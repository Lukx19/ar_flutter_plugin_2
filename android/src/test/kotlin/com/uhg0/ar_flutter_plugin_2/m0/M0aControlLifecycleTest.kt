package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class M0aControlLifecycleTest {
    @Test
    fun `control lifecycle allocates fresh token and exact replay`() {
        val lifecycle = M0aControlLifecycle()
        val start = request(M0aControlOperation.START, 0, 1)
        val encoded = M0aControlCodec.encodeRequest(start)
        val first = lifecycle.handle(start, encoded)
        val replay = lifecycle.handle(start, encoded)

        assertArrayEquals(first, replay)
        assertEquals(M0aControlLifecycle.State.ACTIVE, lifecycle.state())
        assertEquals(1L, M0aControlCodec.decodeResponse(first).streamToken)
    }

    @Test
    fun `start receipt carries the authoritative restored committed baseline`() {
        val lifecycle = M0aControlLifecycle(
            initialCommittedBaseline = M0aCommittedBaselineV1(9, 10, 11, 12),
        )
        val start = request(M0aControlOperation.START, 0, 1)
        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(start, M0aControlCodec.encodeRequest(start)),
        )
        assertEquals(0L, response.nativeTransactionId)
        assertEquals(M0aStartResultCodecV2.byteLength, response.payload.size)
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10L, result.getLong(96))
        assertEquals(11L, result.getLong(104))
        assertEquals(12L, result.getLong(136))
        assertEquals(0L, result.getLong(176))
    }

    @Test
    fun `factory authority survives a replaced view binding`() {
        val authority = M0aCommittedBaselineAuthority(
            M0aCommittedBaselineV1(7, 8, 9, 10),
        )
        val firstLifecycle = M0aControlLifecycle(
            committedBaselineAuthority = authority,
        )
        val firstStart = request(M0aControlOperation.START, 0, 1)
        firstLifecycle.handle(firstStart, M0aControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(M0aCommittedBaselineV1(41, 42, 43, 44))

        val replacementLifecycle = M0aControlLifecycle(
            committedBaselineAuthority = authority,
        )
        val replacementStart = request(M0aControlOperation.START, 0, 60)
        val response = M0aControlCodec.decodeResponse(
            replacementLifecycle.handle(
                replacementStart,
                M0aControlCodec.encodeRequest(replacementStart),
            ),
        )
        assertEquals(184, response.payload.size)
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42L, result.getLong(96))
        assertEquals(43L, result.getLong(104))
        assertEquals(44L, result.getLong(136))
        assertEquals(M0aCommittedBaselineV1(41, 42, 43, 44), authority.snapshot())
    }

    @Test
    fun `checkpoint and stop require active token and stale token is fenced`() {
        val lifecycle = M0aControlLifecycle()
        val start = request(M0aControlOperation.START, 0, 1)
        lifecycle.handle(start, M0aControlCodec.encodeRequest(start))

        val stale = request(M0aControlOperation.BEGIN_CHECKPOINT, 2, 2)
        val staleResponse = M0aControlCodec.decodeResponse(
            lifecycle.handle(stale, M0aControlCodec.encodeRequest(stale)),
        )
        assertEquals(1, staleResponse.outcome)
        assertEquals(4, staleResponse.errorId)

        val stop = request(M0aControlOperation.STOP, 1, 3)
        val stopResponse = M0aControlCodec.decodeResponse(
            lifecycle.handle(stop, M0aControlCodec.encodeRequest(stop)),
        )
        assertEquals(0, stopResponse.outcome)
        assertEquals(M0aControlLifecycle.State.STOPPED, lifecycle.state())
    }

    @Test
    fun `same control id with changed bytes is a replay conflict`() {
        val lifecycle = M0aControlLifecycle()
        val first = request(M0aControlOperation.START, 0, 1)
        val firstBytes = M0aControlCodec.encodeRequest(first)
        val firstResponse = lifecycle.handle(first, firstBytes)

        val checkpoint = request(M0aControlOperation.BEGIN_CHECKPOINT, 1, 2)
        lifecycle.handle(checkpoint, M0aControlCodec.encodeRequest(checkpoint))

        val conflict = request(M0aControlOperation.START, 0, 2).copy(
            controlRequestId = first.controlRequestId,
            flags = 1,
        )
        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(conflict, M0aControlCodec.encodeRequest(conflict)),
        )
        assertEquals(1, response.outcome)
        assertEquals(30, response.errorId)
        assertArrayEquals(firstResponse, lifecycle.handle(first, firstBytes))
    }

    @Test
    fun `control receipt storage is bounded to four entries`() {
        val lifecycle = M0aControlLifecycle()
        val start = request(M0aControlOperation.START, 0, 1)
        lifecycle.handle(start, M0aControlCodec.encodeRequest(start))

        repeat(4) { offset ->
            val checkpoint = request(
                M0aControlOperation.BEGIN_CHECKPOINT,
                1,
                offset + 2,
            )
            lifecycle.handle(checkpoint, M0aControlCodec.encodeRequest(checkpoint))
        }

        val latest = request(M0aControlOperation.BEGIN_CHECKPOINT, 1, 5)
        val latestBytes = M0aControlCodec.encodeRequest(latest)
        val latestResponse = lifecycle.handle(latest, latestBytes)
        assertEquals(4 * latestBytes.size, lifecycle.cachedRequestBytes())
        assertEquals(4 * latestResponse.size, lifecycle.cachedResponseBytes())
    }

    private fun request(
        operation: M0aControlOperation,
        streamToken: Long,
        seed: Int,
    ): M0aControlRequest {
        val id = uuid(seed)
        return M0aControlRequest(
            operation = operation,
            flags = 0,
            controlRequestId = id,
            sessionId = uuid(20),
            captureGroupId = uuid(40),
            sessionGeneration = 1,
            groupGeneration = 1,
            coverageEpoch = 1,
            streamToken = streamToken,
        )
    }

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }
}
