package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

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
    fun `start negotiates frozen minor and ignores unsupported desired bits`() {
        val lifecycle = M0aControlLifecycle()
        val desired = request(M0aControlOperation.START, 0, 2).copy(
            payload = startPayload {
                putLong(16, 1L shl 4)
                putShort(32, 32)
            },
        )
        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(desired, M0aControlCodec.encodeRequest(desired)),
        )
        assertEquals(0, response.outcome)
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, result.getLong(8))
        assertEquals(0x107L, result.getLong(16))
        assertEquals(1L, lifecycle.metrics.unsupportedDesiredCapabilityBits)
        assertEquals(32, response.diagnostic.size)
        val diagnostic = ByteBuffer.wrap(response.diagnostic).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, diagnostic.getShort(2).toInt())
        assertEquals(5, diagnostic.getShort(16).toInt())
        assertEquals(1L, diagnostic.getLong(24))

        repeat(1_200) {
            lifecycle.metrics.recordUnsupportedDesiredCapabilityBits(Long.MAX_VALUE)
        }
        assertEquals(0xffffL, lifecycle.metrics.unsupportedDesiredCapabilityBits)

        val mandatoryLifecycle = M0aControlLifecycle()
        val mandatory = request(M0aControlOperation.START, 0, 6).copy(
            payload = startPayload {
                putLong(8, M0aStartRequestCodecV2.supportedCapabilities)
            },
        )
        val mandatoryResponse = M0aControlCodec.decodeResponse(
            mandatoryLifecycle.handle(mandatory, M0aControlCodec.encodeRequest(mandatory)),
        )
        val mandatoryResult = ByteBuffer.wrap(mandatoryResponse.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x107L, mandatoryResult.getLong(8))
        assertEquals(0x107L, mandatoryResult.getLong(16))
    }

    @Test
    fun `start rejects unsupported required capability and minor range`() {
        val capabilityLifecycle = M0aControlLifecycle()
        val required = request(M0aControlOperation.START, 0, 3).copy(
            payload = startPayload {
                putLong(8, 1L shl 4)
            },
        )
        val capabilityResponse = M0aControlCodec.decodeResponse(
            capabilityLifecycle.handle(required, M0aControlCodec.encodeRequest(required)),
        )
        assertEquals(1, capabilityResponse.outcome)
        assertEquals(46, capabilityResponse.errorId)

        val minorLifecycle = M0aControlLifecycle()
        val minor = request(M0aControlOperation.START, 0, 4).copy(
            payload = startPayload {
                putShort(0, 1)
                putShort(2, 1)
            },
        )
        val minorResponse = M0aControlCodec.decodeResponse(
            minorLifecycle.handle(minor, M0aControlCodec.encodeRequest(minor)),
        )
        assertEquals(1, minorResponse.outcome)
        assertEquals(1, minorResponse.errorId)
    }

    @Test
    fun `restored cut conflict is rejected rather than overlaid`() {
        val lifecycle = M0aControlLifecycle(
            initialCommittedBaseline = M0aCommittedBaselineV1(7, 11, 13, 17),
        )
        val conflicting = request(M0aControlOperation.START, 0, 5).copy(
            payload = startPayload {
                put(7, 1)
                putLong(64, 12L)
                putLong(72, 13L)
                putLong(104, 17L)
                put(392, 1)
                put(424, 1)
            },
        )
        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(conflicting, M0aControlCodec.encodeRequest(conflicting)),
        )
        assertEquals(1, response.outcome)
        assertEquals(58, response.errorId)
        assertEquals(M0aControlLifecycle.State.IDLE, lifecycle.state())
    }

    @Test
    fun `restored cut requires matching roots and transform identity`() {
        val restoredPayload = restoredPayload()
        val restored = M0aStartRequestCodecV2.decode(restoredPayload)
        val baseline = M0aCommittedBaselineV1.fromRestoredConfiguration(restored)

        val exactLifecycle = M0aControlLifecycle(initialCommittedBaseline = baseline)
        val exact = request(M0aControlOperation.START, 0, 8).copy(payload = restoredPayload)
        val exactResponse = M0aControlCodec.decodeResponse(
            exactLifecycle.handle(exact, M0aControlCodec.encodeRequest(exact)),
        )
        assertEquals(0, exactResponse.outcome)

        val rootMismatch = restoredPayload.copyOf().also { it[392] = (it[392].toInt() xor 1).toByte() }
        val rootLifecycle = M0aControlLifecycle(initialCommittedBaseline = baseline)
        val rootRequest = request(M0aControlOperation.START, 0, 9).copy(payload = rootMismatch)
        val rootResponse = M0aControlCodec.decodeResponse(
            rootLifecycle.handle(rootRequest, M0aControlCodec.encodeRequest(rootRequest)),
        )
        assertEquals(1, rootResponse.outcome)
        assertEquals(58, rootResponse.errorId)

        val transformMismatch = restoredPayload.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putDouble(136, 2.0)
        }
        val transformLifecycle = M0aControlLifecycle(initialCommittedBaseline = baseline)
        val transformRequest = request(M0aControlOperation.START, 0, 10).copy(payload = transformMismatch)
        val transformResponse = M0aControlCodec.decodeResponse(
            transformLifecycle.handle(transformRequest, M0aControlCodec.encodeRequest(transformRequest)),
        )
        assertEquals(1, transformResponse.outcome)
        assertEquals(58, transformResponse.errorId)
    }

    @Test
    fun `start retains one complete cut with non-default configuration`() {
        val lifecycle = M0aControlLifecycle(
            initialCommittedBaseline = M0aCommittedBaselineV1(
                transactionId = 12,
                geometryRevision = 3,
                lineageRevision = 4,
                styleRevision = 8,
                evidenceRevision = 2,
                captureRevision = 5,
                coverageRevision = 6,
                producedStyleRevision = 7,
                regionManifestRevision = 9,
                schemaRootRevision = 10,
                nextSurfaceIdHighWater = 11,
            ),
        )
        val start = request(M0aControlOperation.START, 0, 7).copy(
            payload = startPayload {
                put(6, 1)
                putLong(8, M0aStartRequestCodecV2.supportedCapabilities)
                putInt(24, 8192)
                putInt(28, 32768)
                putShort(34, 5)
                putInt(40, 12345)
                putInt(44, 23456)
            },
        )

        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(start, M0aControlCodec.encodeRequest(start)),
        )
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, result.get(4).toInt())
        assertEquals(0x107L, result.getLong(8))
        assertEquals(12345, result.getInt(24))
        assertEquals(23456, result.getInt(28))
        assertEquals(8192, result.getInt(64))
        assertEquals(32768, result.getInt(68))
        assertEquals(5, result.getShort(72).toInt())
        assertEquals(
            listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 1L, 0L),
            List(12) { index -> result.getLong(88 + index * 8) },
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(response.payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("cd708be10b2af6f51f6beab2b4fde2d7eeb6034b8ec48f360accf102bfdd7138", digest)
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
        val authority = M0aCommittedBaselineAuthority()
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
        assertEquals(
            M0aCommittedBaselineV1(41, 42, 43, 44),
            authority.snapshot(M0aCommittedBaselineScopeV1.from(firstStart)),
        )
    }

    @Test
    fun `factory authority retains restored roots and transform identity`() {
        val authority = M0aCommittedBaselineAuthority()
        val restoredPayload = restoredPayload()
        val restored = M0aStartRequestCodecV2.decode(restoredPayload)
        val baseline = M0aCommittedBaselineV1.fromRestoredConfiguration(restored)
        val firstStart = request(M0aControlOperation.START, 0, 61).copy(payload = restoredPayload)
        val firstLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        firstLifecycle.handle(firstStart, M0aControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(baseline)

        val exactStart = request(M0aControlOperation.START, 0, 62).copy(payload = restoredPayload)
        val exactLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        val exactResponse = M0aControlCodec.decodeResponse(
            exactLifecycle.handle(exactStart, M0aControlCodec.encodeRequest(exactStart)),
        )
        assertEquals(0, exactResponse.outcome)
        assertEquals(baseline, exactLifecycle.committedBaseline())

        val mismatchedPayload = restoredPayload.copyOf().also { it[424] = (it[424].toInt() xor 1).toByte() }
        val mismatchStart = request(M0aControlOperation.START, 0, 63).copy(payload = mismatchedPayload)
        val mismatchLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        val mismatchResponse = M0aControlCodec.decodeResponse(
            mismatchLifecycle.handle(mismatchStart, M0aControlCodec.encodeRequest(mismatchStart)),
        )
        assertEquals(1, mismatchResponse.outcome)
        assertEquals(58, mismatchResponse.errorId)
    }

    @Test
    fun `baseline authority cannot cross group epochs`() {
        val authority = M0aCommittedBaselineAuthority()
        val firstLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        val firstStart = request(M0aControlOperation.START, 0, 70)
        firstLifecycle.handle(firstStart, M0aControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(M0aCommittedBaselineV1(51, 52, 53, 54))

        val otherGroup = request(M0aControlOperation.START, 0, 71).copy(
            captureGroupId = uuid(90),
        )
        val otherLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        val response = M0aControlCodec.decodeResponse(
            otherLifecycle.handle(otherGroup, M0aControlCodec.encodeRequest(otherGroup)),
        )
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, result.getLong(96))
        assertEquals(0L, result.getLong(104))
        assertEquals(0L, result.getLong(136))
    }

    @Test
    fun `baseline authority survives same group replacement with a new coverage epoch`() {
        val authority = M0aCommittedBaselineAuthority()
        val firstLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        val firstStart = request(M0aControlOperation.START, 0, 72)
        firstLifecycle.handle(firstStart, M0aControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(M0aCommittedBaselineV1(61, 62, 63, 64))

        val replacement = request(M0aControlOperation.START, 0, 73).copy(coverageEpoch = 2)
        val replacementLifecycle = M0aControlLifecycle(committedBaselineAuthority = authority)
        val response = M0aControlCodec.decodeResponse(
            replacementLifecycle.handle(replacement, M0aControlCodec.encodeRequest(replacement)),
        )
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(62L, result.getLong(96))
        assertEquals(63L, result.getLong(104))
        assertEquals(64L, result.getLong(136))
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
            payload = if (operation == M0aControlOperation.START) {
                M0aStartRequestCodecV2.defaultPayload()
            } else {
                byteArrayOf()
            },
        )
    }

    private fun startPayload(mutate: ByteBuffer.() -> Unit): ByteArray =
        M0aStartRequestCodecV2.defaultPayload().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply(mutate)
        }

    private fun restoredPayload(): ByteArray = startPayload {
        put(7, 1)
        putLong(8, M0aStartRequestCodecV2.supportedCapabilities)
        repeat(10) { index -> putLong(56 + index * 8, 41L + index * 2) }
        repeat(32) { index -> put(392 + index, (index + 1).toByte()) }
        repeat(32) { index -> put(424 + index, (index + 33).toByte()) }
    }

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }
}
