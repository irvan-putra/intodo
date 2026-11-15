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

/**
 * Drop-in "Import bot backup" flow for BotActivity.
 *
 * Restores BOTH halves of the bot from one .dcmig bundle written by the
 * old app's export:
 *   - the Delta Chat account (core import_backup, SQLCipher passphrase)
 *   - the bot's own state (bot_state.json + ops-*.db stores, Fernet)
 * Both are verified (sha256 manifest) and decrypted BEFORE anything is
 * written. The import runs as a transaction across all three stores:
 *   1. import_prepare stages everything and imports the account on a
 *      fresh slot, committing nothing;
 *   2. the staged settings snapshot is applied through SharedPreferences
 *      with every touched file's prior content snapshotted first - any
 *      failure restores all prior contents and aborts the WHOLE import
 *      (imported account removed, staging cleared);
 *   3. import_commit lands the state; its failure rolls back the same way.
 * A crash between prepare and commit/abort leaves a pending marker that
 * recoverInterruptedImport (call from BotActivity.onCreate) cleans up on
 * the next launch. The bundle file itself is never modified.
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
                // Settings go on BEFORE the state commit. A failure here
                // aborts the whole import - account removed, staging
                // cleared - rather than stranding a half-migrated install.
                val prefsError = StagedPrefsApplier(
                    File(activity.filesDir, "IMPORT_PREFS.json")) { name ->
                    SharedPrefsFile(activity.getSharedPreferences(name, Activity.MODE_PRIVATE))
                }.applyStaged()
                if (prefsError != null) {
                    mod.callAttr("import_abort", nativeLib, files)
                    Result(false, null,
                           "settings restore failed ($prefsError) - the whole import was rolled back, nothing was migrated")
                } else {
                    // commit_state failure rolls the import back inside
                    // import_commit itself.
                    mod.callAttr("import_commit", nativeLib, files, true)
                    Result(true, addr, null)
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

        /**
         * Call from BotActivity.onCreate. An import interrupted by a crash
         * leaves either a pending marker (prepare ran, commit never landed:
         * abort back to a clean install) or an un-applied settings staging
         * file (commit landed but the apply did not finish: apply it now).
         */
        @JvmStatic
        fun recoverInterruptedImport(activity: Activity) {
            val pending = File(activity.filesDir, "IMPORT_PENDING.json")
            val staged = File(activity.filesDir, "IMPORT_PREFS.json")
            if (!pending.exists() && !staged.exists()) return
            Thread {
                try {
                    if (pending.exists()) {
                        if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
                        Python.getInstance().getModule("migration").callAttr(
                            "import_abort",
                            activity.applicationInfo.nativeLibraryDir,
                            activity.filesDir.absolutePath)
                        Log.w("BotImport", "rolled back an interrupted import")
                    } else {
                        StagedPrefsApplier(staged) { name ->
                            SharedPrefsFile(activity.getSharedPreferences(name, Activity.MODE_PRIVATE))
                        }.applyStaged()?.let { Log.w("BotImport", "settings recovery failed: $it") }
                    }
                } catch (e: Exception) {
                    Log.w("BotImport", "import recovery failed", e)
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
 * Every touched file's prior content is snapshotted before any write; a
 * failure at ANY file restores all prior contents and keeps the staging
 * file so the import can be aborted or retried with nothing half-applied.
 * Only after every file commits is the staging file deleted. Returns null
 * on success (or nothing staged) and the error message on failure.
 */
internal class StagedPrefsApplier(
    private val file: File,
    private val open: (String) -> PrefsFile,
) {
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
