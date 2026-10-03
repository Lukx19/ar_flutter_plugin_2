package com.uhg0.ar_flutter_plugin_2.capture

import com.uhg0.ar_flutter_plugin_2.util.toLowercaseHex
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.hashIdentity
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CaptureHashHexTest {
    @Test fun sha256VectorsPreserveDurableObjectNames() {
        val digest = MessageDigest.getInstance("SHA-256")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest.digest(byteArrayOf()).toCaptureHashHex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.digest("abc".toByteArray(Charsets.UTF_8)).toCaptureHashHex())
    }

    @Test fun signedNativeBytesAndUnsignedProtocolBytesMatchAllValues() {
        val native = ByteArray(256) { it.toByte() }
        val original = native.copyOf()
        val expected = (0..255).joinToString("") { "%02x".format(it) }
        assertEquals(expected, native.toLowercaseHex())
        assertEquals(expected, hashIdentity(native))
        assertEquals(expected, native.toCaptureHashHex())
        assertEquals(expected, (0..255).toList().toCaptureHashHex())
        assertArrayEquals(original, native)
        assertEquals("", byteArrayOf().toLowercaseHex())
        assertEquals("", hashIdentity(byteArrayOf()))
        assertEquals("", byteArrayOf().toCaptureHashHex())
        assertEquals("", emptyList<Int>().toCaptureHashHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidProtocolDigestFailsClosed() {
        listOf(0, 256).toCaptureHashHex()
    }
}
