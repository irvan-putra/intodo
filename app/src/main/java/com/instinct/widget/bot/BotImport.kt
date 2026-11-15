package com.instinct.widget.bot

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

    /** Nested in BotImport, NOT the companion: the companion is a FIELD
     * in bytecode, so Java callers cannot name a companion-nested enum
     * in an expression (BotImport.Companion parses as the field), and
     * Kotlin does not resolve companion-nested classifiers through the
     * outer-class qualifier at all. Every type the tests or Java name
     * lives directly in BotImport. */
    enum class ReserveResult { RESERVED, RECOVERY_FAILED, LIFECYCLE_ACTIVE }

    /** Result of a claimExportSetup admission. CLAIMED: the caller owns
     * the export-setup window. ALREADY_CLAIMED, RECOVERY_IN_FLIGHT and
     * RECOVERY_PENDING are TRANSIENT refusals (the UI retries on its
     * poll); RECOVERY_FAILED is the fail-closed gate - crash recovery
     * must succeed before any export may be set up. */
    enum class ExportSetupClaim {
        CLAIMED, ALREADY_CLAIMED, RECOVERY_IN_FLIGHT, RECOVERY_PENDING, RECOVERY_FAILED
    }

    enum class RecoveryState { PENDING, DONE, FAILED }

    /** Outcome of the activity recovery barrier: CLEAN (nothing
     * pending or fully resolved), DEFERRED (a live lifecycle owner
     * blocks recovery right now - retry when it finishes, this is
     * NOT a failure), FAILED (recovery itself failed; fail closed). */
    enum class RecoveryOutcome { CLEAN, DEFERRED, FAILED }

    /** What a failed import must do next. RESOLVE_JOURNAL when an
     * imported account/journal may exist (prepare returned, or its
     * artifacts are on disk) - the caller undoes the import or fails
     * closed. CLEAN_ERROR only when Python never loaded or no import
     * artifact exists, meaning nothing changed. */
    internal enum class FailureDisposition { CLEAN_ERROR, RESOLVE_JOURNAL }

    /** Which migration work holds the lifecycle right now. Read at any
     * time (volatile) for the UI/service permission boundary; written
     * only under migrationLock, and every transition out of an active
     * phase requires the OWNER TOKEN that claimed it - a superseded
     * worker or a late callback can never clear another owner's
     * phase. */
    internal enum class MigrationPhase { IDLE, IMPORTING, EXPORTING, RECOVERING, SERVICE }

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
                        // RESOLVE_JOURNAL implies the module loaded
                        // (failureDisposition gates on it), but the
                        // compiler cannot see that: a null module here
                        // must take the SAME fail-closed path as a
                        // failed abort, never an NPE past the lock.
                        if (m == null) {
                            throw IllegalStateException(
                                "migration module unavailable for journal abort")
                        }
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

        /** Fail-closed lock used by every post-prepare failure path that
         * leaves state recovery cannot resolve in this process: gated
         * controls stay locked until the restart recovery runs. */
        internal fun lockControlsUntilRecovery() {
            synchronized(gateLock) {
                // A NEW failure supersedes any in-flight recovery: bump the
                // generation so its late CLEAN/FAILED publication is
                // discarded instead of overwriting this FAILED.
                ++recoveryGeneration
                recoveryState.set(RecoveryState.FAILED)
            }
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
        internal fun resetRecoveryGateForTest(state: RecoveryState = RecoveryState.DONE) {
            synchronized(gateLock) {
                ++recoveryGeneration
                recoveryState.set(state)
            }
        }
        private val recoveryState = AtomicReference(RecoveryState.PENDING)

        /** Serialization for the UI readiness gate ONLY - the pending
         * check, the generation and every publication. NEVER migrationLock:
         * an import transaction holds that monitor for its whole duration
         * and the main thread (onCreate) must never wait behind it. This
         * lock is only ever held for a disk check or a counter write. */
        private val gateLock = Any()

        /** Generation of the LATEST launched in-process recovery
         * (guarded by gateLock). A worker may publish gate state only for
         * its own generation: runRecoveryBlocking releases the migration
         * phase BEFORE the worker publishes, so without this guard an
         * older worker's late CLEAN or FAILED can overwrite a newer
         * recovery's truthful state - in either direction. */
        private var recoveryGeneration = 0L

        /** Gate state from the LATEST generation only; a superseded
         * worker leaves the gate for the newer authority. */
        private fun publishGateState(generation: Long, state: RecoveryState) {
            synchronized(gateLock) {
                if (generation == recoveryGeneration) recoveryState.set(state)
            }
        }

        /** Test seam (same style as pidAliveProbe): lets JVM gate tests
         * control a recovery's duration/result without Python. Production
         * code never sets it. */
        @Volatile
        internal var recoveryBodyOverride: ((Context) -> Boolean)? = null

        /** Test seam for the migration Python module: JVM tests
         * substitute a fake so the REAL recovery branch - including the
         * recover_export admission - runs under Robolectric, which
         * cannot start Python. Production never sets it. */
        internal var recoveryModuleForTest: RecoveryModule? = null

        /** Test seam: invoked on the recovery worker AFTER
         * runRecoveryBlocking has released the lifecycle phase and BEFORE
         * the gate publication - the exact window where a superseded
         * worker's late publication lands. Production never sets it. */
        @Volatile
        internal var recoveryOutcomeHook: ((RecoveryOutcome) -> Unit)? = null

        /** Test accessor for the raw gate state (isRecoveryComplete
         * collapses PENDING and FAILED into the same false). */
        internal fun recoveryGateStateForTest(): RecoveryState = recoveryState.get()

        /** Ownership for the export SETUP window. The UI handler writes
         * EXPORT_PREFS.json (and deletes EXPORT_RESULT) BEFORE the
         * service claims the lifecycle phase; in between, phase is IDLE
         * and the snapshot is indistinguishable from an interrupted
         * export's leftovers, so an unsynchronized pending check could
         * make recovery delete a LIVE export's settings just before the
         * export starts. While an owner is registered here,
         * exportPendingOnDisk reports those artifacts as not-pending and
         * recoveryBody skips recover_export.
         *
         * v5.24 reconciliation of the custodian's v5.23 review:
         * - Admission is check+claim in ONE synchronized(gateLock) step
         *   on BOTH sides: a worker enters recover_export only after
         *   taking an active-recovery slot under gateLock, and a claim
         *   is refused while any slot is held - so a worker already past
         *   its pending check can never be inside recover_export when a
         *   claim lands. (v5.23's bare CAS closed only claim-vs-claim.)
         * - The claim itself re-checks the gate in the same step (only
         *   DONE admits; FAILED fails closed; PENDING refuses - a worker
         *   can already have removed the disk markers while its settings
         *   restore is still running - and so do still-pending
         *   artifacts): the tap-time recoveryPending() check in the UI
         *   is stale by the time the poll runnable runs.
         * - Every release/spend is owner-checked with the exact token,
         *   parked for the service in a one-slot handoff, so a rejected
         *   or stale service start can never clear ANOTHER flow's claim
         *   (v5.23's unconditional clear could).
         * The main thread never waits: claimExportSetup holds gateLock
         * for a disk check only and refuses fast; the UI retries on a
         * poll. Lock order is migrationLock -> gateLock (a worker holds
         * migrationLock through recoveryBody and takes gateLock briefly);
         * nothing holding gateLock ever takes migrationLock, so no cycle.
         * Process death clears all three statics, so a truly interrupted
         * setup is recovered normally at next launch. Public for the
         * Java UI/service call sites, like recoverAndReserveService. */
        private val exportSetupOwner = AtomicReference<Any?>(null)

        /** In-flight recover_export invocations (guarded by gateLock). */
        private var activeRecoverExports = 0

        /** One-slot token handoff from the claiming UI to the export
         * service instance that start launches (guarded by gateLock). */
        private var exportSetupHandoff: Any? = null

        /** Claim the export-setup window for a NEW export flow. Never
         * blocks; every refusal except RECOVERY_FAILED is transient and
         * the caller's poll retries it. The gate must be DONE: PENDING
         * refuses too - a worker can have removed the last disk marker
         * (recover_import deletes the journal) while its settings
         * restore is still running, and a snapshot taken in that window
         * would capture half-restored settings. */
        fun claimExportSetup(context: Context, owner: Any): ExportSetupClaim =
            synchronized(gateLock) {
                if (exportSetupOwner.get() != null) ExportSetupClaim.ALREADY_CLAIMED
                else if (activeRecoverExports > 0) ExportSetupClaim.RECOVERY_IN_FLIGHT
                else when (recoveryState.get()) {
                    RecoveryState.FAILED -> ExportSetupClaim.RECOVERY_FAILED
                    RecoveryState.PENDING -> ExportSetupClaim.RECOVERY_PENDING
                    RecoveryState.DONE ->
                        if (recoveryPending(context)) ExportSetupClaim.RECOVERY_PENDING
                        else {
                            exportSetupOwner.set(owner)
                            exportSetupHandoff = owner
                            ExportSetupClaim.CLAIMED
                        }
                }
            }

        /** Abandon a claimed setup after a snapshot/start failure, a
         * result-poll timeout or a user abort: export leftovers become
         * recoverable again. Owner-checked: a stale release can never
         * clear a newer claim; null (no live claim) is a safe no-op. */
        fun releaseExportSetup(owner: Any?) {
            if (owner == null) return
            synchronized(gateLock) {
                if (exportSetupOwner.compareAndSet(owner, null))
                    exportSetupHandoff = null
            }
        }

        /** Take the token the claiming UI parked for THIS service start
         * (null for a non-export start or a stale intent). The service
         * spends it with clearExportSetup once its phase reservation
         * protects the export, or when the start is rejected and the
         * export will never run. */
        fun takeExportSetupHandoff(): Any? = synchronized(gateLock) {
            val t = exportSetupHandoff
            exportSetupHandoff = null
            t
        }

        /** Owner-checked spend: only the exact setup token clears the
         * owner, so a rejected/stale service start can never unshield
         * ANOTHER flow's live snapshot. */
        fun clearExportSetup(owner: Any?) {
            if (owner == null) return
            synchronized(gateLock) { exportSetupOwner.compareAndSet(owner, null) }
        }

        /** True while a live export setup owns the export artifacts. */
        fun exportSetupInFlight(): Boolean = exportSetupOwner.get() != null

        /** Unit-test reset of the whole export-setup window state. */
        internal fun resetExportSetupForTest() {
            synchronized(gateLock) {
                exportSetupOwner.set(null)
                exportSetupHandoff = null
                activeRecoverExports = 0
            }
        }

        /** False until crash recovery finished; on failure it stays false
         * (fail CLOSED): controls that could race recovery must not run. */
        @JvmStatic
        fun isRecoveryComplete(): Boolean = recoveryState.get() == RecoveryState.DONE

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
            // Pending check, reset and generation claim are ONE atomic step
            // under gateLock: a fast-path DONE can never interleave with
            // another caller's PENDING reset, and every launched worker is
            // tagged so only the LATEST recovery may publish. Import
            // artifact creation is serialized by migrationLock (imports
            // hold it for the whole transaction), a live export/service
            // holds a non-IDLE phase, and the export SETUP window (the UI
            // writing EXPORT_PREFS.json before the service claims the
            // phase) is owned by exportSetupOwner, which makes
            // recoveryPending skip those live artifacts: a pending==false
            // read here cannot race artifact creation into a false DONE,
            // and recovery never deletes a live export's snapshot.
            val generation = synchronized(gateLock) {
                if (!recoveryPending(activity)) {
                    // The clean fast path is the NEWEST authority on the
                    // gate: it must claim a generation too, or an
                    // in-flight worker whose recovery target vanished
                    // mid-run (pending became false before it returned)
                    // could stamp its late FAILED/CLEAN over this DONE.
                    ++recoveryGeneration
                    recoveryState.set(RecoveryState.DONE)
                    return
                }
                // Reset BEFORE launching: with recovery known-pending, DONE
                // is a lie until THIS recovery finishes.
                recoveryState.set(RecoveryState.PENDING)
                ++recoveryGeneration
            }
            Thread {
                val outcome = runRecoveryBlocking(activity)
                recoveryOutcomeHook?.invoke(outcome)
                when (outcome) {
                    RecoveryOutcome.CLEAN ->
                        publishGateState(generation, RecoveryState.DONE)
                    RecoveryOutcome.FAILED -> {
                        Log.w("BotImport", "import/export recovery failed - controls stay locked")
                        publishGateState(generation, RecoveryState.FAILED)
                    }
                    RecoveryOutcome.DEFERRED ->
                        // A live owner (export/service/import) holds the
                        // lifecycle: NOT a failure, and NOTHING is
                        // published - the gate stays PENDING (truthful)
                        // while the owner runs. The deferred retry below
                        // re-enters recoverInterruptedImport as a NEW
                        // generation once the owner finishes.
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

        /** See BotImport.FailureDisposition for the two outcomes. */
        internal fun failureDisposition(
            prepared: Boolean,
            pythonLoaded: Boolean,
            context: Context,
        ): FailureDisposition =
            if (pythonLoaded && (prepared || importPendingOnDisk(context)))
                FailureDisposition.RESOLVE_JOURNAL
            else
                FailureDisposition.CLEAN_ERROR

        /** True when interrupted export artifacts exist that recovery
         * must resolve. While a LIVE export setup holds its owner token
         * (the UI writes EXPORT_PREFS.json before the service claims the
         * lifecycle phase), those artifacts belong to the live flow and
         * are NOT recovery work; process death clears the static owner,
         * so a truly interrupted setup is recovered at next launch. */
        internal fun exportPendingOnDisk(context: Context): Boolean {
            if (exportSetupInFlight()) return false
            val f = context.filesDir
            return !File(f, "EXPORT_RESULT").exists() &&
                (File(f, "export-out").exists() || File(f, "EXPORT_PREFS.json").exists())
        }

        /** True when interrupted import/export artifacts exist that
         * recovery must resolve before any bot work starts. */
        fun recoveryPending(context: Context): Boolean {
            return importPendingOnDisk(context) || exportPendingOnDisk(context)
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
        @Volatile
        internal var migrationPhase = MigrationPhase.IDLE
            private set

        // @PublishedApi cannot annotate a setter, and inline functions
        // (withMigrationLock/withImportAdmission) may only touch public
        // or @PublishedApi declarations: the phase moves through these
        // two. Callers hold migrationLock - every transition is the
        // serialized one documented on migrationLock.
        @PublishedApi
        internal fun phaseUnderLock(): MigrationPhase = migrationPhase

        @PublishedApi
        internal fun setPhaseUnderLock(phase: MigrationPhase) {
            migrationPhase = phase
        }
        private var phaseOwner: Any? = null
        private val importToken = Any()
        private val activityRecoveryToken = Any()

        /** The runtime's RUNNING marker holds its pid (bot_runtime writes
         * os.getpid()). Liveness is grounded on the actual process: a
         * marker whose pid is dead is STALE - left by a killed process -
         * never a reason to refuse work, and safe to clear. */
        /** Production liveness probe: /proc on Android/Linux. Tests
         * inject a stub - java.lang.ProcessHandle is not on the Android
         * test classpath. The SAFETY direction is unchanged: only a
         * parseable pid the probe reports dead clears the marker. */
        internal val DEFAULT_PID_ALIVE_PROBE: (Int) -> Boolean =
            { pid -> File("/proc", pid.toString()).exists() }

        @Volatile
        internal var pidAliveProbe: (Int) -> Boolean = DEFAULT_PID_ALIVE_PROBE

        internal fun runningMarkerIsLive(marker: File): Boolean {
            if (!marker.exists()) return false
            // Fail closed: only a parseable pid whose process is gone
            // proves the runtime dead (stale). A malformed or unreadable
            // marker cannot prove no runtime is alive -> treated as live
            // (refused, never cleared). PID reuse errs conservative: a
            // live probe blocks rather than risks a live runtime.
            val pid = try {
                marker.readText().trim().toIntOrNull()
            } catch (e: Exception) {
                null
            } ?: return true
            return pidAliveProbe(pid)
        }

        internal inline fun <T> withMigrationLock(
            phase: MigrationPhase,
            body: () -> T,
        ): T = synchronized(migrationLock) {
            val prev = phaseUnderLock()
            setPhaseUnderLock(phase)
            try {
                body()
            } finally {
                setPhaseUnderLock(prev)
            }
        }

        /** Atomic import admission, under the SAME serialization as every
         * other entry: the lifecycle must be IDLE (no recovery, export,
         * service reservation or other import anywhere) AND the bot
         * runtime must not be live (its RUNNING marker), and the IMPORTING
         * phase is claimed in the same synchronized block - no
         * check-then-act window.
         *
         * CONTRACT: this is QUEUE-then-check, not immediate refusal. The
         * body runs holding the lock, so a concurrent caller BLOCKS on
         * the monitor until the active transaction finishes, then sees
         * the final disk state and is admitted or refused on the CURRENT
         * phase/marker. null means refused AT ACQUISITION (active phase
         * or live marker), never "was busy when I called". Callers are
         * all background threads (import worker); the main thread only
         * reads the volatile phase via migrationBlockReason and never
         * waits on this monitor, so no ANR path exists. */
        internal inline fun <T> withImportAdmission(
            runningMarker: File,
            body: () -> T,
        ): T? {
            val result = synchronized(migrationLock) {
                if (phaseUnderLock() != MigrationPhase.IDLE
                    || runningMarkerIsLive(runningMarker)) {
                    null
                } else {
                    // A stale marker (dead pid, process killed) is cleared
                    // here, grounded on the pid check - never a blind unlink.
                    if (runningMarker.exists()) runningMarker.delete()
                    setPhaseUnderLock(MigrationPhase.IMPORTING)
                    phaseOwner = importToken
                    try {
                        body()
                    } finally {
                        if (phaseOwner === importToken) {
                            setPhaseUnderLock(MigrationPhase.IDLE)
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
         * process-wide through synchronized(migrationLock). */
        /** The actual recovery work, called only under migrationLock. */
        @PublishedApi
        internal fun recoveryBody(context: Context): Boolean {
            if (!recoveryPending(context)) return true
            recoveryBodyOverride?.let { return it(context) }
            return try {
                val mod = recoveryModuleForTest ?: run {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(context))
                    val py = Python.getInstance().getModule("migration")
                    object : RecoveryModule {
                        override fun recoverImport(nativeLib: String?, files: String): String =
                            py.callAttr("recover_import", nativeLib, files).toString()
                        override fun recoverExport(files: String) {
                            py.callAttr("recover_export", files)
                        }
                    }
                }
                val nativeLib = context.applicationInfo.nativeLibraryDir
                val files = context.filesDir.absolutePath
                val staged = File(files, "IMPORT_PREFS.json")
                val prior = File(files, "IMPORT_PRIOR_PREFS.json")
                val open = { name: String ->
                    SharedPrefsFile(context.getSharedPreferences(name, Context.MODE_PRIVATE))
                }
                when (mod.recoverImport(nativeLib, files)) {
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
                // A LIVE export setup owns the export artifacts, and the
                // admission is check+slot in ONE gateLock step: either a
                // setup claim landed first (skip) or this worker holds a
                // slot that makes every claim fail fast until the call
                // returns. A claim can NEVER slip between the check and
                // the destructive call (under v5.23's bare check it
                // could: the worker passed the check, the claim landed,
                // then this call deleted the live snapshot). The slot is
                // released in finally; process death kills the worker
                // with it, and recover_export is rerunnable on leftovers.
                val admitted = synchronized(gateLock) {
                    if (exportSetupInFlight()) false else {
                        activeRecoverExports++
                        true
                    }
                }
                if (admitted) {
                    try {
                        mod.recoverExport(files)
                    } finally {
                        synchronized(gateLock) { activeRecoverExports-- }
                    }
                }
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

/** The migration Python module behind a seam: production wraps the
 * Chaquopy module; JVM tests substitute a fake so the REAL recovery
 * branch (including the recover_export admission) runs under
 * Robolectric, which cannot start Python. */
internal interface RecoveryModule {
    /** nativeLib is nullable: Robolectric does not populate
     * ApplicationInfo.nativeLibraryDir, and a non-null Kotlin parameter
     * would throw an intrinsic NPE in a JVM fake before it runs. On
     * device the value is always present; only Python path resolution
     * consumes it. */
    fun recoverImport(nativeLib: String?, files: String): String
    fun recoverExport(files: String)
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
                    values[k] = decodeSnapshotValue(tagged.getJSONObject(k), name, k)
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

    /** Decode one tagged value with an EXPLICIT boxed type per branch
     * and a declared Any return, so nothing between the branch and the
     * map assignment can re-box it. Two layers of library independence:
     * the JSON numeric representation is library-defined (Android
     * org.json parses every integer as Long; desktop org.json uses
     * Integer when it fits), so the value is coerced through Number,
     * and the branch result is boxed explicitly (Integer/Long/Float/
     * Boolean), keeping Int and Long DISTINCT under any JSON library,
     * compiler version or common-type computation. */
    private fun decodeSnapshotValue(
        t: org.json.JSONObject,
        name: String,
        key: String,
    ): Any = when (t.getString("t")) {
        "S" -> t.getString("v")
        "I" -> java.lang.Integer.valueOf((t.get("v") as Number).toInt())
        "L" -> java.lang.Long.valueOf((t.get("v") as Number).toLong())
        "F" -> java.lang.Float.valueOf(t.getDouble("v").toFloat())
        "B" -> java.lang.Boolean.valueOf(t.getBoolean("v"))
        "SS" -> t.getJSONArray("v").let { arr ->
            (0 until arr.length()).mapTo(HashSet<String>()) { arr.getString(it) }
        }
        else -> throw IllegalArgumentException(
            "unknown tag in prior snapshot for $name/$key")
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
