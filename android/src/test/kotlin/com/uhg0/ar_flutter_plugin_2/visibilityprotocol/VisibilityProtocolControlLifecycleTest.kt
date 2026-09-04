package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import com.uhg0.ar_flutter_plugin_2.proposal08.*

import com.uhg0.ar_flutter_plugin_2.proposal08.*

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class VisibilityProtocolControlLifecycleTest {
    @Test
    fun `canonical malformed error records no receipt and corrected control remains legal`() {
        val lifecycle = ControlLifecycle()
        val start = request(ControlOperation.START, 0, 71)
        val errorBytes = lifecycle.malformed(
            start,
            ControlValidationFailure(
                errorId = 6,
                validationPhase = 2,
                fieldId = 4,
                expectedValue = 11,
                observedValue = 12,
            ),
        )
        val error = ControlCodec.decodeResponse(errorBytes)
        val detail = ControlCodec.decodeErrorDetail(error.payload)
        assertEquals(1, error.outcome)
        assertEquals(0, error.resultFlags)
        assertEquals(0, detail.disposition)
        assertEquals(5, detail.recoveryAction)
        assertEquals(ControlLifecycle.State.IDLE, lifecycle.state())
        assertEquals(0, lifecycle.cachedRequestBytes())
        assertEquals(0, lifecycle.cachedResponseBytes())

        val corrected = ControlCodec.decodeResponse(
            lifecycle.handle(start, ControlCodec.encodeRequest(start)),
        )
        assertEquals(0, corrected.outcome)
        assertEquals(ControlLifecycle.State.ACTIVE, lifecycle.state())
    }

    @Test
    fun `control lifecycle allocates fresh token and exact replay`() {
        val lifecycle = ControlLifecycle()
        val start = request(ControlOperation.START, 0, 1)
        val encoded = ControlCodec.encodeRequest(start)
        val first = lifecycle.handle(start, encoded)
        val replay = lifecycle.handle(start, encoded)

        assertArrayEquals(first, replay)
        assertEquals(ControlLifecycle.State.ACTIVE, lifecycle.state())
        assertEquals(1L, ControlCodec.decodeResponse(first).streamToken)
    }

    @Test
    fun `start negotiates frozen minor and ignores unsupported desired bits`() {
        val lifecycle = ControlLifecycle()
        val desired = request(ControlOperation.START, 0, 2).copy(
            payload = startPayload {
                putLong(16, 1L shl 4)
                putShort(32, 32)
            },
        )
        val response = ControlCodec.decodeResponse(
            lifecycle.handle(desired, ControlCodec.encodeRequest(desired)),
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

        val mandatoryLifecycle = ControlLifecycle()
        val mandatory = request(ControlOperation.START, 0, 6).copy(
            payload = startPayload {
                putLong(8, StartRequestCodecV2.supportedCapabilities)
            },
        )
        val mandatoryResponse = ControlCodec.decodeResponse(
            mandatoryLifecycle.handle(mandatory, ControlCodec.encodeRequest(mandatory)),
        )
        val mandatoryResult = ByteBuffer.wrap(mandatoryResponse.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x107L, mandatoryResult.getLong(8))
        assertEquals(0x107L, mandatoryResult.getLong(16))
    }

    @Test
    fun `start rejects unsupported required capability and minor range`() {
        val capabilityLifecycle = ControlLifecycle()
        val required = request(ControlOperation.START, 0, 3).copy(
            payload = startPayload {
                putLong(8, 1L shl 4)
            },
        )
        val capabilityResponse = ControlCodec.decodeResponse(
            capabilityLifecycle.handle(required, ControlCodec.encodeRequest(required)),
        )
        assertEquals(1, capabilityResponse.outcome)
        assertEquals(46, capabilityResponse.errorId)
        assertEquals(ControlCodec.errorDetailBytes, capabilityResponse.payload.size)
        val capabilityDetail = ControlCodec.decodeErrorDetail(capabilityResponse.payload)
        assertEquals(capabilityResponse.errorId, capabilityDetail.errorId)
        assertEquals(0, capabilityDetail.disposition)
        assertEquals(6, capabilityDetail.validationPhase)
        assertEquals(5, capabilityDetail.recoveryAction)
        assertEquals(17, capabilityDetail.fieldId)
        assertEquals(0, capabilityResponse.resultFlags)
        assertEquals(0, capabilityLifecycle.cachedRequestBytes())

        val correctedRequired = required.copy(payload = StartRequestCodecV2.defaultPayload())
        val correctedCapabilityResponse = ControlCodec.decodeResponse(
            capabilityLifecycle.handle(
                correctedRequired,
                ControlCodec.encodeRequest(correctedRequired),
            ),
        )
        assertEquals(0, correctedCapabilityResponse.outcome)

        val minorLifecycle = ControlLifecycle()
        val minor = request(ControlOperation.START, 0, 4).copy(
            payload = startPayload {
                putShort(0, 1)
                putShort(2, 1)
            },
        )
        val minorResponse = ControlCodec.decodeResponse(
            minorLifecycle.handle(minor, ControlCodec.encodeRequest(minor)),
        )
        assertEquals(1, minorResponse.outcome)
        assertEquals(1, minorResponse.errorId)
        val minorDetail = ControlCodec.decodeErrorDetail(minorResponse.payload)
        assertEquals(0, minorDetail.disposition)
        assertEquals(6, minorDetail.validationPhase)
        assertEquals(5, minorDetail.recoveryAction)
        assertEquals(2, minorDetail.fieldId)
        assertEquals(0, minorResponse.resultFlags)
        assertEquals(0, minorLifecycle.cachedRequestBytes())

        val correctedMinor = minor.copy(payload = StartRequestCodecV2.defaultPayload())
        assertEquals(
            0,
            ControlCodec.decodeResponse(
                minorLifecycle.handle(correctedMinor, ControlCodec.encodeRequest(correctedMinor)),
            ).outcome,
        )
    }

    @Test
    fun `lifecycle and stale-token errors carry canonical retry fencing and receipt policy`() {
        val idle = ControlLifecycle()
        val premature = request(ControlOperation.BEGIN_CHECKPOINT, 1, 81)
        val prematureResponse = ControlCodec.decodeResponse(
            idle.handle(premature, ControlCodec.encodeRequest(premature)),
        )
        val prematureDetail = ControlCodec.decodeErrorDetail(prematureResponse.payload)
        assertEquals(48, prematureResponse.errorId)
        assertEquals(0, prematureResponse.resultFlags)
        assertEquals(0, prematureDetail.disposition)
        assertEquals(7, prematureDetail.validationPhase)
        assertEquals(5, prematureDetail.recoveryAction)
        assertEquals(0, idle.cachedRequestBytes())

        val corrected = premature.copy(
            operation = ControlOperation.START,
            streamToken = 0,
            payload = StartRequestCodecV2.defaultPayload(),
        )
        assertEquals(
            0,
            ControlCodec.decodeResponse(
                idle.handle(corrected, ControlCodec.encodeRequest(corrected)),
            ).outcome,
        )

        val active = ControlLifecycle()
        val start = request(ControlOperation.START, 0, 82)
        val startBytes = ControlCodec.encodeRequest(start)
        active.handle(start, startBytes)
        val cachedBeforeStale = active.cachedRequestBytes()
        val stale = request(ControlOperation.BEGIN_CHECKPOINT, 2, 83)
        val staleBytes = ControlCodec.encodeRequest(stale)
        val staleResponseBytes = active.handle(stale, staleBytes)
        val staleResponse = ControlCodec.decodeResponse(staleResponseBytes)
        val staleDetail = ControlCodec.decodeErrorDetail(staleResponse.payload)
        assertEquals(4, staleResponse.errorId)
        assertEquals(4, staleResponse.resultFlags)
        assertEquals(0, staleResponse.resultFlags and 1)
        assertEquals(2, staleDetail.disposition)
        assertEquals(4, staleDetail.validationPhase)
        assertEquals(4, staleDetail.recoveryAction)
        assertEquals(7, staleDetail.fieldId)
        assertEquals(cachedBeforeStale, active.cachedRequestBytes())
        assertArrayEquals(staleResponseBytes, active.handle(stale, staleBytes))
    }

    @Test
    fun `restored cut conflict is rejected rather than overlaid`() {
        val lifecycle = ControlLifecycle(
            initialCommittedBaseline = CommittedBaselineV1(7, 11, 13, 17),
        )
        val conflicting = request(ControlOperation.START, 0, 5).copy(
            payload = startPayload {
                put(7, 1)
                putLong(64, 12L)
                putLong(72, 13L)
                putLong(104, 17L)
                put(392, 1)
                put(424, 1)
            },
        )
        val response = ControlCodec.decodeResponse(
            lifecycle.handle(conflicting, ControlCodec.encodeRequest(conflicting)),
        )
        assertEquals(1, response.outcome)
        assertEquals(58, response.errorId)
        assertEquals(ControlLifecycle.State.IDLE, lifecycle.state())
    }

    @Test
    fun `restored cut requires matching roots and transform identity`() {
        val restoredPayload = restoredPayload()
        val restored = StartRequestCodecV2.decode(restoredPayload)
        val baseline = CommittedBaselineV1.fromRestoredConfiguration(restored)

        val exactLifecycle = ControlLifecycle(initialCommittedBaseline = baseline)
        val exact = request(ControlOperation.START, 0, 8).copy(payload = restoredPayload)
        val exactResponse = ControlCodec.decodeResponse(
            exactLifecycle.handle(exact, ControlCodec.encodeRequest(exact)),
        )
        assertEquals(0, exactResponse.outcome)

        val rootMismatch = restoredPayload.copyOf().also { it[392] = (it[392].toInt() xor 1).toByte() }
        val rootLifecycle = ControlLifecycle(initialCommittedBaseline = baseline)
        val rootRequest = request(ControlOperation.START, 0, 9).copy(payload = rootMismatch)
        val rootResponse = ControlCodec.decodeResponse(
            rootLifecycle.handle(rootRequest, ControlCodec.encodeRequest(rootRequest)),
        )
        assertEquals(1, rootResponse.outcome)
        assertEquals(58, rootResponse.errorId)

        val transformMismatch = restoredPayload.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply {
                putDouble(136, 2.0)
                putDouble(264, 0.5)
            }
        }
        val transformLifecycle = ControlLifecycle(initialCommittedBaseline = baseline)
        val transformRequest = request(ControlOperation.START, 0, 10).copy(payload = transformMismatch)
        val transformResponse = ControlCodec.decodeResponse(
            transformLifecycle.handle(transformRequest, ControlCodec.encodeRequest(transformRequest)),
        )
        assertEquals(1, transformResponse.outcome)
        assertEquals(58, transformResponse.errorId)
    }

    @Test
    fun `revision one restores canonical empty wire roots without changing authority`() {
        val baseline = CommittedBaselineV1(1, 1, 1, 0)
        val payload = startPayload {
            put(7, 1)
            putLong(64, 1L)
            putLong(72, 1L)
        }
        val lifecycle = ControlLifecycle(initialCommittedBaseline = baseline)
        val start = request(ControlOperation.START, 0, 11).copy(payload = payload)

        val response = ControlCodec.decodeResponse(
            lifecycle.handle(start, ControlCodec.encodeRequest(start)),
        )

        assertEquals(0, response.outcome)
        assertEquals(CommittedBaselineV1.forFreshBinding(baseline), lifecycle.committedBaseline())
    }

    @Test
    fun `start retains one complete cut with non-default configuration`() {
        val lifecycle = ControlLifecycle(
            initialCommittedBaseline = CommittedBaselineV1(
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
        val start = request(ControlOperation.START, 0, 7).copy(
            payload = startPayload {
                put(6, 1)
                putLong(8, StartRequestCodecV2.supportedCapabilities)
                putInt(24, 8192)
                putInt(28, 32768)
                putShort(34, 5)
                putInt(40, 12345)
                putInt(44, 23456)
            },
        )

        val response = ControlCodec.decodeResponse(
            lifecycle.handle(start, ControlCodec.encodeRequest(start)),
        )
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, result.get(4).toInt())
        assertEquals(0x107L, result.getLong(8))
        assertEquals(12345, result.getInt(24))
        assertEquals(23456, result.getInt(28))
        assertEquals(3, result.getInt(32))
        assertEquals(100_000, result.getInt(36))
        assertEquals(1024 * 1024, result.getInt(40))
        assertEquals(20_000, result.getInt(44))
        assertEquals(8_000, result.getInt(48))
        assertEquals(4_096, result.getInt(52))
        assertEquals(512, result.getInt(56))
        assertEquals(2_000, result.getInt(60))
        assertEquals(8192, result.getInt(64))
        assertEquals(32768, result.getInt(68))
        assertEquals(5, result.getShort(72).toInt())
        assertEquals(
            listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 1L, 0L),
            List(12) { index -> result.getLong(88 + index * 8) },
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(response.payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("c380108a95972701c99ea4a3e07676c3bec054bb96cf8123f8ea1c874d1ffb12", digest)
    }

    @Test
    fun `start receipt carries the authoritative restored committed baseline`() {
        val lifecycle = ControlLifecycle(
            initialCommittedBaseline = CommittedBaselineV1(9, 10, 11, 12),
        )
        val start = request(ControlOperation.START, 0, 1)
        val response = ControlCodec.decodeResponse(
            lifecycle.handle(start, ControlCodec.encodeRequest(start)),
        )
        assertEquals(0L, response.nativeTransactionId)
        assertEquals(StartResultCodecV2.byteLength, response.payload.size)
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10L, result.getLong(96))
        assertEquals(11L, result.getLong(104))
        assertEquals(12L, result.getLong(136))
        assertEquals(0L, result.getLong(176))
    }

    @Test
    fun `factory authority survives a replaced view binding`() {
        val authority = CommittedBaselineAuthority()
        val firstLifecycle = ControlLifecycle(
            CommittedBaselineAuthority = authority,
        )
        val firstStart = request(ControlOperation.START, 0, 1)
        firstLifecycle.handle(firstStart, ControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(CommittedBaselineV1(41, 42, 43, 44))

        val replacementLifecycle = ControlLifecycle(
            CommittedBaselineAuthority = authority,
        )
        val replacementStart = request(ControlOperation.START, 0, 60)
        val response = ControlCodec.decodeResponse(
            replacementLifecycle.handle(
                replacementStart,
                ControlCodec.encodeRequest(replacementStart),
            ),
        )
        assertEquals(184, response.payload.size)
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42L, result.getLong(96))
        assertEquals(43L, result.getLong(104))
        assertEquals(44L, result.getLong(136))
        assertEquals(
            CommittedBaselineV1(41, 42, 43, 44),
            authority.snapshot(CommittedBaselineScopeV1.from(firstStart)),
        )
    }

    @Test
    fun `factory authority retains restored roots and transform identity`() {
        val authority = CommittedBaselineAuthority()
        val restoredPayload = restoredPayload()
        val restored = StartRequestCodecV2.decode(restoredPayload)
        val baseline = CommittedBaselineV1.fromRestoredConfiguration(restored)
        val firstStart = request(ControlOperation.START, 0, 61).copy(payload = restoredPayload)
        val firstLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        firstLifecycle.handle(firstStart, ControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(baseline)

        val exactStart = request(ControlOperation.START, 0, 62).copy(payload = restoredPayload)
        val exactLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        val exactResponse = ControlCodec.decodeResponse(
            exactLifecycle.handle(exactStart, ControlCodec.encodeRequest(exactStart)),
        )
        assertEquals(0, exactResponse.outcome)
        assertEquals(baseline, exactLifecycle.committedBaseline())

        val mismatchedPayload = restoredPayload.copyOf().also { it[424] = (it[424].toInt() xor 1).toByte() }
        val mismatchStart = request(ControlOperation.START, 0, 63).copy(payload = mismatchedPayload)
        val mismatchLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        val mismatchResponse = ControlCodec.decodeResponse(
            mismatchLifecycle.handle(mismatchStart, ControlCodec.encodeRequest(mismatchStart)),
        )
        assertEquals(1, mismatchResponse.outcome)
        assertEquals(58, mismatchResponse.errorId)
    }

    @Test
    fun `baseline authority cannot cross group epochs`() {
        val authority = CommittedBaselineAuthority()
        val firstLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        val firstStart = request(ControlOperation.START, 0, 70)
        firstLifecycle.handle(firstStart, ControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(CommittedBaselineV1(51, 52, 53, 54))

        val otherGroup = request(ControlOperation.START, 0, 71).copy(
            captureGroupId = uuid(90),
        )
        val otherLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        val response = ControlCodec.decodeResponse(
            otherLifecycle.handle(otherGroup, ControlCodec.encodeRequest(otherGroup)),
        )
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, result.getLong(96))
        assertEquals(0L, result.getLong(104))
        assertEquals(0L, result.getLong(136))
    }

    @Test
    fun `baseline authority survives same group replacement with a new coverage epoch`() {
        val authority = CommittedBaselineAuthority()
        val firstLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        val firstStart = request(ControlOperation.START, 0, 72)
        firstLifecycle.handle(firstStart, ControlCodec.encodeRequest(firstStart))
        firstLifecycle.setCommittedBaseline(CommittedBaselineV1(61, 62, 63, 64))

        val replacement = request(ControlOperation.START, 0, 73).copy(coverageEpoch = 2)
        val replacementLifecycle = ControlLifecycle(CommittedBaselineAuthority = authority)
        val response = ControlCodec.decodeResponse(
            replacementLifecycle.handle(replacement, ControlCodec.encodeRequest(replacement)),
        )
        val result = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(62L, result.getLong(96))
        assertEquals(63L, result.getLong(104))
        assertEquals(64L, result.getLong(136))
    }

    @Test
    fun `checkpoint and stop require active token and stale token is fenced`() {
        val lifecycle = ControlLifecycle()
        val start = request(ControlOperation.START, 0, 1)
        lifecycle.handle(start, ControlCodec.encodeRequest(start))

        val stale = request(ControlOperation.BEGIN_CHECKPOINT, 2, 2)
        val staleResponse = ControlCodec.decodeResponse(
            lifecycle.handle(stale, ControlCodec.encodeRequest(stale)),
        )
        assertEquals(1, staleResponse.outcome)
        assertEquals(4, staleResponse.errorId)

        val stop = request(ControlOperation.STOP, 1, 3)
        val stopResponse = ControlCodec.decodeResponse(
            lifecycle.handle(stop, ControlCodec.encodeRequest(stop)),
        )
        assertEquals(0, stopResponse.outcome)
        assertEquals(ControlLifecycle.State.STOPPED, lifecycle.state())
    }

    @Test
    fun `same control id with changed bytes is a replay conflict`() {
        val lifecycle = ControlLifecycle()
        val first = request(ControlOperation.START, 0, 1)
        val firstBytes = ControlCodec.encodeRequest(first)
        val firstResponse = lifecycle.handle(first, firstBytes)

        val checkpoint = request(ControlOperation.BEGIN_CHECKPOINT, 1, 2)
        lifecycle.handle(checkpoint, ControlCodec.encodeRequest(checkpoint))

        val conflict = request(ControlOperation.START, 0, 2).copy(
            controlRequestId = first.controlRequestId,
            flags = 1,
        )
        val response = ControlCodec.decodeResponse(
            lifecycle.handle(conflict, ControlCodec.encodeRequest(conflict)),
        )
        assertEquals(1, response.outcome)
        assertEquals(30, response.errorId)
        assertArrayEquals(firstResponse, lifecycle.handle(first, firstBytes))
    }

    @Test
    fun `control receipt storage is bounded to four entries`() {
        val lifecycle = ControlLifecycle()
        val start = request(ControlOperation.START, 0, 1)
        lifecycle.handle(start, ControlCodec.encodeRequest(start))

        repeat(4) { offset ->
            val checkpoint = request(
                ControlOperation.BEGIN_CHECKPOINT,
                1,
                offset + 2,
            )
            lifecycle.handle(checkpoint, ControlCodec.encodeRequest(checkpoint))
        }

        val latest = request(ControlOperation.BEGIN_CHECKPOINT, 1, 5)
        val latestBytes = ControlCodec.encodeRequest(latest)
        val latestResponse = lifecycle.handle(latest, latestBytes)
        assertEquals(4 * latestBytes.size, lifecycle.cachedRequestBytes())
        assertEquals(4 * latestResponse.size, lifecycle.cachedResponseBytes())
    }

    private fun request(
        operation: ControlOperation,
        streamToken: Long,
        seed: Int,
    ): ControlRequest {
        val id = uuid(seed)
        return ControlRequest(
            operation = operation,
            flags = 0,
            controlRequestId = id,
            sessionId = uuid(20),
            captureGroupId = uuid(40),
            sessionGeneration = 1,
            groupGeneration = 1,
            coverageEpoch = 1,
            streamToken = streamToken,
            payload = if (operation == ControlOperation.START) {
                StartRequestCodecV2.defaultPayload()
            } else {
                byteArrayOf()
            },
        )
    }

    private fun startPayload(mutate: ByteBuffer.() -> Unit): ByteArray =
        StartRequestCodecV2.defaultPayload().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply(mutate)
        }

    private fun restoredPayload(): ByteArray = startPayload {
        put(7, 1)
        putLong(8, StartRequestCodecV2.supportedCapabilities)
        repeat(10) { index -> putLong(56 + index * 8, 41L + index * 2) }
        repeat(32) { index -> put(392 + index, (index + 1).toByte()) }
        repeat(32) { index -> put(424 + index, (index + 33).toByte()) }
    }

    private fun uuid(seed: Int): Uuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return Uuid(bytes)
    }
}
