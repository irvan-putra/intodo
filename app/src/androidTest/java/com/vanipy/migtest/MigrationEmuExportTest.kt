package com.vanipy.migtest

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
 * EMULATOR QUALIFICATION - export side (old identity, com.instinct.widget).
 *
 * Drives the REAL export path end to end with a fixture-only Delta
 * Chat account (emu_fixture.seed_account: no configured servers, no
 * I/O started, no real keys; the app KEEPS its INTERNET permission -
 * in the round-trip harness the emulator runs with QEMU `-nic none`,
 * i.e. no guest NIC at all, verified fail-closed by ci/emu_runner.sh): the
 * export button, the service "export" mode, migration.py
 * export_migration under Chaquopy with the real core, and the transfer
 * code dialog. Produces to filesDir/emu-out: the .dcmig bundle,
 * code.txt, screenshots and logs (NEVER uploaded - secret by design;
 * only the self-authored export-done.json and listings leave the
 * emulator, see ci/emu_runner.sh).
 */
@RunWith(AndroidJUnit4::class)
class MigrationEmuExportTest {

    private val MARK_STATE = "EMU-STATE-MARKER-5dca71"
    private val MARK_OPS = "EMU-OPS-MARKER-9f02e4"
    private val MARK_PRIV = "EMU-PRIV-MARKER-31c7aa"
    private val ADDR = "fixture-bot@emu.fixture"

    @Test
    fun exportFlow_producesVerifiableBundle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        val files = ctx.filesDir
        val outDir = File(files, "emu-out").apply { deleteRecursively(); mkdirs() }
        val failureDump = File(files, "rt-uidump-failure.xml")
        failureDump.delete()
        assertFalse("stale failure UI dump must be removed before test", failureDump.exists())

        // Emit only fixed phase names in runner-local instrumentation output.
        // Never include exception, result, address, code or file contents.
        fun phase(name: String) {
            instrumentation.sendStatus(100, android.os.Bundle().apply {
                putString("emu_phase", name)
            })
        }
        // Stage-6 diagnostics must never replace a test/decryption failure
        // if the instrumentation status channel itself is unavailable.
        fun diagnosticPhase(name: String) {
            try { phase(name) } catch (_: Throwable) {}
        }

        // ---- 1. clean slate + fixture app state (fixture values only) ----
        ctx.stopService(Intent(ctx, BotService::class.java))
        listOf("RUNNING", "STOP", "EXPORT_RESULT", "EXPORT_PREFS.json",
               "bot_state.json", "bot.log").forEach { File(files, it).delete() }
        File(files, "export-out").deleteRecursively()
        File(files, "accounts").deleteRecursively()
        for (cid in listOf(5, 9)) File(files, "ops-$cid.db").delete()

        assertTrue(ctx.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE).edit()
            .putString("priv", "fixture-priv-$MARK_PRIV")
            .putString("pub", "fixture-pub")
            .putString("feed", "fixture-feed-ciphertext")
            .putString("feed_gen", "7")
            .putString("feed_url", "https://feed.example.test/f")
            .putString("status", "ok")
            .putLong("feed_at", 1763000000L)
            .commit())
        assertTrue(ctx.getSharedPreferences("review_canvas", Context.MODE_PRIVATE).edit()
            .putString("layout", "{\"v\":1,\"emu\":true}")
            .commit())
        File(files, "bot_state.json").writeText(
            """{"provisioned":true,"bot_addr":"$ADDR",""" +
            """"owner_addr":"owner@example.test","mark":"$MARK_STATE"}""")
        for (cid in listOf(5, 9)) {
            File(files, "ops-$cid.db").writeBytes(
                "ops-$cid-".toByteArray() + MARK_OPS.toByteArray() + byteArrayOf(0))
        }

        phase("seeded")

        // ---- 2. fixture account via the app's real Chaquopy runtime ----
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        val py = Python.getInstance()
        py.getModule("emu_fixture").callAttr(
            "seed_account", ctx.applicationInfo.nativeLibraryDir, files.absolutePath)
        assertEquals(1, py.getModule("emu_fixture").callAttr(
            "count_configured", ctx.applicationInfo.nativeLibraryDir,
            files.absolutePath).toInt())
        phase("python_ready")

        // Emulator-only test preparation and observations: this does not
        // change application code or run on a user's device. Record screen
        // and keyguard state before the test wakes/dismisses them.
        phase(if (device.isScreenOn) "screen_on" else "screen_off")
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        phase(if (keyguard?.isKeyguardLocked == true)
            "keyguard_locked" else "keyguard_unlocked")
        try { device.wakeUp() } catch (_: Exception) {}
        try { device.executeShellCommand("wm dismiss-keyguard") } catch (_: Exception) {}

        phase("activity_launching")
        val scenario = ActivityScenario.launch(BotActivity::class.java)
        scenario.use {
            phase("activity_launched")
            // Record focus before any test window adjustment.
            phase("focused_" + when (device.currentPackageName) {
                "com.instinct.widget" -> "app"
                "com.android.systemui" -> "systemui"
                "com.android.launcher3",
                "com.google.android.apps.nexuslauncher" -> "launcher"
                "com.android.permissioncontroller" -> "permissioncontroller"
                "com.android.packageinstaller" -> "packageinstaller"
                "com.android.settings" -> "settings"
                "com.google.android.gms" -> "gms"
                null -> "none"
                else -> "other"
            })
            // Keep only this test activity's window awake through the long
            // UI waits. Do not show over a locked screen or turn it on here:
            // those could hide an app visibility defect. The disposable
            // emulator was explicitly woken/unlocked above.
            it.onActivity { activity ->
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // ---- 3. real UI: Export bot + settings -> confirm ----
            // A provisioned install starts with the setup box COLLAPSED
            // (header "Setup & account  >") - expand it first.
            val header = device.wait(
                Until.findObject(By.textContains("Setup & account")), 60_000)
            if (header == null) {
                phase("header_timeout")
                try { device.dumpWindowHierarchy(failureDump) } catch (_: Exception) {}
                org.junit.Assert.fail("setup header not shown")
            }
            phase("header_seen")
            if (device.findObject(By.text("Export bot + settings")) == null) {
                device.findObject(By.textContains("Setup & account"))?.click()
                phase("expand_clicked")
            }
            phase("setup_open")
            var exportBtn = device.wait(
                Until.findObject(By.text("Export bot + settings")), 30_000)
            if (exportBtn == null) {
                // off-screen in the scroll view: bring it into view
                androidx.test.uiautomator.UiScrollable(
                    androidx.test.uiautomator.UiSelector().scrollable(true))
                    .scrollTextIntoView("Export bot + settings")
                exportBtn = device.wait(
                    Until.findObject(By.text("Export bot + settings")), 15_000)
            }
            val btn = checkNotNull(exportBtn) { "export button not shown" }
            btn.click()
            phase("button_clicked")
            val confirmPanel = try {
                device.wait(
                    Until.findObject(By.res("android", "parentPanel")
                        .hasDescendant(By.textContains("pauses the live bot"))), 15_000)
            } catch (t: Throwable) {
                phase("confirm_wait_error")
                try { device.dumpWindowHierarchy(failureDump) } catch (_: Exception) {}
                throw t
            }
            if (confirmPanel == null) {
                phase("confirm_timeout")
                try { device.dumpWindowHierarchy(failureDump) } catch (_: Exception) {}
                org.junit.Assert.fail("confirm dialog not shown")
            }
            phase("confirm_shown")
            // Match only the platform AlertDialog positive-button resource
            // inside the parent panel whose descendant is the confirmation
            // message. Anchored case-insensitive text accepts presentation
            // variants without matching unrelated Export-labelled controls.
            val confirmButtonSelector = By.res("android", "button1")
                .clazz("android.widget.Button")
                .text(java.util.regex.Pattern.compile(
                    "^export$", java.util.regex.Pattern.CASE_INSENSITIVE))
            val confirmBtn = try {
                confirmPanel.wait(Until.findObject(confirmButtonSelector), 10_000)
            } catch (t: Throwable) {
                phase("confirm_button_wait_error")
                try { device.dumpWindowHierarchy(failureDump) } catch (_: Exception) {}
                throw t
            }
            if (confirmBtn == null) {
                phase("confirm_button_missing")
                try { device.dumpWindowHierarchy(failureDump) } catch (_: Exception) {}
                org.junit.Assert.fail("confirm Export button not found")
            }
            confirmBtn.click()
            phase("confirm_clicked")

            // ---- 4. the service export mode runs migration.py for real ----
            val resFile = File(files, "EXPORT_RESULT")
            val deadline = System.currentTimeMillis() + 300_000
            while (!resFile.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            assertTrue("export timed out (no EXPORT_RESULT)", resFile.exists())
            val res = JSONObject(resFile.readText())
            assertTrue("export failed: ${res.optString("error")}", !res.has("error"))
            val bundle = res.getString("bundle")
            val code = res.getString("code")
            assertEquals(44, code.length)
            assertFalse("plaintext prefs snapshot must be consumed",
                File(files, "EXPORT_PREFS.json").exists())
            assertFalse("raw backup tar must not linger",
                File(files, "export-out").list()
                    ?.any { it.endsWith(".tar") } == true)
            phase("result_seen")

            // ---- 5. transfer-code dialog shown: visual evidence ----
            checkNotNull(device.wait(
                Until.findObject(By.textContains("one-time transfer code")), 60_000))
                { "transfer code dialog not shown" }
            phase("code_dialog_seen")
            device.takeScreenshot(File(outDir, "01-export-code-dialog.png"))

            // ---- 6. verify the bundle through migration.py itself ----
            // Stage-local outcome enums are best-effort; all original
            // operations, values, conversions, and failure assertions stay.
            val mig = try {
                py.getModule("migration")
            } catch (t: Throwable) {
                diagnosticPhase("migration_module_error")
                throw t
            }
            val raw = try {
                File(bundle).readBytes()
            } catch (t: Throwable) {
                diagnosticPhase("bundle_read_error")
                throw t
            }
            diagnosticPhase("bundle_read_ok")
            val rawStr = try {
                String(raw, Charsets.ISO_8859_1)
            } catch (t: Throwable) {
                diagnosticPhase("raw_text_error")
                throw t
            }
            if (rawStr.contains(MARK_STATE)) {
                diagnosticPhase("raw_leak_state")
                org.junit.Assert.fail("state marker visible in raw bundle")
            } else diagnosticPhase("raw_state_clean")
            if (rawStr.contains(MARK_OPS)) {
                diagnosticPhase("raw_leak_ops")
                org.junit.Assert.fail("ops marker visible in raw bundle")
            } else diagnosticPhase("raw_ops_clean")
            if (rawStr.contains(MARK_PRIV)) {
                diagnosticPhase("raw_leak_priv")
                org.junit.Assert.fail("settings marker visible in raw bundle")
            } else diagnosticPhase("raw_priv_clean")
            val parsed = try {
                mig.callAttr("parse_bundle", bundle)
            } catch (t: Throwable) {
                diagnosticPhase("bundle_parse_error")
                throw t
            }
            diagnosticPhase("bundle_parsed")
            val stateTar = try {
                mig.callAttr("_decrypt", code,
                    mig.get("STATE_DOMAIN"), parsed.callAttr("get", "state_blob"))
                    .toJava(ByteArray::class.java)
            } catch (t: Throwable) {
                diagnosticPhase("state_decrypt_error")
                throw t
            }
            diagnosticPhase("state_decrypted")
            val stateStr = try {
                String(stateTar, Charsets.ISO_8859_1)
            } catch (t: Throwable) {
                diagnosticPhase("state_text_error")
                throw t
            }
            if (!stateStr.contains(MARK_STATE)) {
                diagnosticPhase("state_mark_state_fail")
                org.junit.Assert.fail("state tar missing fixture state marker")
            } else diagnosticPhase("state_mark_state_ok")
            if (!stateStr.contains(MARK_OPS)) {
                diagnosticPhase("state_mark_ops_fail")
                org.junit.Assert.fail("state tar missing fixture ops marker")
            } else diagnosticPhase("state_mark_ops_ok")
            // excluded runtime artifacts must not travel
            if (stateStr.contains("secret-bot.log")) {
                diagnosticPhase("state_logs_leak")
                org.junit.Assert.fail("state tar includes log artifact")
            } else diagnosticPhase("state_logs_clean")
            val prefsJson = try {
                String(mig.callAttr("_decrypt", code,
                    mig.get("PREFS_DOMAIN"), parsed.callAttr("get", "prefs_blob"))
                    .toJava(ByteArray::class.java), Charsets.UTF_8)
            } catch (t: Throwable) {
                diagnosticPhase("prefs_decrypt_error")
                throw t
            }
            diagnosticPhase("prefs_decrypted")
            if (!prefsJson.contains(MARK_PRIV)) {
                diagnosticPhase("prefs_marker_fail")
                org.junit.Assert.fail("prefs blob missing fixture settings marker")
            } else diagnosticPhase("prefs_marker_ok")
            // Backup blob decrypts with the code (integrity); SQLCipher
            // contents remain exercised by import and are not inspected.
            try {
                mig.callAttr("_decrypt", code, mig.get("WRAP_DOMAIN"),
                    parsed.callAttr("get", "backup"))
            } catch (t: Throwable) {
                diagnosticPhase("wrap_decrypt_error")
                throw t
            }
            diagnosticPhase("wrap_decrypted")
            diagnosticPhase("bundle_validated")

            // ---- 7. handoff artifacts for the workflow ----
            File(bundle).copyTo(File(outDir, "dcbot-migration.dcmig"), true)
            File(outDir, "code.txt").writeText(code)
            resFile.copyTo(File(outDir, "EXPORT_RESULT.json"), true)
            File(files, "bot.log").takeIf { it.exists() }
                ?.copyTo(File(outDir, "bot.log"), true)
            File(outDir, "export-done.json").writeText(
                """{"bundleBytes":${raw.size},"codeLen":${code.length},"addr":"$ADDR"}""")
            device.takeScreenshot(File(outDir, "02-export-final.png"))
            phase("artifacts_written")
        }
    }
}
