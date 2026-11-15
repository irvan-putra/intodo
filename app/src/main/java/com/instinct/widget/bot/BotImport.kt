package com.instinct.widget.bot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Drop-in "Import bot backup" flow for BotActivity.
 *
 * Restores BOTH halves of the bot from one .dcmig bundle written by the
 * old app's export:
 *   - the Delta Chat account (core import_backup, SQLCipher passphrase)
 *   - the bot's own state (bot_state.json + ops-*.db stores, Fernet)
 * Both are verified (sha256 manifest) and decrypted BEFORE anything is
 * written. The import runs as a journaled transaction across all three
 * stores (Delta Chat account, bot state files, SharedPreferences):
 *   1. import_prepare writes a durable journal BEFORE the first account
 *      mutation (the journal proves any configured account found later
 *      was created by the import - a pre-existing user account is never
 *      removed), imports the account on a fresh slot, stages state and
 *      settings, commits nothing;
 *   2. a durable, type-tagged snapshot of every SharedPreferences file to
 *      be touched is written BEFORE any settings change, then the staged
 *      settings are applied; any failure restores prior contents and
 *      aborts the WHOLE import;
 *   3. import_commit flips the journal past the decision point and lands
 *      the state idempotently - a crash anywhere after the flip is rolled
 *      FORWARD by recovery, a crash before it is rolled back.
 * recoverInterruptedImport (BotActivity.onCreate, before any service
 * start) finishes or undoes an interrupted import and cleans an
 * interrupted export; isRecoveryComplete() gates every control that
 * could race it, failing CLOSED when recovery itself fails.
 *
 * Wiring (BotActivity):
 *   private val importer = BotImport(this) { result -> ... }
 *   importButton.setOnClickListener { importer.launch() }
 *   override fun onActivityResult(...) { importer.handleActivityResult(...) }
 * On result.ok, BotActivity may start BotService for the first time.
 */
class BotImport(
    private val activity: Activity,
    private val callback: (Result) -> Unit,
) {
    data class Result(
        val ok: Boolean,
        val addr: String?,
        val error: String?,
        val prefsWarning: String? = null,
    )

    private var bundleFile: File? = null

    fun launch() {
        AlertDialog.Builder(activity)
            .setTitle("Import bot backup")
            .setMessage(
                "Move the existing bot (its Delta Chat identity, chats and " +
                "per-chat lists) into itdapk from the .dcmig bundle exported by the " +
                "old app. You will also need the one-time transfer code " +
                "shown there.\n\nThe bot service stays stopped during the " +
                "import.")
            .setPositiveButton("Choose bundle file") { _, _ -> stopServiceThenPick() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopServiceThenPick() {
        // Same stop pattern as the scratch app: clear the persisted mode so
        // a sticky restart does not revive the service mid-import.
        activity.getSharedPreferences("bot", Activity.MODE_PRIVATE)
            .edit().putString("last_mode", "").apply()
        activity.stopService(Intent(activity, BotService::class.java))
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_PICK_BUNDLE)
    }

    /** Call from BotActivity.onActivityResult. Returns true when consumed. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PICK_BUNDLE) return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) return true
        var tmp: File? = null
        try {
            tmp = File.createTempFile("import-", ".dcmig", activity.cacheDir)
            activity.contentResolver.openInputStream(data.data!!).use { input ->
                FileOutputStream(tmp).use { out -> input?.copyTo(out) }
            }
            bundleFile = tmp
            promptCode()
        } catch (e: Exception) {
            tmp?.delete()
            Toast.makeText(activity, "Could not read bundle: $e", Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun promptCode() {
        val input = EditText(activity).apply {
            // visible-password: no autocorrect, no dictionary learning, no
            // clipboard suggestions for the transfer code
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "one-time transfer code"
        }
        AlertDialog.Builder(activity)
            .setTitle("Transfer code")
            .setMessage("Shown once by the old app during export (44 characters).")
            .setView(input)
            .setPositiveButton("Import") { _, _ -> runImport(input.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> cleanup() }
            .show()
    }

    private fun runImport(code: String) {
        val bundle = bundleFile ?: return
        Toast.makeText(activity, "Importing...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
                val mod = Python.getInstance().getModule("migration")
                val nativeLib = activity.applicationInfo.nativeLibraryDir
                val files = activity.filesDir.absolutePath
                val addr = mod.callAttr("import_prepare", nativeLib, files,
                                        bundle.absolutePath, code).toString()
                val applier = StagedPrefsApplier(
                    File(activity.filesDir, "IMPORT_PREFS.json"),
                    File(activity.filesDir, "IMPORT_PRIOR_PREFS.json")) { name ->
                    SharedPrefsFile(activity.getSharedPreferences(name, Activity.MODE_PRIVATE))
                }
                // Durable rollback knowledge BEFORE the first settings write:
                // survives a process death mid-apply.
                applier.writePriorSnapshot()
                val prefsError = applier.applyStaged()
                if (prefsError != null) {
                    mod.callAttr("import_abort", nativeLib, files)
                    val restoreErr = applier.restoreFromDurableSnapshot()
                    if (restoreErr == null) {
                        applier.discardPriorSnapshot()
                        Result(false, null,
                            "Settings restore failed ($prefsError). The import was rolled " +
                            "back and your previous settings are intact.")
                    } else {
                        // Never claim a rollback that did not happen: the
                        // durable snapshot is kept so a restart can retry.
                        Result(false, null,
                            "Settings restore failed ($prefsError). The imported account " +
                            "was removed, BUT restoring your previous settings also failed " +
                            "($restoreErr). Your previous-settings snapshot is still on the " +
                            "device - restart the app to retry the restore.")
                    }
                } else {
                    try {
                        mod.callAttr("import_commit", nativeLib, files)
                        Result(true, addr, null)
                    } catch (e: Exception) {
                        // import_commit already removed the imported account
                        // and KEPT the durable prior-settings snapshot.
                        val restoreErr = applier.restoreFromDurableSnapshot()
                        if (restoreErr == null) {
                            applier.discardPriorSnapshot()
                            Result(false, null,
                                (e.message ?: e.toString()) + " - the import was rolled back " +
                                "and your previous settings are intact.")
                        } else {
                            Result(false, null,
                                (e.message ?: e.toString()) + " - the imported account was " +
                                "removed, BUT restoring your previous settings also failed " +
                                "($restoreErr). Your previous-settings snapshot is still on " +
                                "the device - restart the app to retry the restore.")
                        }
                    }
                }
            } catch (e: Exception) {
                Result(false, null, e.message ?: e.toString())
            }
            activity.runOnUiThread {
                cleanup()
                callback(result)
            }
        }.start()
    }

    private fun cleanup() {
        bundleFile?.delete()
        bundleFile = null
    }

    companion object {
        // 43 is BotActivity.REQ_PICK_XDC (publish flow); they must not collide.
        const val REQ_PICK_BUNDLE = 44

        enum class RecoveryState { PENDING, DONE, FAILED }
        private val recoveryState = AtomicReference(RecoveryState.PENDING)

        /** False until crash recovery finished; on failure it stays false
         * (fail CLOSED): controls that could race recovery must not run. */
        @JvmStatic
        fun isRecoveryComplete(): Boolean = recoveryState.get() == RecoveryState.DONE

        /**
         * Call from BotActivity.onCreate BEFORE building controls that can
         * start the service, import or export. Finishes or undoes an
         * interrupted import (per the durable journal) and cleans an
         * interrupted export. Cheap no-op fast path when nothing was
         * interrupted; otherwise runs once on a background thread and
         * flips isRecoveryComplete() when done - or fails closed.
         */
        @JvmStatic
        fun recoverInterruptedImport(activity: Activity) {
            val filesDir = activity.filesDir
            val journal = File(filesDir, "IMPORT_PENDING.json")
            val staged = File(filesDir, "IMPORT_PREFS.json")
            val prior = File(filesDir, "IMPORT_PRIOR_PREFS.json")
            val staging = File(filesDir, ".import-staging")
            val backupTmp = File(filesDir, ".import-backup.tmp")
            val exportOut = File(filesDir, "export-out")
            val exportPrefs = File(filesDir, "EXPORT_PREFS.json")
            val exportResult = File(filesDir, "EXPORT_RESULT")
            val needsWork = journal.exists() || staged.exists() || prior.exists() ||
                staging.exists() || backupTmp.exists() ||
                (!exportResult.exists() && (exportOut.exists() || exportPrefs.exists()))
            if (!needsWork) {
                recoveryState.set(RecoveryState.DONE)
                return
            }
            Thread {
                try {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
                    val mod = Python.getInstance().getModule("migration")
                    val nativeLib = activity.applicationInfo.nativeLibraryDir
                    val files = filesDir.absolutePath
                    val open = { name: String ->
                        SharedPrefsFile(activity.getSharedPreferences(name, Activity.MODE_PRIVATE))
                    }
                    when (mod.callAttr("recover_import", nativeLib, files).toString()) {
                        "rollback_prefs", "restore_orphan_prior" -> {
                            val err = StagedPrefsApplier(staged, prior, open)
                                .restoreFromDurableSnapshot()
                            if (err == null) {
                                prior.delete()
                            } else {
                                throw IllegalStateException("settings restore failed: $err")
                            }
                        }
                        "rolled_forward" ->
                            Log.w("BotImport", "finished an interrupted import")
                        else -> {
                            // "none"/"aborted": a completed one-call import may
                            // still hold staged settings for the activity.
                            if (staged.exists()) {
                                StagedPrefsApplier(staged, prior, open).applyStaged()
                                    ?.let { throw IllegalStateException("staged settings apply failed: $it") }
                            }
                        }
                    }
                    mod.callAttr("recover_export", files)
                    recoveryState.set(RecoveryState.DONE)
                } catch (e: Exception) {
                    Log.w("BotImport", "import/export recovery failed - controls stay locked", e)
                    recoveryState.set(RecoveryState.FAILED)
                }
            }.start()
        }
    }
}

/** One app SharedPreferences file behind the transactional applier. */
internal interface PrefsFile {
    fun readAll(): Map<String, *>
    /** Overlay staged keys onto existing content; false on commit failure. */
    fun mergeAll(values: Map<String, Any>): Boolean
    /** Restore exact prior content (clear + write); false on commit failure. */
    fun replaceAll(values: Map<String, *>): Boolean
}

internal class SharedPrefsFile(private val sp: SharedPreferences) : PrefsFile {
    override fun readAll(): Map<String, *> = sp.all

    override fun mergeAll(values: Map<String, Any>): Boolean {
        val ed = sp.edit()
        for ((k, v) in values) when (v) {
            is String -> ed.putString(k, v)
            is Long -> ed.putLong(k, v)
            is Int -> ed.putLong(k, v.toLong())
            else -> return false
        }
        return ed.commit()
    }

    override fun replaceAll(values: Map<String, *>): Boolean {
        val ed = sp.edit().clear()
        for ((k, v) in values) when (v) {
            is String -> ed.putString(k, v)
            is Boolean -> ed.putBoolean(k, v)
            is Int -> ed.putInt(k, v)
            is Long -> ed.putLong(k, v)
            is Float -> ed.putFloat(k, v)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                ed.putStringSet(k, v as Set<String>)
            }
            else -> return false
        }
        return ed.commit()
    }
}

/**
 * Transactional apply of the dcmig/2 settings snapshot staged by
 * import_prepare as IMPORT_PREFS.json (Python must not write shared_prefs
 * XML under a running app).
 *
 * Rollback knowledge is DURABLE: writePriorSnapshot() persists a
 * type-tagged copy of every file to be touched before any write, so a
 * process death mid-apply can still be undone by
 * restoreFromDurableSnapshot() in a later process. For in-process
 * failures applyStaged() also restores from its in-memory snapshot.
 * Only after every file commits is the staging file deleted.
 */
internal class StagedPrefsApplier(
    private val file: File,
    private val priorFile: File,
    private val open: (String) -> PrefsFile,
) {
    /** Persist a type-tagged snapshot of every file the staged settings
     * will touch. Call BEFORE applyStaged(); no-op when nothing is staged. */
    fun writePriorSnapshot() {
        if (!file.exists()) return
        val root = org.json.JSONObject(file.readText())
        val snap = org.json.JSONObject()
        for (name in root.keys()) {
            val tagged = org.json.JSONObject()
            for ((k, v) in open(name).readAll()) {
                val t = org.json.JSONObject()
                when (v) {
                    is String -> { t.put("t", "S"); t.put("v", v) }
                    is Int -> { t.put("t", "I"); t.put("v", v) }
                    is Long -> { t.put("t", "L"); t.put("v", v) }
                    is Float -> { t.put("t", "F"); t.put("v", v.toDouble()) }
                    is Boolean -> { t.put("t", "B"); t.put("v", v) }
                    is Set<*> -> { t.put("t", "SS"); t.put("v", org.json.JSONArray(v)) }
                    else -> continue  // unknown type: skip rather than corrupt
                }
                tagged.put(k, t)
            }
            snap.put(name, tagged)
        }
        val tmp = File(priorFile.parentFile, priorFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(snap.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        if (!tmp.renameTo(priorFile)) {
            throw IllegalStateException("could not stage prior-settings snapshot")
        }
    }

    /** Restore every snapshotted file from the DURABLE prior snapshot
     * (safe to call from a fresh process). null on success / nothing to do. */
    fun restoreFromDurableSnapshot(): String? {
        if (!priorFile.exists()) return null
        return try {
            val root = org.json.JSONObject(priorFile.readText())
            for (name in root.keys()) {
                val tagged = root.getJSONObject(name)
                val values = LinkedHashMap<String, Any?>()
                for (k in tagged.keys()) {
                    val t = tagged.getJSONObject(k)
                    values[k] = when (t.getString("t")) {
                        "S" -> t.getString("v")
                        "I" -> t.getInt("v")
                        "L" -> t.getLong("v")
                        "F" -> t.getDouble("v").toFloat()
                        "B" -> t.getBoolean("v")
                        "SS" -> t.getJSONArray("v").let { arr ->
                            (0 until arr.length()).mapTo(HashSet<String>()) { arr.getString(it) }
                        }
                        else -> throw IllegalArgumentException(
                            "unknown tag in prior snapshot for $name/$k")
                    }
                }
                if (!open(name).replaceAll(values)) {
                    throw IllegalStateException("restore commit failed for $name")
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /** Delete the durable prior snapshot once its restore is confirmed
     * (or the commit made it obsolete). */
    fun discardPriorSnapshot() {
        priorFile.delete()
    }

    /** Apply the staged settings transactionally; null on success (or
     * nothing staged), the error message on failure (with in-memory
     * restore of every touched file already attempted). */
    fun applyStaged(): String? {
        if (!file.exists()) return null
        val root = try {
            org.json.JSONObject(file.readText())
        } catch (e: Exception) {
            return "unreadable staged settings: ${e.message}"
        }
        val names = root.keys().asSequence().toList()
        val prior = LinkedHashMap<String, Map<String, *>>()
        val staged = LinkedHashMap<String, Map<String, Any>>()
        try {
            for (name in names) {
                val values = root.getJSONObject(name)
                val m = LinkedHashMap<String, Any>()
                for (k in values.keys()) {
                    when (val v = values.get(k)) {
                        is String -> m[k] = v
                        is Number -> m[k] = v.toLong()
                        else -> throw IllegalArgumentException("unexpected value type for $name/$k")
                    }
                }
                staged[name] = m
                prior[name] = open(name).readAll()
            }
            // commit, not apply: a crash mid-restore must not leave half
            // the settings written while the staging file is gone.
            for ((name, m) in staged) {
                if (!open(name).mergeAll(m)) throw IllegalStateException("commit failed for $name")
            }
        } catch (e: Exception) {
            var restoreError: String? = null
            for ((name, p) in prior) {
                try {
                    if (!open(name).replaceAll(p)) restoreError = "restore commit failed for $name"
                } catch (re: Exception) {
                    restoreError = re.message ?: re.toString()
                }
            }
            return (e.message ?: e.toString()) +
                (restoreError?.let { " (prior settings restore also failed: $it)" } ?: "")
        }
        file.delete()
        return null
    }
}
