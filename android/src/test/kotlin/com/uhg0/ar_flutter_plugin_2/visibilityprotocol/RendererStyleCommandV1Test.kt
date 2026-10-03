package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererStyleCommandV1Test {
    @Test
    fun `renderer style page matches the checked in cross language vector`() {
        val fixture = fixture()
        val pageBytes = hex(fixture.getValue("pageHex").jsonPrimitive.content)
        val page = RendererStyleCommandV1.decode(pageBytes)

        assertEquals(fixture.getValue("bindingGeneration").jsonPrimitive.long, page.bindingGeneration)
        assertEquals(fixture.getValue("groupGeneration").jsonPrimitive.long, page.groupGeneration)
        assertEquals(fixture.getValue("transactionId").jsonPrimitive.long, page.transactionId)
        assertEquals(fixture.getValue("geometryRevision").jsonPrimitive.long, page.geometryRevision)
        assertEquals(fixture.getValue("lineageRevision").jsonPrimitive.long, page.lineageRevision)
        assertEquals(fixture.getValue("semanticRevision").jsonPrimitive.long, page.semanticRevision)
        assertEquals(fixture.getValue("coverageRevision").jsonPrimitive.long, page.coverageRevision)
        assertEquals(fixture.getValue("styleRevision").jsonPrimitive.long, page.styleRevision)
        assertEquals(fixture.getValue("residencyRevision").jsonPrimitive.long, page.residencyRevision)
        assertEquals(fixture.getValue("targetRevision").jsonPrimitive.long, page.targetRevision)
        assertEquals(fixture.getValue("reset").jsonPrimitive.boolean, page.reset)
        assertEquals(fixture.getValue("targetSurfaceId").jsonPrimitive.long, page.targetSurfaceId)
        assertEquals(fixture.getValue("targetDirectionIndex").jsonPrimitive.int, page.targetDirectionIndex)
        assertArrayEquals(hex(fixture.getValue("captureGroupIdHex").jsonPrimitive.content), page.captureGroupId)
        assertArrayEquals(hex(fixture.getValue("styleRowsHex").jsonPrimitive.content), page.styleRows)
        assertArrayEquals(hex(fixture.getValue("canonicalDigestHex").jsonPrimitive.content), page.completeDigest)

        assertArrayEquals(pageBytes, RendererStyleCommandV1.encode(page))
    }

    @Test
    fun `renderer style decoder rejects reserved header bytes`() {
        val bytes = hex(fixture().getValue("pageHex").jsonPrimitive.content)

        assertThrows(IllegalArgumentException::class.java) {
            RendererStyleCommandV1.decode(bytes.copyOf().also { it[132] = 1 })
        }
    }

    @Test
    fun `renderer style staging rejects a corrupted complete digest`() {
        val bytes = hex(fixture().getValue("pageHex").jsonPrimitive.content)
        val staging = RendererStyleCommandStagingV1(ByteArray(16))

        assertThrows(IllegalArgumentException::class.java) {
            staging.accept(RendererStyleCommandV1.decode(bytes.copyOf().also {
                it[144] = (it[144].toInt() xor 1).toByte()
            }))
        }
        assertEquals(0, staging.stagedCutCount())
    }

    @Test
    fun `kind five pages round trip one complete cut with stable digest`() {
        val cut = RendererStyleCutPayloadV1(
            captureGroupId = ByteArray(16) { (it + 1).toByte() },
            bindingGeneration = 5,
            groupGeneration = 7,
            transactionId = 11,
            geometryRevision = 13,
            lineageRevision = 17,
            semanticRevision = 19,
            coverageRevision = 23,
            styleRevision = 29,
            residencyRevision = 31,
            targetRevision = 37,
            reset = true,
            surfaceIds = longArrayOf(3, 9),
            styleRows = ByteArray(32) { it.toByte() },
            targetSurfaceId = 9,
            targetDirectionIndex = 4,
        )

        val pages = RendererStyleCommandV1.encodePages(cut, maxPageBytes = 200)

        assertTrue(pages.size > 1)
        val decoded = pages.map(RendererStyleCommandV1::decode)
        assertEquals(5, decoded.first().kind)
        assertEquals(1, decoded.first().version)
        assertEquals(0, decoded.first().pageIndex)
        assertFalse(decoded.first().isFinal)
        assertTrue(decoded.last().isFinal)
        assertEquals(cut.styleRevision, decoded.last().styleRevision)
        decoded.forEach { page ->
            assertTrue(page.bytes.size <= 200)
            assertArrayEquals(decoded.first().completeDigest, page.completeDigest)
        }
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(cut.canonicalBytes()),
            decoded.first().completeDigest,
        )
    }

    @Test
    fun `complete cut canonical bytes are independent of page sizing`() {
        val cut = RendererStyleCutPayloadV1(
            captureGroupId = ByteArray(16) { (it + 4).toByte() },
            bindingGeneration = 6,
            groupGeneration = 1,
            transactionId = 2,
            geometryRevision = 3,
            lineageRevision = 4,
            semanticRevision = 5,
            coverageRevision = 6,
            styleRevision = 7,
            residencyRevision = 8,
            targetRevision = 9,
            reset = false,
            surfaceIds = longArrayOf(1, 2, 8),
            styleRows = ByteArray(48) { (it * 3).toByte() },
        )

        val first = RendererStyleCommandV1.decode(
            RendererStyleCommandV1.encodePages(cut, maxPageBytes = 200).first(),
        ).completeDigest
        val second = RendererStyleCommandV1.decode(
            RendererStyleCommandV1.encodePages(cut, maxPageBytes = 224).first(),
        ).completeDigest

        assertArrayEquals(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decoder rejects non ascending stable ids`() {
        val cut = RendererStyleCutPayloadV1(
            captureGroupId = ByteArray(16),
            bindingGeneration = 1,
            groupGeneration = 1,
            transactionId = 1,
            geometryRevision = 1,
            lineageRevision = 1,
            semanticRevision = 1,
            coverageRevision = 1,
            styleRevision = 1,
            residencyRevision = 1,
            targetRevision = 1,
            reset = true,
            surfaceIds = longArrayOf(2, 1),
            styleRows = ByteArray(32),
        )
        RendererStyleCommandV1.encodePages(cut)
    }

    @Test
    fun `partial cut expires before accepting a late continuation and can restart`() {
        var nowNanos = 0L
        var expiryTask: (() -> Unit)? = null
        val staging = RendererStyleCommandStagingV1(
            bindingIdentity = ByteArray(16) { 7 },
            maximumRows = 2,
            clockNanos = { nowNanos },
            timeoutNanos = 10L,
            scheduleExpiry = { delayMillis, task ->
                assertEquals(1L, delayMillis)
                expiryTask = task
                TimeoutHandle { expiryTask = null }
            },
        )
        val pages = twoPageCutPages()

        assertTrue(staging.accept(pages.first()) is RendererStyleCommandStagingV1.Result.Progress)
        assertEquals(1, staging.stagedCutCount())
        assertEquals(2, staging.stagedRowCapacity())
        assertEquals(1, staging.stagedPageCount())

        nowNanos = 10L
        checkNotNull(expiryTask).invoke()
        assertEquals(0, staging.stagedCutCount())
        assertThrows(IllegalArgumentException::class.java) {
            staging.accept(pages.last())
        }
        assertEquals(0, staging.stagedCutCount())
        assertEquals(0, staging.stagedRowCapacity())
        assertEquals(0, staging.stagedPageCount())

        assertTrue(staging.canAccept(pages.first(), committedStyleRevision = 0L))
        assertTrue(staging.accept(pages.first()) is RendererStyleCommandStagingV1.Result.Progress)
    }

    @Test
    fun `admission drops an expired partial cut before checking continuation`() {
        var nowNanos = 0L
        val staging = RendererStyleCommandStagingV1(
            bindingIdentity = ByteArray(16) { 9 },
            maximumRows = 2,
            clockNanos = { nowNanos },
            timeoutNanos = 10L,
        )
        val pages = twoPageCutPages()

        staging.accept(pages.first())
        nowNanos = 10L

        assertFalse(staging.canAccept(pages.last(), committedStyleRevision = 0L))
        assertEquals(0, staging.stagedCutCount())
        assertTrue(staging.canAccept(pages.first(), committedStyleRevision = 0L))
    }

    @Test
    fun `completed cut owns rows across input mutation failed staging and retry`() {
        val staging = RendererStyleCommandStagingV1(ByteArray(16) { 7 }, maximumRows = 2)
        val pages = twoPageCutPages()
        staging.accept(pages.first())
        val first = (staging.accept(pages.last()) as RendererStyleCommandStagingV1.Result.Complete).cut
        val expectedIds = first.surfaceIds.copyOf()
        val expectedStyles = first.styleRows.copyOf()

        pages.forEach { page ->
            page.surfaceIds.fill(99L)
            page.styleRows.fill(99.toByte())
        }
        val next = first.copy(
            styleRevision = first.styleRevision + 1L,
            surfaceIds = longArrayOf(30L, 40L),
            styleRows = ByteArray(32) { (it + 32).toByte() },
        )
        val nextBytes = RendererStyleCommandV1.encodePages(
            next,
            maxPageBytes = RendererStyleCommandV1.HEADER_BYTES + RendererStyleCommandV1.RECORD_BYTES,
        )
        val corrupted = nextBytes.map(RendererStyleCommandV1::decode)
        staging.accept(corrupted.first())
        corrupted.last().styleRows[0] = 0
        assertThrows(IllegalArgumentException::class.java) { staging.accept(corrupted.last()) }
        assertEquals(0, staging.stagedCutCount())

        val retry = nextBytes.map(RendererStyleCommandV1::decode)
        staging.accept(retry.first())
        val second = (staging.accept(retry.last()) as RendererStyleCommandStagingV1.Result.Complete).cut
        assertArrayEquals(next.surfaceIds, second.surfaceIds)
        assertArrayEquals(next.styleRows, second.styleRows)
        assertArrayEquals(expectedIds, first.surfaceIds)
        assertArrayEquals(expectedStyles, first.styleRows)
        assertEquals(0, staging.stagedCutCount())
    }

    private fun twoPageCutPages(): List<RendererStyleCommandV1.Page> {
        val cut = RendererStyleCutPayloadV1(
            captureGroupId = ByteArray(16) { (it + 1).toByte() },
            bindingGeneration = 1,
            groupGeneration = 1,
            transactionId = 1,
            geometryRevision = 1,
            lineageRevision = 1,
            semanticRevision = 1,
            coverageRevision = 1,
            styleRevision = 1,
            residencyRevision = 1,
            targetRevision = 1,
            reset = false,
            surfaceIds = longArrayOf(10, 20),
            styleRows = ByteArray(32) { it.toByte() },
        )
        return RendererStyleCommandV1.encodePages(
            cut,
            maxPageBytes = RendererStyleCommandV1.HEADER_BYTES + RendererStyleCommandV1.RECORD_BYTES,
        ).map(RendererStyleCommandV1::decode)
    }

    private fun fixture(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("renderer_style_command_v1.json"))
            .readBytes()
            .decodeToString(),
    ).jsonObject

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

}
