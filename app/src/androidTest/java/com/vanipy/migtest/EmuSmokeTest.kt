package com.vanipy.migtest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SHORT SMOKE GATE - runs BEFORE the long migration qualification.
 * Proves the three things that must hold on the actual device image
 * before any full flow is attempted: Chaquopy/Python starts, the bundled
 * arm64 core (librpcsrv) loads and answers, and the fixture's direct
 * SQLite seeding + offline key creation is accepted by THIS build of
 * core. Fixture-only; takes minutes, fails fast with a clear signal.
 */
@RunWith(AndroidJUnit4::class)
class EmuSmokeTest {

    @Test
    fun pythonCoreAndFixtureSeedWork() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val files = ctx.filesDir
        File(files, "accounts").deleteRecursively()
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        val py = Python.getInstance()
        py.getModule("emu_fixture").callAttr("install_bounded_rpc_startup_diagnostics")
        py.getModule("emu_fixture").callAttr(
            "seed_account", ctx.applicationInfo.nativeLibraryDir,
            files.absolutePath)
        assertEquals(1, py.getModule("emu_fixture").callAttr(
            "count_configured", ctx.applicationInfo.nativeLibraryDir,
            files.absolutePath).toInt())
        val out = File(files, "emu-out").apply { mkdirs() }
        File(out, "smoke-ok.json").writeText(
            """{"python":"ok","core":"ok","fixtureSeed":"ok"}""")
    }
}
