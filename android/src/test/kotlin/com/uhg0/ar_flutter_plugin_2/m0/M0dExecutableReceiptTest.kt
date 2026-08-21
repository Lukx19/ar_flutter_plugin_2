package com.uhg0.ar_flutter_plugin_2.m0

import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M0dExecutableReceiptTest {
    @Test
    fun `T5 native renderer campaign emits an immutable executable receipt`() {
        M0dT5ReceiptCampaign.execute()
    }

    @Test
    fun `T5 rejects missing malformed and non-immutable source locks`() {
        val missing = runCatching { M0dT5ReceiptCampaign.explicitSourceLock(null) }
        assertTrue(missing.exceptionOrNull() is IllegalArgumentException)

        val malformed = File.createTempFile("m0d-malformed", ".json")
        try {
            malformed.writeText("{\"format\":\"proposal08-m0d-source-pins-v1\",\"parentCommit\":\"bad\",\"pluginCommit\":\"also-bad\"}")
            val invalid = runCatching { M0dT5ReceiptCampaign.explicitSourceLock(malformed.absolutePath) }
            assertTrue(invalid.exceptionOrNull() is IllegalArgumentException)
        } finally {
            malformed.delete()
        }
    }

    @Test
    fun `T5 preserves exact explicit execution source provenance`() {
        val lock = File.createTempFile("m0d-source", ".json")
        try {
            lock.writeText("{\"format\":\"proposal08-m0d-source-pins-v1\",\"parentCommit\":\"0123456789abcdef0123456789abcdef01234567\",\"pluginCommit\":\"89abcdef0123456789abcdef0123456789abcdef\"}")
            val parsed = M0dT5ReceiptCampaign.explicitSourceLock(lock.absolutePath)
            assertEquals("0123456789abcdef0123456789abcdef01234567", parsed["parentCommit"]?.jsonPrimitive?.content)
            assertEquals("89abcdef0123456789abcdef0123456789abcdef", parsed["pluginCommit"]?.jsonPrimitive?.content)
        } finally {
            lock.delete()
        }
    }
}
