package com.vanipy.migtest

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.instinct.widget.bot.BotActivity
import com.instinct.widget.bot.BotService
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EMULATOR QUALIFICATION - post-import launch (renamed identity).
 *
 * Runs AFTER MigrationEmuImportTest in the round-trip: the imported
 * fixture account exists in this install. Starts the REAL live bot
 * service over the imported account with the network fail-closed
 * (harness-level isolation).
 *
 * BOUNDED CLAIM - exactly this and no more: with the imported fixture
 * account and fail-closed egress, the live runtime (a) completes boot
 * and setup, (b) does not exit prematurely over a 30s window, and (c)
 * stops cleanly and non-overlappingly on the STOP flag. This leg does
 * NOT claim ongoing event-loop progress: no heartbeat or event counter
 * is sampled during the window.
 *
 * Evidence, all from production behavior, nothing added for the test:
 * BOOT/SETUP: bot_runtime's own log.info("live bot running as ...")
 * (bot_runtime.py:996) is written only after rpc open, account lookup,
 * start_io, watcher/hook setup and boot_runtimes. The test takes a
 * pre-start log offset and requires a FRESH beacon beyond it - a stale
 * line from an earlier run cannot satisfy READY.
 * LIVENESS: the RUNNING lock's pid must equal this process (in-process
 * python), and EXACTLY ONE child process whose argv[0] is exactly
 * <nativeLibraryDir>/librpcsrv.so must exist while live. That is the
 * DeltaChat RPC server: deltachat_rpc_client.Rpc spawns
 * subprocess.Popen(rpc_server_path) with a single-argument cmdline, so
 * the identity is exact, not a substring heuristic.
 * GRACEFUL network failure: fresh ready beacon + exactly-one RPC child
 * + lock persistence through the window + state intact; start_io under
 * fail-closed egress retries internally instead of throwing.
 * CLEAN, NON-OVERLAPPING SHUTDOWN: after the STOP flag the test
 * requires ALL of: (a) a FRESH "STOP flag seen" watchdog line beyond
 * the stop-time offset (the watchdog actually processed the flag -
 * asserted, not just recorded), (b) the lock removed, and (c) the
 * exact librpcsrv.so child drained to zero - verified before the
 * account recheck opens the DB again.
 * CRASH: boot/ready timeout, lock vanishing without STOP (the runtime
 * finally removes it on ANY exit), process death (fails the
 * instrumentation itself), stop not completing, or a lingering RPC
 * child after the lock is gone. bot.log "Traceback" lines are REPORTED
 * but not gated: offline non-fatal paths log them in healthy runs.
 *
 * Child inspection never scans all of /proc: it enumerates ONLY this
 * process's own children via /proc/self/task/<tid>/children (every
 * thread's list unioned - Popen forks from the BotService worker
 * thread, not necessarily the main thread), then reads each
 * candidate's cmdline. Unrelated processes are never inspected and
 * cannot affect the gate. An unreadable task list, children file or
 * candidate cmdline makes the scan UNAVAILABLE (distinct from zero
 * children) and its gate FAIL after bounded retries. If the kernel
 * denies that own-child interface, this leg reports the concrete
 * capability limitation by failing closed - never by broadening to
 * all of /proc or weakening to empty.
 *
 * Failure-path teardown is unconditional, but its VERDICT is honest
 * about what could be observed. On EVERY exit path the test writes
 * STOP, clears persisted last_mode (an explicit live start persists
 * last_mode="live" in BotService:62-68, so START_STICKY could
 * otherwise resume live into a later leg) and calls stopService.
 * Worker termination is read through BotService's existing
 * getActiveWorkerForTesting() accessor via reflection (the test lives
 * in another package); an unavailable accessor fails closed.
 * Two cases, distinguished because a null accessor is ambiguous (it
 * means "worker died" but also "the async start callback never
 * installed one yet"):
 * - Worker OBSERVED live earlier (every path that passed the ready
 *   gate): null-or-dead now is genuine termination; teardown then
 *   rechecks lock absence and child drain AFTER that death, so a
 *   pending worker cannot invalidate the instant of verification.
 * - Worker NEVER observed (e.g. boot timeout before the runtime
 *   wrote its lock): teardown waits a bounded grace window for a
 *   delayed start callback to surface a worker and, if one appears,
 *   awaits its termination. If none appears, cleanup is declared
 *   UNPROVEN - a still-queued start cannot be ruled out - and the leg
 *   FAILS. Containment is then at job level, by runner design:
 *   postlaunch is the final leg, any leg failure exits the runner
 *   immediately, the capture trap performs only allowlisted file
 *   copies (no DB access), and the emulator is ephemeral.
 * A teardown that cannot be verified FAILS the leg (added as
 * suppressed when the body already failed).
 *
 * Evidence stays allowlisted: self-authored phase names, booleans and
 * counts only; bot.log and runtime output never leave the emulator.
 */
@RunWith(AndroidJUnit4::class)
class MigrationEmuPostImportLaunchTest {

    private val ADDR = "fixture-bot@emu.fixture"
    private val BOOT_BUDGET_MS = 60_000L
    private val READY_BUDGET_MS = 60_000L
    private val OBSERVE_WINDOW_MS = 30_000L
    private val STOP_BUDGET_MS = 30_000L
    private val CHILD_DRAIN_BUDGET_MS = 15_000L
    private val START_GRACE_MS = 10_000L
    private val READY_BEACON = "live bot running as"
    private val WATCHDOG_BEACON = "STOP flag seen"

    @Test
    fun liveServiceBootsStopsOverImportedAccount() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val files = ctx.filesDir
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val filesPath = files.absolutePath
        // The DeltaChat RPC server's exact process identity: the client
        // spawns Popen(rpc_server_path) with no extra argv.
        val rpcServerPath = "$nativeLib/librpcsrv.so"
        // Do NOT wipe emu-out: the import leg's artifacts live there.
        val outDir = File(files, "emu-out").apply { mkdirs() }
        val running = File(files, "RUNNING")
        val stopFlag = File(files, "STOP")
        val logFile = File(files, "bot.log")

        val report = JSONObject()
        var phase = "precondition"
        var scenario: ActivityScenario<BotActivity>? = null
        var primary: Throwable? = null
        var workerObservedLive = false
        // Fixed vocabulary only: never put Throwable messages, addresses,
        // paths, PIDs or app data onto the instrumentation status channel.
        fun diagnostic(event: String) {
            try {
                InstrumentationRegistry.getInstrumentation().sendStatus(100,
                    android.os.Bundle().apply { putString("emu_postlaunch", event) })
            } catch (_: Throwable) {
                // A missing beacon never changes an assertion or outcome.
            }
        }
        fun gate(name: String) { diagnostic("gate_" + name) }
        try {
            gate("precondition")
            // ---- 0. imported state present, no runtime active ----
            val st = JSONObject(File(files, "bot_state.json").readText())
            report.put("stateProvisioned", st.optBoolean("provisioned"))
            report.put("stateAddrMatches", st.optString("bot_addr") == ADDR)
            assertTrue("bot_state.json not provisioned (import leg missing?)",
                st.optBoolean("provisioned"))
            assertEquals("imported account address", ADDR, st.optString("bot_addr"))
            report.put("staleRunningAbsentAtEntry", !running.exists())
            assertFalse("RUNNING marker present before start", running.exists())
            // A stale STOP would race the watchdog; the runtime unlinks it
            // at entry anyway - remove defensively and record.
            report.put("staleStopCleared", stopFlag.delete() || !stopFlag.exists())

            gate("fixture_setup")
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
            val py = Python.getInstance()
            val fixture = py.getModule("emu_fixture")
            fixture.callAttr("install_bounded_rpc_startup_diagnostics")

            // ---- 1. start the REAL live service (same intent the Start
            // bot button sends), app foreground via BotActivity so the
            // foreground-service start is allowed ----
            phase = "live-start"
            // Freshness offset: the boot beacon must be written AFTER
            // this point - bot.log persists across runs.
            val bootLogMark = if (logFile.isFile) logFile.length() else 0L
            gate("service_start")
            scenario = ActivityScenario.launch(BotActivity::class.java)
            ctx.startService(
                Intent(ctx, BotService::class.java).putExtra("mode", "live"))

            // ---- 2. boot: the runtime writes RUNNING itself; the lock
            // pid must be this very process (in-process python) ----
            phase = "boot-wait"
            gate("lock")
            val booted = waitFor(BOOT_BUDGET_MS) { running.exists() }
            report.put("runningAppeared", booted)
            assertTrue("runtime lock absent after ${BOOT_BUDGET_MS}ms - boot failed", booted)
            val lockPid = running.readText().trim().toLongOrNull()
            report.put("lockPidIsThisProcess", lockPid != null &&
                lockPid.toInt() == Process.myPid())
            assertTrue("RUNNING lock pid $lockPid is not this process " +
                Process.myPid(), lockPid != null && lockPid.toInt() == Process.myPid())

            // ---- 3. READY: a FRESH production boot-complete beacon
            // beyond the pre-start offset - lock alone could be a stuck
            // boot before rpc open, account lookup, start_io and setup,
            // and a stale beacon from an earlier run proves nothing ----
            phase = "ready-wait"
            gate("ready")
            val ready = waitFor(READY_BUDGET_MS) {
                logContainsBeyond(logFile, bootLogMark, READY_BEACON)
            }
            report.put("readyBeaconSeen", ready)
            assertTrue("fresh boot-complete beacon absent after " +
                "${READY_BUDGET_MS}ms - lock without completed setup", ready)

            // The live BotService worker thread must be observable:
            // seeing it non-null here is what makes a null accessor
            // during teardown mean "terminated" rather than "the async
            // start callback never landed".
            gate("worker_accessor")
            val liveWorker = activeWorker()
            gate("worker_live")
            report.put("workerObservedLive", liveWorker != null && liveWorker.isAlive)
            assertTrue("no live BotService worker observable while RUNNING " +
                "- the start lifecycle is untracked, so teardown nulls would " +
                "be ambiguous", liveWorker != null && liveWorker.isAlive)
            workerObservedLive = true

            // The DeltaChat RPC server is exactly one child of this
            // process with argv[0] == <nativeLibraryDir>/librpcsrv.so.
            gate("rpc_live_inspection")
            val liveScan = settledRpcChildScan(rpcServerPath)
            report.put("rpcChildScanUnavailable", !liveScan.complete)
            report.put("rpcChildCountLive", liveScan.children.size)
            gate("rpc_live_available")
            assertTrue("rpc child scan unavailable after retries - " +
                "inspection cannot be trusted, failing closed", liveScan.complete)
            gate("rpc_live_count")
            assertEquals("expected exactly one librpcsrv.so child of this " +
                "process while live, found " + liveScan.children.size,
                1, liveScan.children.size)

            // ---- 4. observation window: sample for premature exit only.
            // This does not measure event-loop progress. ----
            phase = "observe"
            gate("observe")
            val t0 = System.currentTimeMillis()
            var vanishedWithoutStop = false
            while (System.currentTimeMillis() - t0 < OBSERVE_WINDOW_MS) {
                Thread.sleep(2000)
                if (!running.exists()) { vanishedWithoutStop = true; break }
            }
            report.put("vanishedWithoutStop", vanishedWithoutStop)
            assertFalse("RUNNING vanished mid-window without a stop - the " +
                "runtime exited abnormally (its finally removes the lock " +
                "on ANY exit)", vanishedWithoutStop)
            // Informational only: offline non-fatal paths log tracebacks.
            report.put("tracebackSeenInLogTail", logTail(logFile).contains("Traceback"))
            gate("state_live")
            val stLive = JSONObject(File(files, "bot_state.json").readText())
            report.put("stateProvisionedLive", stLive.optBoolean("provisioned"))
            assertTrue("state lost provisioned mid-run",
                stLive.optBoolean("provisioned"))

            // ---- 5. clean, non-overlapping stop: the watchdog actually
            // processed the flag (fresh line, asserted), the lock went
            // away AND the exact RPC child drained - all required before
            // the account recheck opens the DB again ----
            phase = "stop"
            gate("stop_lock")
            val stopLogMark = if (logFile.isFile) logFile.length() else 0L
            stopFlag.writeText("stop")
            val stopped = waitFor(STOP_BUDGET_MS) { !running.exists() }
            report.put("cleanStopCompleted", stopped)
            assertTrue("runtime did not stop within ${STOP_BUDGET_MS}ms", stopped)
            gate("stop_watchdog")
            val watchdogProcessed = logContainsBeyond(logFile, stopLogMark, WATCHDOG_BEACON)
            report.put("watchdogProcessedStop", watchdogProcessed)
            assertTrue("watchdog beacon absent after the stop - the lock " +
                "disappeared without the watchdog processing the flag",
                watchdogProcessed)
            gate("rpc_stop_inspection")
            val drainScan = waitForScan(CHILD_DRAIN_BUDGET_MS, rpcServerPath) {
                it.children.isEmpty()
            }
            report.put("rpcChildDrainScanUnavailable", !drainScan.complete)
            report.put("rpcChildDrainedBeforeRecheck",
                drainScan.complete && drainScan.children.isEmpty())
            gate("rpc_stop_available")
            assertTrue("rpc child scan unavailable after the stop - " +
                "cannot prove non-overlap, failing closed", drainScan.complete)
            gate("rpc_stop_empty")
            assertTrue("librpcsrv.so child still alive after lock removal " +
                "- a DB recheck would overlap it", drainScan.children.isEmpty())

            // Worker thread terminated too (observed live above, so a
            // null/dead accessor here is genuine termination): the DB
            // recheck must not overlap even a dying worker.
            gate("worker_stopped")
            val workerGone = waitFor(STOP_BUDGET_MS) { activeWorkerTerminated() }
            report.put("workerTerminatedAfterStop", workerGone)
            assertTrue("BotService worker not terminated after the stop - " +
                "the DB recheck could overlap a dying worker", workerGone)

            // ---- 6. the imported account survived the live cycle, read
            // only after the rpc server is provably gone ----
            phase = "account-after"
            gate("account_count")
            val countAfter = fixture.callAttr("count_configured", nativeLib, filesPath).toInt()
            report.put("accountCountAfter", countAfter)
            assertEquals(1, countAfter)
            gate("account_address")
            val addrs = org.json.JSONArray(
                fixture.callAttr("configured_addrs", nativeLib, filesPath).toString())
            report.put("addrMatchesAfter",
                addrs.length() == 1 && addrs.getString(0) == ADDR)
            assertTrue("imported account changed by the live cycle",
                addrs.length() == 1 && addrs.getString(0) == ADDR)

            phase = "done"
            File(outDir, "postlaunch-report.json").writeText(report.toString())
            diagnostic("body_passed")
        } catch (t: Throwable) {
            diagnostic("body_failed")
            primary = t
        }

        // ---- unconditional verified teardown (every exit path) ----
        var teardownError: String? = null
        diagnostic("cleanup_started")
        try {
            // Always request the stop and clear the persisted mode, even
            // if the lock never appeared or already vanished: a child
            // can outlive its lock, and last_mode="live" was persisted
            // by the explicit start, so START_STICKY could otherwise
            // resume live into a later leg.
            stopFlag.writeText("stop")
            ctx.getSharedPreferences("bot", Context.MODE_PRIVATE)
                .edit().putString("last_mode", "").apply()
            ctx.stopService(Intent(ctx, BotService::class.java))
            // FIRST establish the worker thread's fate: onDestroy wrote
            // STOP without joining the static worker, and only a dead
            // worker guarantees nothing can still enter bot_runtime.run
            // (which unlinks STOP on entry) and open a new RPC server.
            val workerGone: Boolean
            if (workerObservedLive) {
                // A live worker was seen: null/dead now = terminated.
                workerGone = waitFor(STOP_BUDGET_MS) { activeWorkerTerminated() }
            } else {
                // No worker was ever observed (e.g. boot timeout): a
                // queued start callback could still install one. Give it
                // a bounded grace window; if one surfaces, await its
                // death. If none surfaces, cleanup stays UNPROVEN and
                // the leg fails - job-level containment (final leg,
                // runner exits, ephemeral emulator), no in-app cleanup
                // is claimed.
                val surfaced = waitFor(START_GRACE_MS) { activeWorker() != null }
                report.put("cleanupDelayedWorkerSurfaced", surfaced)
                workerGone = if (surfaced) {
                    waitFor(STOP_BUDGET_MS) { activeWorkerTerminated() }
                } else {
                    false
                }
                if (!surfaced) {
                    report.put("cleanupAmbiguous", true)
                    teardownError = "no BotService worker was ever observed; " +
                        "a still-queued start cannot be ruled out - cleanup " +
                        "UNPROVEN, leg fails (job-level containment: final " +
                        "leg, runner exits, ephemeral emulator)"
                }
            }
            diagnostic(if (workerGone) "cleanup_worker_terminated" else "cleanup_worker_unproven")
            report.put("cleanupWorkerTerminated", workerGone)
            // THEN recheck lock and child - verification after worker
            // death, not a single instant a pending worker can undo.
            val lockGone = waitFor(STOP_BUDGET_MS) { !running.exists() }
            diagnostic(if (lockGone) "cleanup_lock_absent" else "cleanup_lock_present")
            report.put("cleanupLockAbsent", lockGone)
            val drain = waitForScan(CHILD_DRAIN_BUDGET_MS, rpcServerPath) {
                it.children.isEmpty()
            }
            diagnostic(if (!drain.complete) "cleanup_rpc_unavailable" else
                if (drain.children.isEmpty()) "cleanup_rpc_drained" else "cleanup_rpc_remaining")
            report.put("cleanupRpcChildScanUnavailable", !drain.complete)
            report.put("cleanupRpcChildDrained", drain.complete && drain.children.isEmpty())
            if (!workerGone) teardownError = "BotService worker thread still alive after teardown"
            if (!lockGone) teardownError = (teardownError?.plus("; ") ?: "") +
                "RUNNING lock still present after teardown"
            if (!(drain.complete && drain.children.isEmpty())) {
                teardownError = (teardownError?.plus("; ") ?: "") +
                    "rpc child teardown unverified (scan complete=${drain.complete}, " +
                    "children=${drain.children.size})"
            }
        } catch (t: Throwable) {
            diagnostic("cleanup_exception")
            teardownError = (teardownError?.plus("; ") ?: "") +
                "teardown threw: ${t.message}"
        }
        try {
            scenario?.close()
        } catch (_: Exception) {
            // activity close is not load-bearing for runtime teardown
        }
        report.put("finalPhase", phase)
        try {
            File(outDir, "postlaunch-assertions.json").writeText(report.toString())
        } catch (_: Exception) {
            // never mask the outcome with report-write trouble
        }
        diagnostic(if (teardownError == null) "cleanup_passed" else "cleanup_unproven")
        if (teardownError != null) {
            val e = AssertionError("postlaunch teardown incomplete: $teardownError")
            if (primary != null) primary!!.addSuppressed(e) else primary = e
        }
        if (primary != null) throw primary!!
    }

    private fun waitFor(budgetMs: Long, cond: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < budgetMs) {
            if (cond()) return true
            Thread.sleep(1000)
        }
        return cond()
    }

    /** Bounded wait whose condition needs scan state; returns the last
     *  scan so the caller can report completeness. */
    private fun waitForScan(
        budgetMs: Long,
        rpcServerPath: String,
        cond: (RpcChildScan) -> Boolean,
    ): RpcChildScan {
        val t0 = System.currentTimeMillis()
        var last = settledRpcChildScan(rpcServerPath)
        while (!last.complete || !cond(last)) {
            if (System.currentTimeMillis() - t0 >= budgetMs) return last
            Thread.sleep(1000)
            last = settledRpcChildScan(rpcServerPath)
        }
        return last
    }

    /** bot.log content strictly past byte offset `mark`:
     *  classification only, the log itself never leaves the emulator. */
    private fun logContainsBeyond(logFile: File, mark: Long, needle: String): Boolean {
        if (!logFile.isFile) return false
        val len = logFile.length()
        if (len <= mark) return false
        return logFile.inputStream().use { s ->
            s.skip(mark)
            String(s.readBytes()).contains(needle)
        }
    }

    private fun logTail(logFile: File): String {
        if (!logFile.isFile) return ""
        return logFile.inputStream().use { s ->
            s.skip(maxOf(0L, logFile.length() - 65536))
            String(s.readBytes())
        }
    }

    private class RpcChildScan(val children: List<Int>, val complete: Boolean)

    /** Scan with bounded retries: an unreadable entry makes the scan
     *  INCOMPLETE (reported, gate fails closed) rather than looking
     *  like zero children. */
    private fun settledRpcChildScan(rpcServerPath: String): RpcChildScan {
        var last = scanRpcChildren(rpcServerPath)
        var attempts = 1
        while (!last.complete && attempts < 5) {
            Thread.sleep(500)
            last = scanRpcChildren(rpcServerPath)
            attempts++
        }
        return last
    }

    /** BotService's static worker reference via its existing test
     *  accessor (package-private, so reflectively from this test
     *  package). Null is AMBIGUOUS on its own: "worker exited" (the
     *  terminal path nulls the reference) or "the async start callback
     *  has not installed one yet" - callers must pair it with whether
     *  a worker was observed earlier. An unavailable accessor throws
     *  and fails the leg, never masquerading as a null worker. */
    private fun activeWorker(): Thread? {
        val m = Class.forName("com.instinct.widget.bot.BotService")
            .getDeclaredMethod("getActiveWorkerForTesting")
        m.isAccessible = true
        // Do not catch inspection failures as null: only a successful
        // invocation returning null can mean the observed worker exited.
        return m.invoke(null) as Thread?
    }

    /** Meaningful only after a worker has been OBSERVED live: then a
     *  null reference or a dead thread is genuine termination. */
    private fun activeWorkerTerminated(): Boolean {
        val w = activeWorker()
        return w == null || !w.isAlive
    }

    /** This process's own children whose argv[0] is EXACTLY the
     *  DeltaChat RPC server path. deltachat_rpc_client.Rpc.start()
     *  spawns Popen(rpc_server_path) with a single-argument cmdline and
     *  the accounts dir via env, so argv[0] equality is the precise
     *  identity - no substring heuristic.
     *
     *  Enumeration is via /proc/self/task/<tid>/children only - every
     *  thread's list unioned, because Popen forks from the BotService
     *  worker thread, not necessarily the main thread. All of /proc is
     *  never listed, so hidden, restricted or churning UNRELATED
     *  processes cannot affect the gate. Any unreadable task list,
     *  children file or candidate cmdline marks the scan incomplete
     *  (distinct from zero children); a kernel without this own-child
     *  interface therefore fails the leg closed as a named capability
     *  limitation rather than silently passing. */
    private fun scanRpcChildren(rpcServerPath: String): RpcChildScan {
        val tasks = File("/proc/self/task").listFiles()
            ?: return RpcChildScan(emptyList(), false)
        val childPids = sortedSetOf<Int>()
        var complete = true
        for (t in tasks) {
            if (t.name.toIntOrNull() == null) continue
            val text = try {
                File(t, "children").readText()
            } catch (_: Exception) {
                complete = false; continue
            }
            text.split(Regex("\\s+")).forEach { tok ->
                if (tok.isNotEmpty()) {
                    val pid = tok.toIntOrNull()
                    if (pid == null || pid <= 0) complete = false
                    else childPids.add(pid)
                }
            }
        }
        if (!complete) return RpcChildScan(emptyList(), false)
        val children = mutableListOf<Int>()
        for (p in childPids) {
            val cmd = try {
                File("/proc/$p/cmdline").readText()
            } catch (_: Exception) {
                // candidate vanished or unreadable mid-scan: unproven,
                // never "zero"
                complete = false; continue
            }
            val argv0 = cmd.split('\u0000').firstOrNull() ?: ""
            if (argv0 == rpcServerPath) children.add(p)
        }
        return RpcChildScan(children, complete)
    }
}
