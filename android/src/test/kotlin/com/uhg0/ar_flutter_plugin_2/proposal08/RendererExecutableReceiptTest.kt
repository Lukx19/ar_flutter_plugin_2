package com.uhg0.ar_flutter_plugin_2.proposal08

import com.uhg0.ar_flutter_plugin_2.proposal08.*

import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererExecutableReceiptTest {
    @Test
    fun `T5 native renderer campaign emits an immutable executable receipt`() {
        RendererT5ReceiptCampaign.execute()
    }

    @Test
    fun `T5 rejects missing malformed and non-immutable source locks`() {
        val missing = runCatching { RendererT5ReceiptCampaign.explicitSourceLock(null) }
        assertTrue(missing.exceptionOrNull() is IllegalArgumentException)

        val malformed = File.createTempFile("coverage-renderer-malformed", ".json")
        try {
            malformed.writeText("{\"format\":\"proposal08-coverage-renderer-source-pins-v1\",\"parentCommit\":\"bad\",\"pluginCommit\":\"also-bad\"}")
            val invalid = runCatching { RendererT5ReceiptCampaign.explicitSourceLock(malformed.absolutePath) }
            assertTrue(invalid.exceptionOrNull() is IllegalArgumentException)
        } finally {
            malformed.delete()
        }
    }

    @Test
    fun `T5 preserves exact explicit execution source provenance`() {
        val lock = File.createTempFile("coverage-renderer-source", ".json")
        try {
            lock.writeText("{\"format\":\"proposal08-coverage-renderer-source-pins-v1\",\"parentCommit\":\"0123456789abcdef0123456789abcdef01234567\",\"pluginCommit\":\"89abcdef0123456789abcdef0123456789abcdef\"}")
            val parsed = RendererT5ReceiptCampaign.explicitSourceLock(lock.absolutePath)
            assertEquals("0123456789abcdef0123456789abcdef01234567", parsed["parentCommit"]?.jsonPrimitive?.content)
            assertEquals("89abcdef0123456789abcdef0123456789abcdef", parsed["pluginCommit"]?.jsonPrimitive?.content)
        } finally {
            lock.delete()
        }
    }
}
