package com.vanipy.intodo.bot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import com.chaquo.python.PyObject
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
        // A refused import keeps its picked bundle cached: a retry
        // re-offers the SAME bundle instead of forcing a re-select.
        bundleFile?.takeIf { it.exists() }?.let {
            promptCode()
            return
        }
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
        migrationBlockReason()?.let {
            Toast.makeText(activity, "Wait - $it.", Toast.LENGTH_LONG).show()
            return
        }
        if (File(activity.filesDir, "RUNNING").exists()) {
            Toast.makeText(activity,
                "Stop the bot first, then import.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "Importing...", Toast.LENGTH_SHORT).show()
        Thread {
            val nativeLib = activity.applicationInfo.nativeLibraryDir
            val files = activity.filesDir.absolutePath
            var mod: PyObject? = null
            var applier: StagedPrefsApplier? = null
            var prepared = false
            // run {} so an abort-failure deep inside the try can
            // return its Result with proper control flow - the
            // Thread lambda itself must return Unit, which is what
            // the CI compile failure (v5.3, line 189) hit.
            // The whole import transaction runs under the migration
            // lock, admitted ATOMICALLY: the lifecycle must be idle and
            // the bot runtime not live, claimed in one synchronized
            // block (no check-then-act window). A service start (boot,
            // sticky restart, the user tapping Start bot) queues behind
            // it and never recovers a journal mid-transaction.
            val admitted = withImportAdmission(File(files, "RUNNING")) {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
                val m = Python.getInstance().getModule("migration")
                mod = m
                val addr = m.callAttr("import_prepare", nativeLib, files,
                                      bundle.absolutePath, code).toString()
                // From here on an imported account and the durable journal
                // may exist: EVERY later failure must resolve the journal
                // (abort) or fail closed - never a generic error that
                // claims nothing changed.
                prepared = true
                val ap = StagedPrefsApplier(
                    File(activity.filesDir, "IMPORT_PREFS.json"),
                    File(activity.filesDir, "IMPORT_PRIOR_PREFS.json")) { name ->
                    SharedPrefsFile(activity.getSharedPreferences(name, Activity.MODE_PRIVATE))
                }
                applier = ap
                // Durable rollback knowledge BEFORE the first settings write:
                // survives a process death mid-apply.
                ap.writePriorSnapshot()
                val prefsError = ap.applyStaged()
                if (prefsError != null) {
                    m.callAttr("import_abort", nativeLib, files)
                    val restoreErr = ap.restoreFromDurableSnapshot()
                    if (restoreErr == null) ap.discardPriorSnapshot()
                    Result(false, null,
                        messageAfterAbort("Settings restore failed ($prefsError)", restoreErr))
                } else {
                    try {
                        m.callAttr("import_commit", nativeLib, files)
                        Result(true, addr, null)
                    } catch (e: Exception) {
                        val msg = e.message ?: e.toString()
                        if (isCommitInterrupted(msg)) {
                            // Post-decision: Python kept the account, journal,
                            // staging and both snapshots. Restore and delete
                            // NOTHING - recovery finishes the import on the
                            // next app start. Never claim a rollback here.
                            // Lock the gated controls until that restart.
                            lockControlsUntilRecovery()
                            Result(false, null,
                                "The import was interrupted while finishing. Nothing was " +
                                "rolled back - restart the app once and it will complete " +
                                "the import.")
                        } else {
                            // Pre-decision failure: undo the prepared import,
                            // then restore the prior settings. A failed abort
                            // keeps the journal; recovery retries the cleanup
                            // on the next start, so say so instead of
                            // claiming a rollback.
                            try {
                                m.callAttr("import_abort", nativeLib, files)
                            } catch (abortErr: Exception) {
                                lockControlsUntilRecovery()
                                return@withImportAdmission Result(false, null, msg +
                                    " - and rolling the import back also failed (" +
                                    (abortErr.message ?: abortErr.toString()) + "). Nothing was " +
                                    "deleted; controls stay locked - restart the app and " +
                                    "recovery will finish the cleanup.")
                            }
                            val restoreErr = ap.restoreFromDurableSnapshot()
                            if (restoreErr == null) ap.discardPriorSnapshot()
                            Result(false, null, messageAfterAbort(msg, restoreErr))
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                val m = mod
                if (failureDisposition(prepared, m != null, activity)
                    == FailureDisposition.CLEAN_ERROR) {
                    // Failure before the account/journal existed (bundle
                    // pick, passcode, rpc open) with NO import artifacts on
                    // disk: nothing was imported and there is nothing to
                    // undo. A prepare exception AFTER its journal/account
                    // writes lands here too - the surviving journal moves
                    // the decision to the resolution branch.
                    Result(false, null, msg)
                } else {
                    // RESOLVE_JOURNAL: a post-prepare escape (prior-snapshot
                    // IO failure, an abort RPC failure above) or a prepare
                    // exception that left its journal behind. An imported
                    // account and the journal may exist, so resolve the
                    // journal now.
                    try {
                        m.callAttr("import_abort", nativeLib, files)
                        val restoreErr = applier?.restoreFromDurableSnapshot()
                        if (restoreErr == null) applier?.discardPriorSnapshot()
                        Result(false, null, messageAfterAbort(msg, restoreErr))
                    } catch (abortErr: Exception) {
                        // Fail CLOSED: lock every gated control until the
                        // restart recovery resolves the journal.
                        lockControlsUntilRecovery()
                        Result(false, null, msg +
                            " - and the import could not be undone (" +
                            (abortErr.message ?: abortErr.toString()) +
                            "). Controls stay locked; restart the app and recovery will finish " +
                            "the cleanup.")
                    }
                }
            }
            }
            if (admitted == null) {
                // Refused at admission: KEEP the picked bundle cached, so
                // retrying Import re-offers it instead of forcing a
                // re-select.
                activity.runOnUiThread {
                    callback(Result(false, null,
                        "Wait - the bot or another migration step is still running."))
                }
                return@Thread
            }
            val result = admitted
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

        /** Fail-closed lock used by every post-prepare failure path that
         * leaves state recovery cannot resolve in this process: gated
         * controls stay locked until the restart recovery runs. */
        internal fun lockControlsUntilRecovery() {
            recoveryState.set(RecoveryState.FAILED)
        }

        /** The single decision every abort-succeeded failure branch in
         * runImport funnels through: a CLEAN restore means the rollback
         * is complete and the gate stays open; a restore ERROR leaves
         * settings possibly half-written, so the gate LOCKS until the
         * restart recovery restores from the durable snapshot. Never
         * claims a rollback that did not happen. */
        internal fun messageAfterAbort(base: String, restoreErr: String?): String =
            if (restoreErr == null) {
                "$base - the import was rolled back and your previous settings are intact."
            } else {
                lockControlsUntilRecovery()
                "$base - the imported account was removed, BUT restoring your " +
                "previous settings also failed ($restoreErr). Your previous-settings " +
                "snapshot is still on the device - restart the app to retry the restore."
            }

        /** Unit-test hook: the gate is static, so tests reset it between
         * cases. Production code never calls this. */
        internal fun resetRecoveryGateForTest() {
            recoveryState.set(RecoveryState.DONE)
        }
        private val recoveryState = AtomicReference(RecoveryState.PENDING)

        /** False until crash recovery finished; on failure it stays false
         * (fail CLOSED): controls that could race recovery must not run. */
        @JvmStatic
        fun isRecoveryComplete(): Boolean = recoveryState.get() == RecoveryState.DONE

        /** Outcome of the activity recovery barrier: CLEAN (nothing
         * pending or fully resolved), DEFERRED (a live lifecycle owner
         * blocks recovery right now - retry when it finishes, this is
         * NOT a failure), FAILED (recovery itself failed; fail closed). */
        enum class RecoveryOutcome { CLEAN, DEFERRED, FAILED }

        /** One-shot retry slot for a DEFERRED activity recovery. Set
         * under migrationLock, fired OFF the lock exactly once when the
         * lifecycle reaches IDLE - no busy loop, no concurrent cleanup. */
        private var deferredRecovery: (() -> Unit)? = null

        internal fun deferRecoveryUntilIdle(task: () -> Unit) {
            synchronized(migrationLock) {
                deferredRecovery = task
                if (migrationPhase != MigrationPhase.IDLE) return
            }
            // The owner finished between the DEFERRED outcome and this
            // registration: fire now.
            fireDeferredRecovery()
        }

        @PublishedApi
        internal fun fireDeferredRecovery() {
            val task = synchronized(migrationLock) {
                val t = deferredRecovery
                deferredRecovery = null
                t
            } ?: return
            Thread { task() }.start()
        }

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
            if (!recoveryPending(activity)) {
                recoveryState.set(RecoveryState.DONE)
                return
            }
            Thread {
                when (runRecoveryBlocking(activity)) {
                    RecoveryOutcome.CLEAN ->
                        recoveryState.set(RecoveryState.DONE)
                    RecoveryOutcome.FAILED -> {
                        Log.w("BotImport", "import/export recovery failed - controls stay locked")
                        lockControlsUntilRecovery()
                    }
                    RecoveryOutcome.DEFERRED ->
                        // A live owner (export/service/import) holds the
                        // lifecycle: NOT a failure. Re-run once when that
                        // owner finishes - no busy loop, and the single
                        // slot keeps concurrent cleanups impossible.
                        deferRecoveryUntilIdle { recoverInterruptedImport(activity) }
                }
            }.start()
        }

        /** Best-effort parent-directory fsync so a tmp+rename is durable
         * across a POWER LOSS, not only a process death. Support varies
         * by filesystem/Android build; a failure is logged, never fatal
         * (the file itself was already fsynced). */
        fun fsyncParentDir(file: File) {
            val parent = file.parentFile ?: return
            try {
                val fd = android.system.Os.open(
                    parent.absolutePath, android.system.OsConstants.O_RDONLY, 0)
                try {
                    android.system.Os.fsync(fd)
                } finally {
                    android.system.Os.close(fd)
                }
            } catch (e: Exception) {
                Log.w("BotImport", "parent dir fsync skipped: ${e.message}")
            }
        }

        /** True when import-side artifacts (journal, staged/prior
         * settings, staging/backup temps) exist on disk. This is the
         * durable signal a prepare exception cannot hide: import_prepare
         * may throw AFTER writing the journal and importing the account,
         * so callers must check the disk, not only their own flags. */
        internal fun importPendingOnDisk(context: Context): Boolean {
            val f = context.filesDir
            return File(f, "IMPORT_PENDING.json").exists() ||
                File(f, "IMPORT_PREFS.json").exists() ||
                File(f, "IMPORT_PRIOR_PREFS.json").exists() ||
                File(f, ".import-staging").exists() ||
                File(f, ".import-backup.tmp").exists()
        }

        /** What a failed import must do next. RESOLVE_JOURNAL when an
         * imported account/journal may exist (prepare returned, or its
         * artifacts are on disk) - the caller undoes the import or fails
         * closed. CLEAN_ERROR only when Python never loaded or no import
         * artifact exists, meaning nothing changed. */
        internal enum class FailureDisposition { CLEAN_ERROR, RESOLVE_JOURNAL }
        internal fun failureDisposition(
            prepared: Boolean,
            pythonLoaded: Boolean,
            context: Context,
        ): FailureDisposition =
            if (pythonLoaded && (prepared || importPendingOnDisk(context)))
                FailureDisposition.RESOLVE_JOURNAL
            else
                FailureDisposition.CLEAN_ERROR

        /** True when interrupted import/export artifacts exist that
         * recovery must resolve before any bot work starts. */
        fun recoveryPending(context: Context): Boolean {
            val f = context.filesDir
            return importPendingOnDisk(context) ||
                (!File(f, "EXPORT_RESULT").exists() &&
                    (File(f, "export-out").exists() || File(f, "EXPORT_PREFS.json").exists()))
        }

        /** Whole-lifecycle migration serialization. The manifest declares
         * no android:process on BotActivity, BotService or BootReceiver, so
         * every caller lives in ONE process and this single lock serializes
         * EVERY migration mutation: a live import, an export phase and
         * crash recovery can never overlap each other, and the service
         * barrier queues behind a live import instead of "recovering" a
         * journal mid-transaction. The pending re-check happens INSIDE the
         * lock, so a caller queued behind a finished transaction sees the
         * final disk state. Inline so callers can return@
         * the wrapper label with a value out of the body. */
        @PublishedApi
        internal val migrationLock = Any()

        /** Which migration work holds the lifecycle right now. Read at any
         * time (volatile) for the UI/service permission boundary; written
         * only under migrationLock, and every transition out of an active
         * phase requires the OWNER TOKEN that claimed it - a superseded
         * worker or a late callback can never clear another owner's
         * phase. */
        internal enum class MigrationPhase { IDLE, IMPORTING, EXPORTING, RECOVERING, SERVICE }
        @Volatile
        internal var migrationPhase = MigrationPhase.IDLE
            @PublishedApi internal set
        private var phaseOwner: Any? = null
        private val importToken = Any()
        private val activityRecoveryToken = Any()

        /** The runtime's RUNNING marker holds its pid (bot_runtime writes
         * os.getpid()). Liveness is grounded on the actual process: a
         * marker whose pid is dead is STALE - left by a killed process -
         * never a reason to refuse work, and safe to clear. */
        internal fun runningMarkerIsLive(marker: File): Boolean {
            if (!marker.exists()) return false
            // Fail closed: only a parseable pid whose /proc entry is gone
            // proves the runtime dead (stale). A malformed or unreadable
            // marker cannot prove no runtime is alive -> treated as live
            // (refused, never cleared). PID reuse errs conservative: an
            // existing /proc entry blocks rather than risks a live one.
            val pid = try {
                marker.readText().trim().toIntOrNull()
            } catch (e: Exception) {
                null
            } ?: return true
            return File("/proc", pid.toString()).exists()
        }

        internal inline fun <T> withMigrationLock(
            phase: MigrationPhase,
            body: () -> T,
        ): T = synchronized(migrationLock) {
            val prev = migrationPhase
            migrationPhase = phase
            try {
                body()
            } finally {
                migrationPhase = prev
            }
        }

        /** Atomic import admission, under the SAME serialization as every
         * other entry: the lifecycle must be IDLE (no recovery, export,
         * service reservation or other import anywhere) AND the bot
         * runtime must not be live (its RUNNING marker), and the IMPORTING
         * phase is claimed in the same synchronized block - no
         * check-then-act window. Returns null when admission is refused;
         * the body runs holding the lock, so a service start queued behind
         * it sees the final disk state. */
        internal inline fun <T> withImportAdmission(
            runningMarker: File,
            body: () -> T,
        ): T? {
            val result = synchronized(migrationLock) {
                if (migrationPhase != MigrationPhase.IDLE
                    || runningMarkerIsLive(runningMarker)) {
                    null
                } else {
                    // A stale marker (dead pid, process killed) is cleared
                    // here, grounded on the pid check - never a blind unlink.
                    if (runningMarker.exists()) runningMarker.delete()
                    migrationPhase = MigrationPhase.IMPORTING
                    phaseOwner = importToken
                    try {
                        body()
                    } finally {
                        if (phaseOwner === importToken) {
                            migrationPhase = MigrationPhase.IDLE
                            phaseOwner = null
                        }
                    }
                }
            }
            fireDeferredRecovery()
            return result
        }

        /** Why a new mutation or service entry must wait, or null when the
         * lifecycle is idle. This is the service/UI permission boundary:
         * bot start, validate, provision, export and import all consult it
         * (the service barrier additionally QUEUES on the lock itself). */
        fun migrationBlockReason(): String? = when (migrationPhase) {
            MigrationPhase.IMPORTING -> "an import is still running"
            MigrationPhase.EXPORTING -> "an export is still running"
            MigrationPhase.RECOVERING -> "crash recovery is still running"
            MigrationPhase.SERVICE -> "the bot is starting or running"
            MigrationPhase.IDLE -> null
        }

        /** Synchronous recovery barrier for EVERY entry point that can
         * start bot work: the activity, a START_STICKY service restart
         * and the boot receiver all funnel here. Finishes or undoes an
         * interrupted import/export BEFORE any bot mode runs. Returns
         * true when nothing was pending or everything resolved; false
         * when recovery itself failed (the journal remains and the
         * caller must not start bot work). Idempotent, and serialized
         * process-wide through withMigrationLock. */
        /** The actual recovery work, called only under migrationLock. */
        @PublishedApi
        internal fun recoveryBody(context: Context): Boolean {
            if (!recoveryPending(context)) return true
            return try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context))
                val mod = Python.getInstance().getModule("migration")
                val nativeLib = context.applicationInfo.nativeLibraryDir
                val files = context.filesDir.absolutePath
                val staged = File(files, "IMPORT_PREFS.json")
                val prior = File(files, "IMPORT_PRIOR_PREFS.json")
                val open = { name: String ->
                    SharedPrefsFile(context.getSharedPreferences(name, Context.MODE_PRIVATE))
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
                true
            } catch (e: Exception) {
                Log.w("BotImport", "recovery barrier failed", e)
                false
            }
        }

        /** The activity's async recovery path. Runs only when the
         * lifecycle is IDLE: a live owner (service run, export, import)
         * is NEVER stomped - with nothing pending there is nothing to do
         * (CLEAN), and a pending artifact behind an active owner is
         * DEFERRED, never a failure: the caller retries when the owner
         * finishes (deferRecoveryUntilIdle), so a recreated activity
         * during an export no longer locks the gate forever. */
        fun runRecoveryBlocking(context: Context): RecoveryOutcome {
            val outcome = synchronized(migrationLock) {
                if (migrationPhase != MigrationPhase.IDLE) {
                    if (recoveryPending(context))
                        RecoveryOutcome.DEFERRED
                    else
                        RecoveryOutcome.CLEAN
                } else {
                    phaseOwner = activityRecoveryToken
                    migrationPhase = MigrationPhase.RECOVERING
                    val ok = recoveryBody(context)
                    migrationPhase = MigrationPhase.IDLE
                    phaseOwner = null
                    if (ok) RecoveryOutcome.CLEAN else RecoveryOutcome.FAILED
                }
            }
            fireDeferredRecovery()
            return outcome
        }

        /** Outcome of the service-entry barrier + reservation. */
        enum class ReserveResult { RESERVED, RECOVERY_FAILED, LIFECYCLE_ACTIVE }

        /** The service-entry barrier AND reservation, one atomic step.
         * A live owner is rejected outright (LIFECYCLE_ACTIVE) - never
         * overwritten - so two runtimes can never coexist. Otherwise
         * recovery runs under the lock and the SERVICE phase is claimed
         * for [owner] in the same synchronized block: no import can be
         * admitted between the barrier passing and the runtime opening.
         * The reservation outlives the lock; ONLY the same owner token
         * releases it (releaseServiceReservation), so a superseded
         * worker's finally can never clear a newer reservation. */
        fun recoverAndReserveService(context: Context, owner: Any): ReserveResult {
            val result = synchronized(migrationLock) {
                if (migrationPhase != MigrationPhase.IDLE) {
                    ReserveResult.LIFECYCLE_ACTIVE
                } else {
                    phaseOwner = owner
                    migrationPhase = MigrationPhase.RECOVERING
                    val ok = recoveryBody(context)
                    if (ok) {
                        migrationPhase = MigrationPhase.SERVICE
                        ReserveResult.RESERVED
                    } else {
                        migrationPhase = MigrationPhase.IDLE
                        phaseOwner = null
                        ReserveResult.RECOVERY_FAILED
                    }
                }
            }
            fireDeferredRecovery()
            return result
        }

        /** Owner-checked release of the service reservation. */
        fun releaseServiceReservation(owner: Any) {
            synchronized(migrationLock) {
                if (phaseOwner === owner) {
                    migrationPhase = MigrationPhase.IDLE
                    phaseOwner = null
                }
            }
            fireDeferredRecovery()
        }

        /** Owner-checked transition into the export phase. */
        fun markExportPhase(owner: Any) = synchronized(migrationLock) {
            if (phaseOwner === owner) migrationPhase = MigrationPhase.EXPORTING
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
        BotImport.fsyncParentDir(priorFile)
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

/** Python raises post-decision commit errors with this prefix: nothing was
 *  rolled back, recovery finishes the import on the next app start. The
 *  caller must not restore or delete anything for these. */
internal fun isCommitInterrupted(message: String): Boolean =
    message.startsWith("COMMIT_INTERRUPTED:")
