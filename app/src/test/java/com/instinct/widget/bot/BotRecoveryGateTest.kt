package com.instinct.widget.bot

import android.app.Activity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the recovery-gate publication race the independent
 * review found: runRecoveryBlocking releases the lifecycle phase BEFORE
 * the worker publishes the gate, so without a generation guard a
 * superseded worker's late CLEAN or FAILED overwrites a newer recovery's
 * truthful state - in either direction. The fix serializes the pending
 * check, the PENDING reset and every publication under gateLock, and
 * tags each launched worker with a generation: only the LATEST
 * in-process recovery may publish.
 *
 * The publication window is held open deterministically with
 * recoveryOutcomeHook (the seam between phase release and publication);
 * recoveryBodyOverride controls each worker's outcome without Python.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BotRecoveryGateTest {

    private lateinit var activity: Activity
    private val serviceOwner = Any()

    @Before
    fun setUp() {
        // Route android.util.Log (the recovery body's failure logging)
        // to the test output: a red run must SHOW the caught exception,
        // not just its FAILED outcome.
        org.robolectric.shadows.ShadowLog.stream = System.out
        activity = Robolectric.buildActivity(Activity::class.java).get()
        BotImport.resetRecoveryGateForTest()
        BotImport.resetExportSetupForTest()
    }

    @After
    fun tearDown() {
        BotImport.recoveryBodyOverride = null
        BotImport.recoveryOutcomeHook = null
        BotImport.recoveryModuleForTest = null
        BotImport.releaseServiceReservation(serviceOwner)
        BotImport.resetExportSetupForTest()
        clearPending()
        // Leave the static gate as the run found it (PENDING), not DONE:
        // Robolectric reuses the sandbox across test CLASSES, so a DONE
        // left here contaminates later classes whose assertions assume
        // the virgin gate (ServiceReservationTest).
        BotImport.resetRecoveryGateForTest(BotImport.RecoveryState.PENDING)
    }

    /** Drives the REAL recoveryBody branch under Robolectric (no
     * Python): the recover_export admission and skip logic under test
     * is the production code, only the module is fake. A successful
     * import recovery leaves no journal behind. */
    private class FakeRecoveryModule(
        private val importResult: String = "none",
        private val onRecoverImport: () -> Unit = {},
        private val onRecoverExport: () -> Unit = {},
    ) : RecoveryModule {
        val importCalls = AtomicInteger()
        val exportCalls = AtomicInteger()
        override fun recoverImport(nativeLib: String?, files: String): String {
            importCalls.incrementAndGet()
            File(files, "IMPORT_PENDING.json").delete()
            onRecoverImport()
            return importResult
        }
        override fun recoverExport(files: String) {
            exportCalls.incrementAndGet()
            onRecoverExport()
        }
    }

    private fun pendingFile() = File(activity.filesDir, "IMPORT_PENDING.json")

    private fun markPending() {
        pendingFile().writeText("{}")
    }

    private fun clearPending() {
        pendingFile().delete()
        File(activity.filesDir, "EXPORT_PREFS.json").delete()
        File(activity.filesDir, "EXPORT_RESULT").delete()
        File(activity.filesDir, "export-out").deleteRecursively()
    }

    private fun state(): BotImport.RecoveryState = BotImport.recoveryGateStateForTest()

    private fun awaitState(expect: BotImport.RecoveryState, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state() == expect) return
            Thread.sleep(10)
        }
        fail("gate did not become $expect, still ${state()}")
    }

    private fun settleAndAssert(expect: BotImport.RecoveryState) {
        Thread.sleep(250)
        assertEquals(expect, state())
    }

    /** The reviewer's core scenario, FAILED direction: an older worker's
     * late FAILED must not overwrite a NEWER recovery's DONE. The hook
     * holds worker A exactly at the publication window until worker B
     * (a later generation) has already published. */
    @Test
    fun staleLateFailedPublicationIsDiscarded() {
        markPending()
        val bodies = AtomicInteger(0)
        BotImport.recoveryBodyOverride = {
            // First recovery FAILS (its journal is still on disk); the
            // retried recovery succeeds.
            bodies.incrementAndGet() != 1
        }
        val firstAtPublication = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstPublished = CountDownLatch(1)
        BotImport.recoveryOutcomeHook = { outcome ->
            if (outcome == BotImport.RecoveryOutcome.FAILED) {
                firstAtPublication.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                firstPublished.countDown()
            }
        }

        BotImport.recoverInterruptedImport(activity)
        assertTrue("worker A must reach the publication window",
            firstAtPublication.await(5, TimeUnit.SECONDS))
        // A is parked between phase release and publication; a NEWER
        // launch resets PENDING and runs to DONE first.
        BotImport.recoverInterruptedImport(activity)
        awaitState(BotImport.RecoveryState.DONE)

        releaseFirst.countDown()
        assertTrue(firstPublished.await(5, TimeUnit.SECONDS))
        // A's stale FAILED publication is discarded: the gate stays DONE.
        settleAndAssert(BotImport.RecoveryState.DONE)
    }

    /** The stale-DONE defect itself, CLEAN direction: an older worker's
     * late DONE must not overwrite a NEWER recovery's FAILED - that is
     * the "ready too early" lie the plan keeps open. */
    @Test
    fun staleLateCleanPublicationDoesNotOverwriteFailed() {
        markPending()
        val bodies = AtomicInteger(0)
        BotImport.recoveryBodyOverride = {
            bodies.incrementAndGet() == 1
        }
        val firstAtPublication = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstPublished = CountDownLatch(1)
        BotImport.recoveryOutcomeHook = { outcome ->
            if (outcome == BotImport.RecoveryOutcome.CLEAN) {
                firstAtPublication.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                firstPublished.countDown()
            }
        }

        BotImport.recoverInterruptedImport(activity)
        assertTrue(firstAtPublication.await(5, TimeUnit.SECONDS))
        BotImport.recoverInterruptedImport(activity)
        awaitState(BotImport.RecoveryState.FAILED)

        releaseFirst.countDown()
        assertTrue(firstPublished.await(5, TimeUnit.SECONDS))
        // A's stale DONE publication is discarded: the gate stays FAILED.
        settleAndAssert(BotImport.RecoveryState.FAILED)
    }

    /** DEFERRED behavior: behind a live service owner the gate stays
     * PENDING (never a premature DONE, never a bogus FAILED) and the
     * deferred retry - a NEW generation - publishes DONE once the owner
     * releases. */
    @Test
    fun deferredRecoveryKeepsGatePendingUntilOwnerFinishes() {
        BotImport.recoveryBodyOverride = { true }
        assertEquals(BotImport.ReserveResult.RESERVED,
            BotImport.recoverAndReserveService(activity, serviceOwner))
        markPending()
        BotImport.resetRecoveryGateForTest()

        BotImport.recoverInterruptedImport(activity)
        // The reset is synchronous (serialized under gateLock): PENDING
        // before the worker even starts, and DEFERRED publishes nothing.
        settleAndAssert(BotImport.RecoveryState.PENDING)

        BotImport.releaseServiceReservation(serviceOwner)
        awaitState(BotImport.RecoveryState.DONE)
    }

    /** Pending-check serialization: the PENDING reset lands before
     * recoverInterruptedImport returns, so no reader can see a stale
     * DONE in between; a clean disk takes the fast path to DONE. */
    @Test
    fun pendingResetIsSynchronousAndCleanDiskTakesFastPath() {
        BotImport.recoveryBodyOverride = { true }
        markPending()
        BotImport.resetRecoveryGateForTest()
        BotImport.recoverInterruptedImport(activity)
        assertEquals(BotImport.RecoveryState.PENDING, state())
        awaitState(BotImport.RecoveryState.DONE)

        clearPending()
        BotImport.resetRecoveryGateForTest(BotImport.RecoveryState.PENDING)
        BotImport.recoverInterruptedImport(activity)
        assertEquals(BotImport.RecoveryState.DONE, state())
    }

    /** The custodian's second gap: recoveryPending can become FALSE
     * while a worker is still between phase release and publication
     * (its own cleanup deleted the journal, then a later step failed).
     * A clean fast path in that window is the NEWEST authority and must
     * claim a generation, or the worker's late FAILED overwrites it. */
    @Test
    fun cleanFastPathSupersedesParkedFailedWorker() {
        markPending()
        BotImport.recoveryBodyOverride = {
            // Partial cleanup then failure: the journal is GONE (a
            // concurrent pending check now reads false) yet the outcome
            // is FAILED.
            clearPending()
            false
        }
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        BotImport.recoveryOutcomeHook = { outcome ->
            if (outcome == BotImport.RecoveryOutcome.FAILED) {
                parked.countDown()
                release.await(5, TimeUnit.SECONDS)
                published.countDown()
            }
        }

        BotImport.recoverInterruptedImport(activity)
        assertTrue("worker must reach the publication window",
            parked.await(5, TimeUnit.SECONDS))
        // Disk is clean while the FAILED worker is parked: the fast
        // path claims DONE synchronously ...
        BotImport.recoverInterruptedImport(activity)
        assertEquals(BotImport.RecoveryState.DONE, state())
        // ... and the stale FAILED publication is discarded.
        release.countDown()
        assertTrue(published.await(5, TimeUnit.SECONDS))
        settleAndAssert(BotImport.RecoveryState.DONE)
    }

    /** The custodian's first gap, reworked to drive the REAL branch:
     * the UI writes EXPORT_PREFS.json BEFORE the service claims the
     * lifecycle phase. While the setup owner token is held, those
     * artifacts belong to the LIVE flow: they are not pending recovery
     * work, the gate may say DONE, and the snapshot must survive.
     * Releasing the owner hands the leftovers back to recovery - and
     * the real body branch then runs recover_export for them. */
    @Test
    fun exportSetupOwnershipShieldsLiveSnapshotFromRecovery() {
        val exportPrefs = File(activity.filesDir, "EXPORT_PREFS.json")
        val fake = FakeRecoveryModule()
        BotImport.recoveryModuleForTest = fake
        val owner = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner))
        exportPrefs.writeText("{}")

        // Live setup window: snapshot on disk, phase IDLE, owner held.
        assertFalse("a live setup's artifacts are not recovery work",
            BotImport.recoveryPending(activity))
        BotImport.resetRecoveryGateForTest(BotImport.RecoveryState.PENDING)
        BotImport.recoverInterruptedImport(activity)
        assertEquals(BotImport.RecoveryState.DONE, state())
        assertTrue("the live snapshot must survive recovery decisions",
            exportPrefs.exists())

        // Abandoning the setup hands the leftovers back to recovery:
        // the REAL body branch admits and runs recover_export for them.
        BotImport.releaseExportSetup(owner)
        assertTrue(BotImport.recoveryPending(activity))
        BotImport.resetRecoveryGateForTest()
        BotImport.recoverInterruptedImport(activity)
        awaitState(BotImport.RecoveryState.DONE)
        assertEquals(1, fake.exportCalls.get())
    }

    /** The custodian's race: a worker already PAST its pending check is
     * parked INSIDE recover_export (via the module seam, so the real
     * admission code runs). A setup claim in that window must be
     * REFUSED - under v5.23's bare CAS it succeeded and the parked
     * worker then deleted the live snapshot. Once recovery finishes,
     * the same claim goes through. */
    @Test
    fun recoverExportInFlightBlocksSetupClaim() {
        markPending()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeRecoveryModule(onRecoverExport = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Unit
        })
        BotImport.recoveryModuleForTest = fake

        BotImport.recoverInterruptedImport(activity)
        assertTrue("worker must be inside recover_export",
            entered.await(5, TimeUnit.SECONDS))
        assertEquals(BotImport.ExportSetupClaim.RECOVERY_IN_FLIGHT,
            BotImport.claimExportSetup(activity, Any()))
        assertFalse("a refused claim never registers an owner",
            BotImport.exportSetupInFlight())

        release.countDown()
        awaitState(BotImport.RecoveryState.DONE)
        val owner = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner))
        BotImport.releaseExportSetup(owner)
    }

    /** The other interlock direction through the REAL branch: with the
     * setup claimed first, a body that has genuine import-side work
     * (journal present) still runs recover_import but SKIPS
     * recover_export - the live setup owns those artifacts. */
    @Test
    fun setupClaimMakesRealBranchSkipRecoverExport() {
        val owner = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner))
        markPending()
        val fake = FakeRecoveryModule()
        BotImport.recoveryModuleForTest = fake

        BotImport.recoverInterruptedImport(activity)
        awaitState(BotImport.RecoveryState.DONE)
        assertEquals(1, fake.importCalls.get())
        assertEquals("recover_export must be skipped while a live setup owns the artifacts",
            0, fake.exportCalls.get())
    }

    /** The claim is the gate's authority at setup time (the tap-time
     * recoveryPending() check in the UI is stale by then): it refuses
     * while interrupted artifacts await recovery (and kicks no owner),
     * and fails CLOSED while the gate is FAILED. */
    @Test
    fun setupClaimRefusedWhileRecoveryPendingOrFailed() {
        File(activity.filesDir, "EXPORT_PREFS.json").writeText("{}")
        assertEquals(BotImport.ExportSetupClaim.RECOVERY_PENDING,
            BotImport.claimExportSetup(activity, Any()))
        assertFalse(BotImport.exportSetupInFlight())

        clearPending()
        BotImport.resetRecoveryGateForTest(BotImport.RecoveryState.FAILED)
        assertEquals(BotImport.ExportSetupClaim.RECOVERY_FAILED,
            BotImport.claimExportSetup(activity, Any()))
        assertFalse(BotImport.exportSetupInFlight())
    }

    /** The custodian's pending-gate finding: the worker has ALREADY
     * removed the journal (disk reads clean) but is still inside the
     * import leg (its settings restore has not completed) and the gate
     * is PENDING. A claim keying on disk alone would succeed here and
     * its snapshot would race the restore; keying on the DONE gate
     * refuses it. Once the worker finishes, the claim goes through. */
    @Test
    fun setupClaimRefusedWhileWorkerRestoringWithCleanDisk() {
        markPending()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The journal is deleted BEFORE the hook: when the latch opens,
        // disk is clean, the gate is PENDING and the worker is parked.
        val fake = FakeRecoveryModule(onRecoverImport = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Unit
        })
        BotImport.recoveryModuleForTest = fake

        BotImport.recoverInterruptedImport(activity)
        assertTrue("worker must be parked mid-recovery with a clean disk",
            entered.await(5, TimeUnit.SECONDS))
        assertFalse("journal already removed by the worker",
            BotImport.recoveryPending(activity))
        assertEquals(BotImport.ExportSetupClaim.RECOVERY_PENDING,
            BotImport.claimExportSetup(activity, Any()))
        assertFalse("a refused claim never registers an owner",
            BotImport.exportSetupInFlight())

        release.countDown()
        awaitState(BotImport.RecoveryState.DONE)
        val owner = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner))
        BotImport.releaseExportSetup(owner)
    }

    /** The custodian's ownership finding: only the exact setup token
     * spends the claim - a rejected/stale service start with a wrong or
     * missing token can never unshield another flow's live snapshot. */
    @Test
    fun exportSetupSpendRequiresTheSameToken() {
        val owner = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner))
        assertEquals(BotImport.ExportSetupClaim.ALREADY_CLAIMED,
            BotImport.claimExportSetup(activity, Any()))

        BotImport.clearExportSetup(Any())
        assertTrue("a wrong token must not clear the claim",
            BotImport.exportSetupInFlight())
        BotImport.clearExportSetup(null)
        assertTrue("a missing handoff must not clear the claim",
            BotImport.exportSetupInFlight())

        val handed = BotImport.takeExportSetupHandoff()
        assertTrue("the service receives the claiming flow's own token",
            handed === owner)
        BotImport.clearExportSetup(handed)
        assertFalse(BotImport.exportSetupInFlight())
        assertEquals("a new flow may claim once the old one is spent",
            BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, Any()))
    }

    /** The custodian's terminal-path finding: every abandon route
     * (service early return after consuming the handoff, UI
     * timeout/abort) spends exactly its own token, so no route can
     * leave a stale owner/handoff wedging future claims as
     * ALREADY_CLAIMED or shielding truly abandoned artifacts from
     * recovery. The BotService/BotActivity call-site wiring itself is
     * compile- and source-reviewed only: Robolectric cannot drive the
     * service worker join logic. */
    @Test
    fun terminalCleanupFreesTheWindowForANewClaim() {
        // Service route: handoff consumed up front, then an early
        // return (dedupe / join timeout / reservation reject) spends it.
        val owner = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner))
        assertTrue("the service takes its own flow's token",
            BotImport.takeExportSetupHandoff() === owner)
        assertTrue("a second take finds the handoff already consumed",
            BotImport.takeExportSetupHandoff() == null)
        BotImport.clearExportSetup(owner)
        assertFalse(BotImport.exportSetupInFlight())

        // UI route: timeout/abort after the handoff was consumed
        // releases the owner with the same token; stale/null tokens
        // are no-ops.
        val owner2 = Any()
        assertEquals(BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, owner2))
        BotImport.takeExportSetupHandoff()
        BotImport.releaseExportSetup(null)
        assertTrue("a null token must not clear a live claim",
            BotImport.exportSetupInFlight())
        BotImport.releaseExportSetup(owner2)
        assertFalse(BotImport.exportSetupInFlight())
        assertEquals("the window is free for the next flow",
            BotImport.ExportSetupClaim.CLAIMED,
            BotImport.claimExportSetup(activity, Any()))
    }
}
