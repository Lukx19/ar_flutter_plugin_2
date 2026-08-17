package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

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
        lifecycle.handle(first, M0aControlCodec.encodeRequest(first))

        val conflict = request(M0aControlOperation.START, 0, 2).copy(
            controlRequestId = first.controlRequestId,
            flags = 1,
        )
        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(conflict, M0aControlCodec.encodeRequest(conflict)),
        )
        assertEquals(1, response.outcome)
        assertEquals(30, response.errorId)
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
