package com.uhg0.ar_flutter_plugin_2.sessionlog

import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SessionLogFileProviderManifestTest {
    @Test
    fun `the session log share provider is declared as the plugin's own class`() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = manifest.getElementsByTagName("provider")
        val android = "http://schemas.android.com/apk/res/android"
        val names = (0 until providers.length).map {
            (providers.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name")
        }
        assertEquals(listOf(SessionLogFileProvider::class.java.name), names)
        assertTrue(FileProvider::class.java.isAssignableFrom(SessionLogFileProvider::class.java))
    }
}
