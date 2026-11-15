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
 * I/O started, no real keys; the app KEEPS its INTERNET permission
 * and the emulator is NOT network-isolated): the export button, the service "export" mode, migration.py
 * export_migration under Chaquopy with the real core, and the transfer
 * code dialog. Produces to filesDir/emu-out (adb-pulled by the
 * workflow): the .dcmig bundle, code.txt, screenshots and logs.
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

        // ---- 2. fixture account via the app's real Chaquopy runtime ----
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        val py = Python.getInstance()
        py.getModule("emu_fixture").callAttr(
            "seed_account", ctx.applicationInfo.nativeLibraryDir, files.absolutePath)
        assertEquals(1, py.getModule("emu_fixture").callAttr(
            "count_configured", ctx.applicationInfo.nativeLibraryDir,
            files.absolutePath).toInt())

        ActivityScenario.launch(BotActivity::class.java).use {
            // ---- 3. real UI: Export bot + settings -> confirm ----
            // A provisioned install starts with the setup box COLLAPSED
            // (header "Setup & account  >") - expand it first.
            checkNotNull(device.wait(
                Until.findObject(By.textContains("Setup & account")), 60_000))
                { "setup header not shown" }
            if (device.findObject(By.text("Export bot + settings")) == null) {
                device.findObject(By.textContains("Setup & account"))?.click()
            }
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
            checkNotNull(device.wait(
                Until.findObject(By.textContains("pauses the live bot")), 15_000))
                { "confirm dialog not shown" }
            checkNotNull(device.findObject(By.text("Export"))) {
                "confirm Export button not found" }.click()

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

            // ---- 5. transfer-code dialog shown: visual evidence ----
            checkNotNull(device.wait(
                Until.findObject(By.textContains("one-time transfer code")), 60_000))
                { "transfer code dialog not shown" }
            device.takeScreenshot(File(outDir, "01-export-code-dialog.png"))

            // ---- 6. verify the bundle through migration.py itself ----
            val mig = py.getModule("migration")
            val raw = File(bundle).readBytes()
            val rawStr = String(raw, Charsets.ISO_8859_1)
            assertFalse(rawStr.contains(MARK_STATE))
            assertFalse(rawStr.contains(MARK_OPS))
            assertFalse(rawStr.contains(MARK_PRIV))
            val parsed = mig.callAttr("parse_bundle", bundle)
            val stateTar = mig.callAttr("_decrypt", code,
                mig.get("STATE_DOMAIN"), parsed.callAttr("get", "state_blob"))
                .toJava(ByteArray::class.java)
            val stateStr = String(stateTar, Charsets.ISO_8859_1)
            assertTrue(stateStr.contains(MARK_STATE))
            assertTrue(stateStr.contains(MARK_OPS))
            // excluded runtime artifacts must not travel
            assertFalse(stateStr.contains("secret-bot.log"))
            val prefsJson = String(mig.callAttr("_decrypt", code,
                mig.get("PREFS_DOMAIN"), parsed.callAttr("get", "prefs_blob"))
                .toJava(ByteArray::class.java), Charsets.UTF_8)
            assertTrue(prefsJson.contains(MARK_PRIV))
            // the backup blob decrypts with the code (integrity) - contents
            // are core's SQLCipher dump, exercised by the import side
            mig.callAttr("_decrypt", code, mig.get("WRAP_DOMAIN"),
                parsed.callAttr("get", "backup"))

            // ---- 7. handoff artifacts for the workflow ----
            File(bundle).copyTo(File(outDir, "dcbot-migration.dcmig"), true)
            File(outDir, "code.txt").writeText(code)
            resFile.copyTo(File(outDir, "EXPORT_RESULT.json"), true)
            File(files, "bot.log").takeIf { it.exists() }
                ?.copyTo(File(outDir, "bot.log"), true)
            File(outDir, "export-done.json").writeText(
                """{"bundleBytes":${raw.size},"codeLen":${code.length},"addr":"$ADDR"}""")
            device.takeScreenshot(File(outDir, "02-export-final.png"))
        }
    }
}
