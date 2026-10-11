package com.uhg0.ar_flutter_plugin_2.sessionlog

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** System listener and share checks of the native session log. */
class NativeSessionLogSystemTest {
    @Suppress("DEPRECATION")
    @Test
    fun `backgrounding trim levels are no memory pressure`() {
        val records = withNativeSessionLog {
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        }
        assertTrue(records.isEmpty())
        val pressure = withNativeSessionLog {
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            NativeSessionLogEmitter.recordTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        }.full("MEMORY_PRESSURE")
        assertEquals(listOf("warning", "warning", "warning", "info"), pressure.map { it[2] })
    }

    @Test
    fun `only files inside the export folder may be shared`() {
        val root = File("cache", "session_log_export").path
        assertTrue(NativeSessionLogEmitter.isInsideExportRoot(root, File(root, "log.jsonl").path))
        assertFalse(
            NativeSessionLogEmitter.isInsideExportRoot(root, File(File("cache", "session_log_export_other"), "log.jsonl").path),
        )
        assertFalse(NativeSessionLogEmitter.isInsideExportRoot(root, root))
    }
}
