package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibilityGridDirectorySyncTest {
    @Test
    fun `directory durability selects Android host and Windows backends exactly`() {
        assertEquals(
            VisibilityGridDirectorySync.Backend.ANDROID,
            VisibilityGridDirectorySync.backend(
                runtimeName = "Android Runtime",
                vmName = "ART",
                osName = "Linux",
            ),
        )
        assertEquals(
            VisibilityGridDirectorySync.Backend.ANDROID,
            VisibilityGridDirectorySync.backend(
                runtimeName = "OpenJDK Runtime Environment",
                vmName = "Dalvik",
                osName = "Linux",
            ),
        )
        assertEquals(
            VisibilityGridDirectorySync.Backend.HOST_JVM,
            VisibilityGridDirectorySync.backend(
                runtimeName = "OpenJDK Runtime Environment",
                vmName = "OpenJDK 64-Bit Server VM",
                osName = "Linux",
            ),
        )
        assertEquals(
            VisibilityGridDirectorySync.Backend.WINDOWS_NO_OP,
            VisibilityGridDirectorySync.backend(
                runtimeName = "OpenJDK Runtime Environment",
                vmName = "OpenJDK 64-Bit Server VM",
                osName = "Windows 11",
            ),
        )
    }
}
