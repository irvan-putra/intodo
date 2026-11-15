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
import com.vanipy.intodo.bot.BotActivity
import com.vanipy.intodo.bot.BotImport
import com.vanipy.intodo.bot.BotService
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * EMULATOR QUALIFICATION - import side (new identity, com.vanipy.intodo).
 *
 * Consumes the fixture bundle produced by the OLD identity's
 * MigrationEmuExportTest (pushed by the workflow to filesDir/emu-in).
 * test1 drives the REAL import UI: cached bundle -> transfer code dialog
 * -> runImport's admission lock -> migration.py import_prepare/commit
 * with the real core -> "Bot imported" dialog. test2 proves wrong-code
 * refusal changes nothing. test3 proves a crash after prepare rolls the
 * account back through the real launch recovery barrier.
 *
 * NOT covered on-device (stated honestly in the qualification report):
 * process death mid-commit (covered on the desktop real-core preflight),
 * crash mid-export, power-loss durability, the SAF save step.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MigrationEmuImportTest {

    private val ADDR = "fixture-bot@emu.fixture"
    private val MARK_STATE = "EMU-STATE-MARKER-5dca71"
    private val MARK_OPS = "EMU-OPS-MARKER-9f02e4"
    private val MARK_PRIV = "EMU-PRIV-MARKER-31c7aa"

    private lateinit var ctx: Context
    private lateinit var device: UiDevice
    private lateinit var files: File
    private lateinit var outDir: File
    private lateinit var stagedBundle: File
    private lateinit var code: String
    private lateinit var nativeLib: String

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ctx = ApplicationProvider.getApplicationContext()
        device = UiDevice.getInstance(instrumentation)
        files = ctx.filesDir
        nativeLib = ctx.applicationInfo.nativeLibraryDir
        outDir = File(files, "emu-out").apply { mkdirs() }
        val inDir = File(files, "emu-in")
        code = File(inDir, "code.txt").readText().trim()
        stagedBundle = File(ctx.cacheDir, "import-emu.dcmig")
        File(inDir, "dcbot-migration.dcmig").copyTo(stagedBundle, true)

        // clean slate: no bot state, no migration leftovers, no accounts
        ctx.stopService(Intent(ctx, BotService::class.java))
        listOf("RUNNING", "STOP", "bot_state.json", "IMPORT_PENDING.json",
               "IMPORT_PREFS.json", "IMPORT_PRIOR_PREFS.json",
               ".import-backup.tmp", "bot.log").forEach { File(files, it).delete() }
        File(files, ".import-staging").deleteRecursively()
        File(files, "accounts").deleteRecursively()
        for (cid in listOf(5, 9)) File(files, "ops-$cid.db").delete()
        ctx.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ctx.getSharedPreferences("review_canvas", Context.MODE_PRIVATE)
            .edit().clear().commit()

        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
    }

    private fun py() = Python.getInstance()

    private fun countConfigured(): Int = py().getModule("emu_fixture")
        .callAttr("count_configured", nativeLib, files.absolutePath).toInt()

    private fun waitRecoveryComplete() {
        val deadline = System.currentTimeMillis() + 240_000
        while (!BotImport.isRecoveryComplete()
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
        }
        assertTrue("recovery barrier did not complete", BotImport.isRecoveryComplete())
    }

    @Test
    fun test1_importHappyPath() {
        assertEquals(0, countConfigured())
        ActivityScenario.launch(BotActivity::class.java).use { scenario ->
            waitRecoveryComplete()
            // Stage the bundle exactly where the real flow would have it
            // after the file pick (handleActivityResult copies SAF content
            // to a cache file); SAF itself is not exercised on-device.
            scenario.onActivity { a ->
                val importerField =
                    BotActivity::class.java.getDeclaredField("importer")
                importerField.isAccessible = true
                val importer = importerField.get(a)
                val bundleField = importer.javaClass.getDeclaredField("bundleFile")
                bundleField.isAccessible = true
                bundleField.set(importer, stagedBundle)
            }
            var importBtn = device.wait(
                Until.findObject(By.text("Import bot backup")), 60_000)
            if (importBtn == null) {
                // unprovisioned install shows the setup box expanded, but
                // the button may sit off-screen: scroll to it first
                androidx.test.uiautomator.UiScrollable(
                    androidx.test.uiautomator.UiSelector().scrollable(true))
                    .scrollTextIntoView("Import bot backup")
                importBtn = device.wait(
                    Until.findObject(By.text("Import bot backup")), 15_000)
            }
            if (importBtn == null) {
                // still not found: the box may be collapsed - toggle it
                device.findObject(By.textContains("Setup & account"))?.click()
                importBtn = device.wait(
                    Until.findObject(By.text("Import bot backup")), 15_000)
            }
            val btn = checkNotNull(importBtn) { "import button not shown" }
            btn.click()
            // cached bundle -> straight to the transfer-code dialog
            checkNotNull(device.wait(
                Until.findObject(By.text("Transfer code")), 30_000))
                { "transfer code dialog not shown" }
            device.takeScreenshot(File(outDir, "01-import-code-dialog.png"))
            val input = checkNotNull(device.wait(
                Until.findObject(By.clazz("android.widget.EditText")), 10_000))
                { "code input not found" }
            input.setText(code)
            checkNotNull(device.findObject(By.text("Import"))) {
                "Import button not found" }.click()

            val ok = device.wait(Until.findObject(By.text("Bot imported")), 300_000)
            val failDlg = device.findObject(By.text("Import failed"))
            if (ok == null) {
                device.takeScreenshot(File(outDir, "01-import-FAILED.png"))
                fail("import did not complete" +
                    (failDlg?.let { " (failure dialog shown)" } ?: ""))
            }
            device.takeScreenshot(File(outDir, "02-import-success-dialog.png"))
        }

        // landed state, account, settings
        val st = JSONObject(File(files, "bot_state.json").readText())
        assertEquals(ADDR, st.getString("bot_addr"))
        assertEquals(MARK_STATE, st.getString("mark"))
        for (cid in listOf(5, 9)) {
            val bytes = File(files, "ops-$cid.db").readBytes()
            assertTrue(String(bytes, Charsets.ISO_8859_1).contains(MARK_OPS))
        }
        assertEquals(1, countConfigured())
        val w = ctx.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE)
        assertTrue(w.getString("priv", "")!!.endsWith(MARK_PRIV))
        assertEquals("{\"v\":1,\"emu\":true}",
            ctx.getSharedPreferences("review_canvas", Context.MODE_PRIVATE)
                .getString("layout", ""))
        // transaction fully cleaned
        assertFalse(File(files, "IMPORT_PENDING.json").exists())
        assertFalse(File(files, ".import-staging").exists())
        assertFalse(File(files, "IMPORT_PREFS.json").exists())
        assertFalse(File(files, "IMPORT_PRIOR_PREFS.json").exists())
        File(files, "bot.log").takeIf { it.exists() }
            ?.copyTo(File(outDir, "bot.log"), true)
        File(outDir, "import-done.json").writeText(
            """{"addr":"$ADDR","configured":1}""")
    }

    @Test
    fun test2_wrongCodeRefused() {
        val mig = py().getModule("migration")
        try {
            mig.callAttr("import_prepare", nativeLib, files.absolutePath,
                stagedBundle.absolutePath, "x".repeat(44))
            fail("wrong transfer code ACCEPTED")
        } catch (expected: Exception) {
            // MigrationError from the Fernet decrypt - any refusal is fine
        }
        assertFalse(File(files, "IMPORT_PENDING.json").exists())
        assertFalse(File(files, "bot_state.json").exists())
        assertFalse(File(files, "IMPORT_PREFS.json").exists())
        assertFalse(File(files, ".import-staging").exists())
        assertEquals(0, countConfigured())
    }

    @Test
    fun test3_crashAfterPrepare_rollsBackThroughLaunchBarrier() {
        // Phase 1 for real: account imported, journal "prepared", settings
        // staged - then the "crash": nothing is committed.
        val addr = py().getModule("migration").callAttr(
            "import_prepare", nativeLib, files.absolutePath,
            stagedBundle.absolutePath, code).toString()
        assertEquals(ADDR, addr)
        assertTrue(File(files, "IMPORT_PENDING.json").exists())
        assertTrue(File(files, "IMPORT_PREFS.json").exists())
        assertEquals(1, countConfigured())

        // Relaunch: BotActivity.onCreate runs the recovery barrier, which
        // must roll the uncommitted import back (directive "aborted").
        // NOTE: the static recovery gate was DONE since test1, and
        // recoverInterruptedImport does NOT reset it on in-process
        // re-entry - so completion is proven on DISK (the journal is
        // removed only at the END of the rollback), not from the gate.
        ActivityScenario.launch(BotActivity::class.java).use {
            val deadline = System.currentTimeMillis() + 240_000
            while ((File(files, "IMPORT_PENDING.json").exists() || countConfigured() != 0)
                   && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            device.takeScreenshot(File(outDir, "03-after-recovery.png"))
        }
        assertFalse(File(files, "IMPORT_PENDING.json").exists())
        assertFalse(File(files, ".import-staging").exists())
        assertFalse(File(files, "IMPORT_PREFS.json").exists())
        assertFalse(File(files, "bot_state.json").exists())
        assertEquals("imported fixture account must be removed by recovery",
            0, countConfigured())
    }
}
