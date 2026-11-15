package com.vanipy.intodo.bot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

class CommitInterruptedContractTest {
    @Test
    fun commitInterruptedPrefixIsRecognized() {
        assertTrue(isCommitInterrupted(
            "COMMIT_INTERRUPTED: the import passed its decision point but did not finish (boom)."))
    }

    @Test
    fun ordinaryCommitFailuresAreNotCommitInterrupted() {
        assertFalse(isCommitInterrupted("some rpc error"))
        assertFalse(isCommitInterrupted(""))
        assertFalse(isCommitInterrupted("COMMIT_INTERRUPTED without colon"))
    }
}

class RecoveryGateLockTest {
    @Test
    fun lockControlsUntilRecoveryFailsTheGateClosed() {
        BotImport.resetRecoveryGateForTest()
        assertTrue(BotImport.isRecoveryComplete())
        BotImport.lockControlsUntilRecovery()
        // Every restore-error path locks the gate: no control may run
        // while settings sit half-written until restart recovery.
        assertFalse(BotImport.isRecoveryComplete())
        BotImport.resetRecoveryGateForTest()
    }
}

class CallerFailureBranchLockTest {
    @Test
    fun restoreErrorAfterSuccessfulAbortLocksTheGate() {
        // The exact decision every abort-succeeded caller branch makes:
        // a failed restore means partial settings, so the gate LOCKS.
        BotImport.resetRecoveryGateForTest()
        val msg = BotImport.messageAfterAbort("base failure", "io error")
        assertFalse(BotImport.isRecoveryComplete())
        assertTrue(msg.contains("restart the app"))
        BotImport.resetRecoveryGateForTest()
    }

    @Test
    fun cleanRestoreAfterSuccessfulAbortKeepsTheGateOpen() {
        BotImport.resetRecoveryGateForTest()
        val msg = BotImport.messageAfterAbort("base failure", null)
        assertTrue(BotImport.isRecoveryComplete())
        assertTrue(msg.contains("rolled back"))
    }
}

/**
 * The prepare-exception gap: import_prepare can throw AFTER writing its
 * journal and importing the account (e.g. a failed old-account removal
 * intentionally keeps the journal). The caller's own `prepared` flag is
 * still false then, so the disposition decision must read the DISK, not
 * the flag - otherwise the gate stays open over a pending journal.
 * Robolectric provides the Context/filesDir these checks use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PrepareExceptionDispositionTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun artifact(name: String): File = File(context.filesDir, name)

    @Test
    fun prepareThrowWithSurvivingJournalResolvesJournal() {
        // Simulate import_prepare's durable journal surviving its throw.
        artifact("IMPORT_PENDING.json").writeText(
            """{"phase": "prepared", "addr": "old@example.org"}""")
        try {
            assertEquals(
                BotImport.FailureDisposition.RESOLVE_JOURNAL,
                BotImport.failureDisposition(
                    prepared = false, pythonLoaded = true, context = context))
            assertTrue(BotImport.recoveryPending(context))
        } finally {
            artifact("IMPORT_PENDING.json").delete()
        }
    }

    @Test
    fun prepareThrowWithoutArtifactsIsACleanError() {
        // Python loaded but nothing reached the disk: nothing to undo.
        assertEquals(
            BotImport.FailureDisposition.CLEAN_ERROR,
            BotImport.failureDisposition(
                prepared = false, pythonLoaded = true, context = context))
        assertFalse(BotImport.recoveryPending(context))
    }

    @Test
    fun postPrepareEscapeResolvesJournalEvenWithCleanDisk() {
        // prepare returned; a later step failed before any artifact was
        // written. The imported account exists, so resolve the journal.
        assertEquals(
            BotImport.FailureDisposition.RESOLVE_JOURNAL,
            BotImport.failureDisposition(
                prepared = true, pythonLoaded = true, context = context))
    }

    @Test
    fun pythonNeverLoadedIsAlwaysACleanError() {
        // No module handle means no import RPC ran this run; stale-disk
        // recovery belongs to the entry barrier, not this caller.
        assertEquals(
            BotImport.FailureDisposition.CLEAN_ERROR,
            BotImport.failureDisposition(
                prepared = false, pythonLoaded = false, context = context))
    }

    @Test
    fun stagedAndPriorSnapshotsAlsoMarkTheImportPending() {
        artifact("IMPORT_PREFS.json").writeText("{}")
        try {
            assertTrue(BotImport.importPendingOnDisk(context))
            assertTrue(BotImport.recoveryPending(context))
        } finally {
            artifact("IMPORT_PREFS.json").delete()
        }
    }
}

/**
 * The concurrency gap, widened to the whole mutation lifecycle: the
 * activity's async recovery, the BotService worker's barrier AND a live
 * import/export transaction must never overlap - a service start during
 * an import must queue behind it, never "recover" a journal
 * mid-transaction. One process-wide lock (the manifest declares no
 * android:process) serializes every phase; these tests pin the
 * serialization, the phase visibility and the permission boundary.
 */
class MigrationLifecycleTest {
    @Test
    fun migrationLockSerializesConcurrentCallers() {
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = java.util.concurrent.CountDownLatch(1)
        val ran = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                BotImport.withMigrationLock(BotImport.MigrationPhase.IMPORTING) {
                    val now = inFlight.incrementAndGet()
                    maxSeen.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    gate.await()          // hold the lock until every caller queues
                    ran.incrementAndGet()
                    inFlight.decrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        Thread.sleep(100)         // let every caller queue on the lock
        gate.countDown()
        threads.forEach { it.join(5000) }
        assertEquals(4, ran.get())
        assertEquals(1, maxSeen.get())
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
    }

    @Test
    fun phaseIsVisibleWhileTheLifecycleIsHeld() {
        BotImport.withMigrationLock(BotImport.MigrationPhase.IMPORTING) {
            assertEquals(BotImport.MigrationPhase.IMPORTING, BotImport.migrationPhase)
            assertEquals("an import is still running", BotImport.migrationBlockReason())
            "done"
        }
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
        assertNull(BotImport.migrationBlockReason())
    }

    @Test
    fun recoveryPhaseIsVisibleDuringRecovery() {
        BotImport.withMigrationLock(BotImport.MigrationPhase.RECOVERING) {
            assertEquals("crash recovery is still running", BotImport.migrationBlockReason())
            "done"
        }
        assertNull(BotImport.migrationBlockReason())
    }

    @Test
    fun migrationLockReturnsTheBodyResult() {
        assertEquals("kept",
            BotImport.withMigrationLock(BotImport.MigrationPhase.IDLE) { "kept" })
    }
}

/**
 * Atomic admission and the service reservation - the actual entry
 * semantics of the whole-lifecycle fix: an import is admitted only when
 * the lifecycle is IDLE and no LIVE runtime marker exists (the marker
 * holds the runtime pid; a dead pid makes it stale, cleared on the
 * pid's grounding, never blindly honored). The service reservation is
 * claimed atomically and released only by its owner token.
 */
class ImportAdmissionTest {

    private fun tempDir(): File {
        val d = File.createTempFile("adm", "").let { it.delete(); it }
        d.mkdirs()
        return d
    }

    private fun liveMarker(dir: File): File =
        File(dir, "RUNNING").apply {
            writeText(ProcessHandle.current().pid().toString())
        }

    @Test
    fun admissionRefusedWhileRuntimeIsLive() {
        val dir = tempDir()
        val marker = liveMarker(dir)
        try {
            assertNull(BotImport.withImportAdmission(marker) { "ran" })
        } finally {
            marker.delete()
            dir.delete()
        }
    }

    @Test
    fun staleMarkerFromADeadProcessIsClearedAndAdmitted() {
        val dir = tempDir()
        val marker = File(dir, "RUNNING")
        marker.writeText("4194304")   // pid space max + 1: no live process
        try {
            val out = BotImport.withImportAdmission(marker) { "ran" }
            assertEquals("ran", out)
            assertFalse(marker.exists())
        } finally {
            marker.delete()
            dir.delete()
        }
    }

    @Test
    fun malformedMarkerFailsClosedAndIsKept() {
        // An unparseable marker cannot prove no runtime is alive: it
        // refuses admission and is NOT cleared (fail closed).
        val dir = tempDir()
        val marker = File(dir, "RUNNING")
        marker.writeText("not-a-pid")
        try {
            assertNull(BotImport.withImportAdmission(marker) { "ran" })
            assertTrue(marker.exists())
        } finally {
            marker.delete()
            dir.delete()
        }
    }

    @Test
    fun admissionRunsBodyAndReleasesWhenIdle() {
        val dir = tempDir()
        val marker = File(dir, "RUNNING")
        val out = BotImport.withImportAdmission(marker) {
            assertEquals(BotImport.MigrationPhase.IMPORTING,
                BotImport.migrationPhase)
            "ran"
        }
        assertEquals("ran", out)
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
        dir.delete()
    }

    @Test
    fun concurrentAdmissionIsRefusedNotOverlapped() {
        val dir = tempDir()
        val marker = File(dir, "RUNNING")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = Thread {
            BotImport.withImportAdmission(marker) {
                entered.countDown()
                release.await()
                "first"
            }
        }
        first.start()
        entered.await()          // first import holds the lifecycle
        assertEquals(BotImport.MigrationPhase.IMPORTING, BotImport.migrationPhase)
        // A second admission attempt must be refused, never overlap.
        assertNull(BotImport.withImportAdmission(marker) { "second" })
        release.countDown()
        first.join(5000)
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
        dir.delete()
    }
}

/**
 * The service reservation lifecycle: atomic claim, active-owner
 * rejection (never an overwrite), export transition, and owner-checked
 * release - a superseded worker's release can never clear a NEWER
 * owner's reservation. Robolectric provides the Context; with nothing
 * pending, recovery inside the reservation succeeds without Python.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ServiceReservationTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun reservationClaimsServiceAndBlocksAdmission() {
        val owner = Any()
        assertEquals(BotImport.ReserveResult.RESERVED,
            BotImport.recoverAndReserveService(context, owner))
        try {
            assertEquals(BotImport.MigrationPhase.SERVICE, BotImport.migrationPhase)
            assertEquals("the bot is starting or running",
                BotImport.migrationBlockReason())
            assertNull(BotImport.withImportAdmission(
                File(context.filesDir, "RUNNING")) { "ran" })
        } finally {
            BotImport.releaseServiceReservation(owner)
        }
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
    }

    @Test
    fun activeOwnerRejectsAndIsNeverOverwritten() {
        val first = Any()
        val second = Any()
        assertEquals(BotImport.ReserveResult.RESERVED,
            BotImport.recoverAndReserveService(context, first))
        // A second reservation while owned: rejected, first owner intact.
        assertEquals(BotImport.ReserveResult.LIFECYCLE_ACTIVE,
            BotImport.recoverAndReserveService(context, second))
        assertEquals(BotImport.MigrationPhase.SERVICE, BotImport.migrationPhase)
        // A superseded owner's release must NOT clear the reservation...
        BotImport.releaseServiceReservation(second)
        assertEquals(BotImport.MigrationPhase.SERVICE, BotImport.migrationPhase)
        // ...only the real owner's release does.
        BotImport.releaseServiceReservation(first)
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
    }

    @Test
    fun exportPhaseIsOwnerChecked() {
        val owner = Any()
        val other = Any()
        BotImport.markExportPhase(other)   // not the owner: no-op
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
        assertEquals(BotImport.ReserveResult.RESERVED,
            BotImport.recoverAndReserveService(context, owner))
        BotImport.markExportPhase(owner)
        assertEquals(BotImport.MigrationPhase.EXPORTING, BotImport.migrationPhase)
        assertEquals("an export is still running", BotImport.migrationBlockReason())
        BotImport.releaseServiceReservation(owner)
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
    }

    @Test
    fun activityRecoveryNeverStompsAnActiveOwner() {
        val owner = Any()
        assertEquals(BotImport.ReserveResult.RESERVED,
            BotImport.recoverAndReserveService(context, owner))
        // Nothing pending behind a live owner: recovery is a no-op CLEAN,
        // and the owner's phase is untouched.
        assertEquals(BotImport.RecoveryOutcome.CLEAN,
            BotImport.runRecoveryBlocking(context))
        assertEquals(BotImport.MigrationPhase.SERVICE, BotImport.migrationPhase)
        BotImport.releaseServiceReservation(owner)
    }

    @Test
    fun pendingArtifactBehindActiveOwnerDefersNeverFails() {
        // A leftover export artifact while an owner is live: DEFERRED,
        // not FAILED - the gate must retry after the owner finishes,
        // never lock forever (activity recreated mid-export).
        File(context.filesDir, "EXPORT_PREFS.json").writeText("{}")
        try {
            val owner = Any()
            assertEquals(BotImport.ReserveResult.RESERVED,
                BotImport.recoverAndReserveService(context, owner))
            assertEquals(BotImport.RecoveryOutcome.DEFERRED,
                BotImport.runRecoveryBlocking(context))
            // DEFERRED is not a failure: the fail-closed gate state is
            // untouched, and the owner still holds its phase.
            assertFalse(BotImport.isRecoveryComplete())
            assertEquals(BotImport.MigrationPhase.SERVICE,
                BotImport.migrationPhase)
            BotImport.releaseServiceReservation(owner)
        } finally {
            File(context.filesDir, "EXPORT_PREFS.json").delete()
        }
    }

    @Test
    fun deferredRecoveryFiresOnceWhenOwnerReleases() {
        val owner = Any()
        assertEquals(BotImport.ReserveResult.RESERVED,
            BotImport.recoverAndReserveService(context, owner))
        val fired = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        BotImport.deferRecoveryUntilIdle {
            fired.incrementAndGet()
            latch.countDown()
        }
        BotImport.releaseServiceReservation(owner)
        assertTrue("deferred recovery never fired after the owner released",
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // One-shot slot: no busy loop, no second fire on later idles.
        BotImport.releaseServiceReservation(owner)
        Thread.sleep(200)
        assertEquals(1, fired.get())
    }

    @Test
    fun deferredRegistrationWhileIdleFiresImmediately() {
        // The owner finished between the DEFERRED outcome and the
        // registration: fire now, never wait for a transition that
        // already happened.
        val latch = java.util.concurrent.CountDownLatch(1)
        BotImport.deferRecoveryUntilIdle { latch.countDown() }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}
