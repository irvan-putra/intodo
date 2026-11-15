package com.instinct.widget.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Failure-injection tests for the Android settings-restore layer the
 * Python fixtures cannot exercise: the staged snapshot must land
 * transactionally across SharedPreferences files - all files or none.
 */
class StagedPrefsApplierTest {

    /** In-memory PrefsFile with injection points. */
    private class FakePrefsFile(
        initial: Map<String, *> = emptyMap<String, Any>(),
        var failMerge: Boolean = false,
        var failReplace: Boolean = false,
    ) : PrefsFile {
        val data = LinkedHashMap<String, Any?>()
        var mergeCalls = 0
        var replaceCalls = 0

        init {
            initial.forEach { (k, v) -> data[k] = v }
        }

        override fun readAll(): Map<String, *> = LinkedHashMap(data)

        override fun mergeAll(values: Map<String, Any>): Boolean {
            mergeCalls++
            if (failMerge) return false
            data.putAll(values)
            return true
        }

        override fun replaceAll(values: Map<String, *>): Boolean {
            replaceCalls++
            if (failReplace) return false
            data.clear()
            data.putAll(values)
            return true
        }
    }

    private fun stagingFile(content: String): File {
        val f = File.createTempFile("IMPORT_PREFS", ".json")
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `nothing staged is a no-op`() {
        val applier = StagedPrefsApplier(File("/nonexistent/IMPORT_PREFS.json")) {
            throw AssertionError("no prefs file should be opened")
        }
        assertNull(applier.applyStaged())
    }

    @Test
    fun `all files land and staging is consumed`() {
        val instinct = FakePrefsFile(mapOf("install_session" to "keep-me", "feed_at" to 7L))
        val canvas = FakePrefsFile()
        val stores = mapOf("instinct_widget" to instinct, "review_canvas" to canvas)
        val f = stagingFile(
            """{"instinct_widget": {"priv": "P", "feed_at": 9}, "review_canvas": {"layout": "grid"}}""")
        val err = StagedPrefsApplier(f) { stores.getValue(it) }.applyStaged()
        assertNull(err)
        assertEquals("P", instinct.data["priv"])
        assertEquals(9L, instinct.data["feed_at"])
        assertEquals("keep-me", instinct.data["install_session"])  // merge, not replace
        assertEquals("grid", canvas.data["layout"])
        assertFalse(f.exists())
    }

    @Test
    fun `failure at second file restores first file exactly`() {
        val instinct = FakePrefsFile(mapOf("priv" to "OLD", "other" to true))
        val canvas = FakePrefsFile(mapOf("layout" to "list"), failMerge = true)
        val stores = mapOf("instinct_widget" to instinct, "review_canvas" to canvas)
        val f = stagingFile(
            """{"instinct_widget": {"priv": "NEW"}, "review_canvas": {"layout": "grid"}}""")
        val err = StagedPrefsApplier(f) { stores.getValue(it) }.applyStaged()
        assertTrue(err!!.contains("commit failed for review_canvas"))
        // first file was written, then fully restored to prior content
        assertEquals(mapOf("priv" to "OLD", "other" to true), instinct.data)
        assertEquals(1, instinct.replaceCalls)
        // second file untouched
        assertEquals(mapOf("layout" to "list"), canvas.data)
        // staging file kept for the abort/retry path
        assertTrue(f.exists())
    }

    @Test
    fun `unexpected value type restores everything written so far`() {
        val instinct = FakePrefsFile(mapOf("priv" to "OLD"))
        val stores = mapOf("instinct_widget" to instinct)
        // filtered upstream, but a hand-built file must not write a boolean through
        val f = stagingFile("""{"instinct_widget": {"priv": true}}""")
        val err = StagedPrefsApplier(f) { stores.getValue(it) }.applyStaged()
        assertTrue(err!!.contains("unexpected value type"))
        assertEquals(mapOf("priv" to "OLD"), instinct.data)
        assertEquals(0, instinct.mergeCalls)
        assertTrue(f.exists())
    }

    @Test
    fun `malformed staging json touches nothing`() {
        val instinct = FakePrefsFile(mapOf("priv" to "OLD"))
        val f = stagingFile("{ not json")
        val err = StagedPrefsApplier(f) { instinct }.applyStaged()
        assertTrue(err!!.contains("unreadable staged settings"))
        assertEquals(mapOf("priv" to "OLD"), instinct.data)
        assertEquals(0, instinct.mergeCalls)
        assertTrue(f.exists())
    }

    @Test
    fun `restore failure is reported alongside the apply failure`() {
        val instinct = FakePrefsFile(mapOf("priv" to "OLD"))
        val canvas = FakePrefsFile(failMerge = true)
        val stores = mapOf("instinct_widget" to instinct, "review_canvas" to canvas)
        val f = stagingFile(
            """{"instinct_widget": {"priv": "NEW"}, "review_canvas": {"layout": "grid"}}""")
        instinct.failReplace = true
        val err = StagedPrefsApplier(f) { stores.getValue(it) }.applyStaged()!!
        assertTrue(err.contains("commit failed for review_canvas"))
        assertTrue(err.contains("prior settings restore also failed"))
        assertTrue(f.exists())
    }
}
