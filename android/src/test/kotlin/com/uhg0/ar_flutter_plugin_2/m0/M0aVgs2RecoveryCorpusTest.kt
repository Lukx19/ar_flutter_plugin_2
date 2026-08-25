package com.uhg0.ar_flutter_plugin_2.m0

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class M0aVgs2RecoveryCorpusTest {
    @Test
    fun `Issue 98 production runner executes every SHA locked VGS2 policy case`() {
        val bytes = fixture()
        val receipt = M0aVgs2RecoveryCorpus.run(bytes)
        assertEquals(5, receipt.policyCases)
        assertEquals(1, receipt.freshBindingCases)
    }

    @Test
    fun `Issue 98 corpus rejects missing unknown and mutated expected fields`() {
        val source = fixture().decodeToString()
        listOf(
            source.replaceFirst("\"observedValue\":40", "\"observedValue\":41"),
            source.replaceFirst("\"fieldId\":4", "\"fieldId\":9"),
            source.replaceFirst("\"scope\":1", "\"unknown\":0,\"scope\":1"),
            source.replaceFirst("\"scope\":1,", ""),
        ).forEach { mutation ->
            val bytes = mutation.encodeToByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                M0aVgs2RecoveryCorpus.run(bytes, sha256(bytes))
            }
        }
    }

    private fun fixture() = File("../../../docs/m1/issue98_vgs2_recovery_corpus_v1.json").readBytes()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
