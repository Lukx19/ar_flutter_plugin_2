package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlOperation
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthSourceSelectionTest {
    private fun payload(source: Int): ByteArray = ByteArray(49).also {
        it[0] = 1
        it[16] = 2
        it[6] = 0x40
        it[8] = 0x80.toByte()
        it[22] = 0x40
        it[24] = 0x80.toByte()
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(32, 1).putLong(40, 2)
        it[48] = source.toByte()
    }

    @Test fun `explicit dense and raw choices override qualification fallback only in debug`() {
        for (fallback in listOf(false, true)) {
            for (choice in DepthSourcePreference.entries) {
                val raw = rawDepthCandidateForPreference(choice, true, fallback)
                val calls = ArrayList<String>()
                val mode = depthAcquisitionModeForSession(Config.DepthMode.AUTOMATIC, raw)
                val image = object : RawDepthImage {
                    override val width = 1
                    override val height = 1
                    override val timestampNs = 1L
                    override fun unsignedValue(x: Int, y: Int) = 255
                    override fun close() = Unit
                }
                val acquirer = selectedDepthAcquirer(mode,
                    acquireDense = { calls += "dense"; image },
                    acquireRaw = { calls += "raw"; image },
                    acquireRawConfidence = { calls += "confidence"; image })
                acquirer.acquireDepth()
                acquirer.acquireConfidence()
                assertEquals(if (choice == DepthSourcePreference.RAW) listOf("raw", "confidence") else listOf("dense"), calls)
                assertFalse(rawDepthCandidateForPreference(choice, false, fallback))
            }
            assertEquals(fallback, rawDepthCandidateForPreference(null, true, fallback))
        }
    }

    @Test fun `source companion rejects malformed choice generation and wrong START identity`() {
        for (bytes in listOf(payload(2), payload(255), payload(0).copyOf(48), payload(0).copyOf(50), payload(0).also { it.fill(0, 32, 40) })) {
            assertThrows(IllegalArgumentException::class.java) { DepthSourceSelection.decode(bytes) }
        }
        val choice = DepthSourceSelection.decode(payload(1))
        val request = ControlRequest(ControlOperation.START, 0, choice.sessionId, choice.sessionId,
            choice.captureGroupId, choice.sessionGeneration, choice.groupGeneration, 1, 0)
        assertTrue(choice.matches(request))
        assertFalse(choice.matches(request.copy(groupGeneration = 3)))
        assertFalse(choice.matches(request.copy(sessionGeneration = 2)))
        assertFalse(choice.matches(request.copy(captureGroupId = choice.sessionId)))
    }

    @Test fun `active source is immutable and an identical retry cannot change its group`() {
        val active = DepthSourceSelection.decode(payload(1))
        active.validateReplacement(active, false)
        assertThrows(IllegalStateException::class.java) {
            DepthSourceSelection.decode(payload(0)).validateReplacement(active, false)
        }
        assertThrows(IllegalStateException::class.java) {
            DepthSourceSelection.decode(payload(1).also { it[16] = 3 }).validateReplacement(active, false)
        }
        val transform = identityVisibilityGridTransform()
        val frame = VisibilityGroupFrame.copyOf(transform, transform, 100_000, 1000,
            depthSourcePreference = active.preference)
        transform[12] = 9.0
        assertEquals(0.0, frame.groupFromWorldGl[12], 0.0)
        assertEquals(DepthSourcePreference.RAW, frame.depthSourcePreference)
        DepthSourceSelection.decode(payload(0)).validateReplacement(active, true)
        assertEquals(DepthSourcePreference.RAW, frame.depthSourcePreference)
    }
}
