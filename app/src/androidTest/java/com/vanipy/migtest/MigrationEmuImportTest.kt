package com.vanipy.migtest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.instinct.widget.bot.BotImport
import com.instinct.widget.bot.SharedPrefsFile
import com.instinct.widget.bot.StagedPrefsApplier
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EMULATOR QUALIFICATION - import side (renamed identity,
 * com.vanipy.intodo via -PemuAppId; the APKs are identical apart from
 * the application ID, which IS the phone identity being tested).
 *
 * Drives the REAL import transaction core with the bundle + one-time
 * code produced by MigrationEmuExportTest on the old identity in the
 * SAME emulator session (harness hands them over through
 * files/emu-in): BotImport.withImportAdmission, migration.py
 * import_prepare, the production StagedPrefsApplier, and
 * import_commit - the exact call sequence BotImport.runImport runs,
 * without the file-picker/code-dialog UI shell (a stated limit: the
 * system DocumentsUI picker is not exercised; the transaction core,
 * journal, settings apply and account landing are).
 *
 * Fixture-only: the account has NO configured servers (pseudo
 * transport) and no I/O is started; the harness runs the emulator with
 * QEMU `-nic none` (no guest NIC at all, verified fail-closed before
 * any leg runs), so the offline claim is an established environment fact,
 * not an assumption - see ci/emu_runner.sh (roundtrip) and
 * emu-rt-isolation.txt. The app KEEPS its INTERNET permission: this
 * proves the import transaction works with zero external connectivity,
 * not behavior under a revoked permission.
 *
 * Diagnostics: writes files/emu-out/import-assertions.json - a
 * self-authored allowlisted report (phase names, booleans, counts;
 * never raw exception text or app data) so the public-repo workflow
 * needs no raw instrumentation logs.
 */
@RunWith(AndroidJUnit4::class)
class MigrationEmuImportTest {

    // Must match MigrationEmuExportTest's fixture values exactly: they
    // are what the export side wrote into its settings snapshot.
    private val ADDR = "fixture-bot@emu.fixture"
    private val PRIV_VALUE = "fixture-priv-EMU-PRIV-MARKER-31c7aa"
    private val FEED_VALUE = "fixture-feed-ciphertext"
    private val FEED_GEN = "7"
    private val FEED_URL = "https://feed.example.test/f"
    private val FEED_STATUS = "ok"
    private val FEED_AT = 1763000000L
    private val CANVAS_LAYOUT = "{\"v\":1,\"emu\":true}"
    private val STATE_MARKER = "EMU-STATE-MARKER-5dca71"
    private val OPS_MARKER = "EMU-OPS-MARKER-9f02e4"

    @Test
    fun importFlow_landsAccountAndSettings() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val files = ctx.filesDir
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val filesPath = files.absolutePath
        val outDir = File(files, "emu-out").apply { deleteRecursively(); mkdirs() }

        // Structured, allowlisted assertion report for the harness: only
        // self-authored phase names, booleans and counts - never raw
        // exception text or app data. Written in `finally` so a failing
        // leg still leaves its exact failure phase; the raw JUnit output
        // stays runner-local (public-repo artifact policy).
        val report = JSONObject()
        var phase = "handoff"
        try {
            // ---- 0. harness handoff from the old identity's export ----
            val inDir = File(files, "emu-in")
            val bundle = File(inDir, "dcbot-migration.dcmig")
            val codeFile = File(inDir, "code.txt")
            report.put("bundlePresent", bundle.isFile)
            report.put("codePresent", codeFile.isFile)
            assertTrue("bundle missing (harness push failed)", bundle.isFile)
            assertTrue("code missing (harness push failed)", codeFile.isFile)
            val code = codeFile.readText().trim()
            report.put("codeShapeOk", code.length == 44)
            assertEquals("transfer code shape", 44, code.length)

            phase = "python-start"
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
            val py = Python.getInstance()
            val fixture = py.getModule("emu_fixture")
            fixture.callAttr("install_bounded_rpc_startup_diagnostics")

            // ---- 1. virgin renamed install: anything else is a harness bug ----
            phase = "virgin-check"
            val countBefore = fixture.callAttr("count_configured", nativeLib, filesPath).toInt()
            report.put("virginAccountCount", countBefore)
            assertEquals("renamed install must start account-free", 0, countBefore)
            val runningMarker = File(files, "RUNNING").exists()
            report.put("runningMarkerAbsent", !runningMarker)
            assertFalse("live service marker must be absent", runningMarker)

            // ---- 2. the REAL transaction, in runImport's exact order ----
            phase = "transaction"
            val mig = py.getModule("migration")
            var commitAddr: String? = null
            val admitted = BotImport.withImportAdmission(File(files, "RUNNING")) {
                val addr = mig.callAttr("import_prepare", nativeLib, filesPath,
                    bundle.absolutePath, code).toString()
                report.put("prepareAddrMatches", addr == ADDR)
                assertEquals(ADDR, addr)
                // durable journal + staged settings exist BEFORE any commit
                val journalStaged = File(files, "IMPORT_PENDING.json").isFile
                val prefsStaged = File(files, "IMPORT_PREFS.json").isFile
                report.put("journalStagedBeforeMutation", journalStaged)
                report.put("prefsStagedBeforeCommit", prefsStaged)
                assertTrue("import journal not staged", journalStaged)
                assertTrue("settings not staged", prefsStaged)
                val applier = StagedPrefsApplier(
                    File(files, "IMPORT_PREFS.json"),
                    File(files, "IMPORT_PRIOR_PREFS.json")) { name ->
                    SharedPrefsFile(ctx.getSharedPreferences(name, Context.MODE_PRIVATE))
                }
                applier.writePriorSnapshot()
                val prefsError = applier.applyStaged()
                report.put("prefsApplied", prefsError == null)
                assertNull("settings apply failed: $prefsError", prefsError)
                commitAddr = mig.callAttr("import_commit", nativeLib, filesPath).toString()
            }
            report.put("admitted", admitted != null)
            assertNotNull("import not admitted (lifecycle busy or live RUNNING)", admitted)
            report.put("commitAddrMatches", commitAddr == ADDR)
            assertEquals(ADDR, commitAddr)

            // ---- 3. the account landed in THIS install's core ----
            phase = "account-landed"
            val countAfter = fixture.callAttr("count_configured", nativeLib, filesPath).toInt()
            report.put("accountCountAfter", countAfter)
            assertEquals(1, countAfter)
            val addrs = JSONArray(fixture.callAttr("configured_addrs",
                nativeLib, filesPath).toString())
            val addrMatches = addrs.length() == 1 && addrs.getString(0) == ADDR
            report.put("importedAddrMatches", addrMatches)
            assertTrue("imported account address mismatch", addrMatches)

            // ---- 4. imported bot state and per-chat stores landed ----
            phase = "state-landed"
            val stateFile = File(files, "bot_state.json")
            val stateText = if (stateFile.isFile) stateFile.readText() else ""
            val stateMarkerLanded = stateText.contains(STATE_MARKER)
            val stateAddrLanded = stateText.contains(ADDR)
            report.put("stateMarkerLanded", stateMarkerLanded)
            report.put("stateAddrLanded", stateAddrLanded)
            assertTrue("bot state marker missing after commit", stateMarkerLanded)
            assertTrue("bot state account address missing after commit", stateAddrLanded)
            val opsFiles = listOf("ops-5.db", "ops-9.db").map { File(files, it) }
            val opsMarkersLanded = opsFiles.all { it.isFile && it.readBytes().containsMarker(OPS_MARKER) }
            report.put("opsFilesLanded", opsFiles.count { it.isFile })
            report.put("opsMarkersLanded", opsMarkersLanded)
            assertTrue("per-chat ops marker missing after commit", opsMarkersLanded)

            // ---- 5. settings ACTUALLY applied - shared_prefs, not staging ----
            phase = "settings"
            val w = ctx.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE)
            val settings = JSONObject()
            settings.put("priv", w.getString("priv", null) == PRIV_VALUE)
            settings.put("feed", w.getString("feed", null) == FEED_VALUE)
            settings.put("feed_gen", w.getString("feed_gen", null) == FEED_GEN)
            settings.put("feed_url", w.getString("feed_url", null) == FEED_URL)
            settings.put("status", w.getString("status", null) == FEED_STATUS)
            settings.put("feed_at", w.getLong("feed_at", 0) == FEED_AT)
            settings.put("canvas_layout",
                ctx.getSharedPreferences("review_canvas", Context.MODE_PRIVATE)
                    .getString("layout", null) == CANVAS_LAYOUT)
            report.put("settings", settings)
            assertEquals(PRIV_VALUE, w.getString("priv", null))
            assertEquals(FEED_VALUE, w.getString("feed", null))
            assertEquals(FEED_GEN, w.getString("feed_gen", null))
            assertEquals(FEED_URL, w.getString("feed_url", null))
            assertEquals(FEED_STATUS, w.getString("status", null))
            assertEquals(FEED_AT, w.getLong("feed_at", 0))
            assertEquals(CANVAS_LAYOUT,
                ctx.getSharedPreferences("review_canvas", Context.MODE_PRIVATE)
                    .getString("layout", null))

            // ---- 6. transaction fully resolved: no journal or byproducts ----
            phase = "leftovers"
            val leftovers = listOf("IMPORT_PENDING.json", "IMPORT_PREFS.json",
                                   "IMPORT_PRIOR_PREFS.json", ".import-staging")
                .filter { File(files, it).exists() }
            report.put("leftoversPresent", JSONArray(leftovers))
            assertTrue("leftover after commit: $leftovers", leftovers.isEmpty())

            // ---- 7. handoff artifact for the workflow ----
            phase = "done"
            File(outDir, "import-done.json").writeText(
                """{"addr":"$ADDR","settingsApplied":true,"journalResolved":true}""")
        } finally {
            report.put("finalPhase", phase)
            try {
                File(outDir, "import-assertions.json").writeText(report.toString())
            } catch (_: Exception) {
                // never mask the test outcome with report-write trouble
            }
        }
    }

    private fun ByteArray.containsMarker(marker: String): Boolean {
        val needle = marker.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..(size - needle.size)) {
            var matches = true
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
