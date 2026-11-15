package com.instinct.widget.bot

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cross-instance restart ordering - the stop/restart liveness bug the
 * lifecycle review found: a NEW service instance must not erase the
 * STOP flag while the PREVIOUS instance's worker is still tearing down
 * (its runtime has not observed the stop request yet). The process-wide
 * worker registry lets the new worker join the old thread; STOP is
 * cleared only after the old worker is dead AND this start holds the
 * lifecycle reservation, immediately before the runtime opens.
 *
 * The runtime itself never starts here: Python is absent under
 * Robolectric, the worker's catch records that, the finally releases.
 * What is asserted is the ordering around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BotServiceRestartTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        BotService.setActiveWorkerForTesting(null)
        File(context.filesDir, "STOP").delete()
        File(context.filesDir, "bot.log").delete()
    }

    @Test
    fun newInstancePreservesStopUntilOldWorkerDiesAndReservationHeld() {
        // A "previous instance's" worker, still tearing down.
        val oldMayFinish = CountDownLatch(1)
        val oldWorker = Thread({
            try {
                oldMayFinish.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "bot-old-instance")
        oldWorker.start()
        BotService.setActiveWorkerForTesting(oldWorker)

        // onDestroy of the old instance wrote the stop request.
        val stop = File(context.filesDir, "STOP")
        stop.writeText("")

        // The NEW instance receives the sticky restart.
        val service = Robolectric.buildService(BotService::class.java).create().get()
        service.onStartCommand(Intent(context, BotService::class.java), 0, 1)

        // While the old worker lives: STOP must survive (the old runtime
        // may not have observed it), and no reservation may be claimed.
        val newWorker = BotService.getActiveWorkerForTesting()
        assertNotNull(newWorker)
        Thread.sleep(500)
        assertTrue("STOP erased while the old worker was still alive",
            stop.exists())
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)

        // Let the old worker finish; the new worker must then join it,
        // reserve, and only then clear STOP (Python itself is absent
        // under Robolectric - the ordering around it is what matters).
        oldMayFinish.countDown()
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (!stop.exists()
                && BotImport.migrationPhase == BotImport.MigrationPhase.IDLE) break
            Thread.sleep(50)
        }
        newWorker!!.join(15000)
        assertTrue("STOP never cleared after a safe start", !stop.exists())
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
        assertNull(BotService.getActiveWorkerForTesting())
    }

    @Test
    fun startWhileOldWorkerStuckHandsTheSlotBack() {
        // A dying worker that never finishes inside the join window: the
        // new start refuses, STOP survives, and the process-wide slot is
        // handed back to the ACTUAL old thread so the next sticky retry
        // joins it again (ownership is never transferred to a dead
        // would-be starter).
        val oldWorker = Thread({
            try {
                Thread.sleep(60000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "bot-stuck-instance")
        oldWorker.start()
        BotService.setActiveWorkerForTesting(oldWorker)

        val stop = File(context.filesDir, "STOP")
        stop.writeText("")

        val service = Robolectric.buildService(BotService::class.java).create().get()
        service.onStartCommand(Intent(context, BotService::class.java), 0, 1)

        // The new worker joins with a 15s timeout; waiting it out is too
        // slow for a unit test, so interrupt the old worker AFTER the
        // refusal path is the slow one. Instead: wait for the refusal
        // (slot handed back) bounded by the join timeout.
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            if (BotService.getActiveWorkerForTesting() === oldWorker) break
            Thread.sleep(50)
        }
        assertTrue("slot not handed back to the stuck old worker",
            BotService.getActiveWorkerForTesting() === oldWorker)
        assertTrue("STOP erased by a refused start", stop.exists())
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)

        oldWorker.interrupt()
        oldWorker.join(5000)
    }

    @Test
    fun runningWorkerWithoutStopDedupesStarts() {
        // A live RUNNER with NO teardown pending: a start command is a
        // plain no-op - no joiner, no registry change, no STOP written.
        val runner = Thread({
            try {
                Thread.sleep(60000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "bot-running-instance")
        runner.start()
        BotService.setActiveWorkerForTesting(runner)

        val service = Robolectric.buildService(BotService::class.java).create().get()
        service.onStartCommand(Intent(context, BotService::class.java), 0, 1)
        assertTrue("registry changed under a running worker",
            BotService.getActiveWorkerForTesting() === runner)
        assertTrue("STOP written by a deduped start",
            !File(context.filesDir, "STOP").exists())
        assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)

        runner.interrupt()
        runner.join(5000)
    }

    @Test
    fun stopPlusRunnerAdmitsExactlyOneJoiner() {
        // The v5.11 regression shape, through the REAL onStartCommand
        // guard: STOP present (previous instance's onDestroy) + its
        // RUNNER still alive. The first start MUST spawn a joiner -
        // without it nobody starts after the old worker exits, because
        // Android does not re-invoke onStartCommand when a worker thread
        // merely ends. Repeats then dedupe on the joiner.
        val oldWorker = Thread({
            try {
                Thread.sleep(60000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "bot-old-instance")
        oldWorker.start()
        BotService.setActiveWorkerForTesting(oldWorker)
        File(context.filesDir, "STOP").writeText("")

        val s1 = Robolectric.buildService(BotService::class.java).create().get()
        s1.onStartCommand(Intent(context, BotService::class.java), 0, 1)
        val joiner = BotService.getActiveWorkerForTesting()
        assertNotNull("no joiner spawned for STOP + live runner", joiner)
        assertTrue(joiner !== oldWorker)

        val s2 = Robolectric.buildService(BotService::class.java).create().get()
        s2.onStartCommand(Intent(context, BotService::class.java), 0, 2)
        assertTrue("second joiner spawned",
            BotService.getActiveWorkerForTesting() === joiner)

        oldWorker.interrupt()
        oldWorker.join(5000)
        joiner!!.join(15000)
    }

    @Test
    fun rapidRepeatedStartsSpawnNoSecondJoiner() {
        // STOP exists and the first joiner is still waiting on a stuck
        // old worker: repeated start commands (rapid taps, boot receiver
        // + sticky together) must be deduped atomically - no second
        // joiner, no registry overwrite, so no loser can later stopSelf
        // the winner's service or erase STOP.
        val oldWorker = Thread({
            try {
                Thread.sleep(60000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "bot-stuck-instance")
        oldWorker.start()
        BotService.setActiveWorkerForTesting(oldWorker)

        val stop = File(context.filesDir, "STOP")
        stop.writeText("")

        val s1 = Robolectric.buildService(BotService::class.java).create().get()
        s1.onStartCommand(Intent(context, BotService::class.java), 0, 1)
        val firstJoiner = BotService.getActiveWorkerForTesting()
        assertNotNull(firstJoiner)
        assertTrue(firstJoiner !== oldWorker)

        // Rapid repeats, each a fresh service instance (cross-instance).
        for (i in 2..4) {
            val sN = Robolectric.buildService(BotService::class.java).create().get()
            sN.onStartCommand(Intent(context, BotService::class.java), 0, i)
            assertTrue("registry overwritten by a repeated start",
                BotService.getActiveWorkerForTesting() === firstJoiner)
            assertTrue("STOP erased by a repeated start", stop.exists())
            assertEquals(BotImport.MigrationPhase.IDLE, BotImport.migrationPhase)
        }

        oldWorker.interrupt()
        oldWorker.join(5000)
        firstJoiner!!.join(15000)
    }
}
