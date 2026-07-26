package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.ImageFormat
import android.util.Size
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageCacheManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cacheImageBytes returns stored jpeg bytes`() {
        val manager = createManager(maxCacheSize = 4)
        val payload = byteArrayOf(1, 2, 3, 4)

        val cached = manager.cacheImageBytes("img-1", payload, ImageFormat.JPEG)

        assertTrue(cached)
        assertArrayEquals(payload, manager.getImageData("img-1"))
    }

    @Test
    fun `saveImageToFile writes byte identical payload`() {
        val manager = createManager(maxCacheSize = 4)
        val payload = byteArrayOf(9, 8, 7, 6)
        manager.cacheImageBytes("img-1", payload, ImageFormat.JPEG)
        val destination = File(temporaryFolder.root, "cache/capture.jpg")

        val saved = manager.saveImageToFile(
            "img-1",
            destination.absolutePath,
            ImageFormat.JPEG,
        )

        assertTrue(saved)
        assertTrue(destination.exists())
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun `cache rejects new entry when max cache size is reached`() {
        val manager = createManager(maxCacheSize = 2)

        manager.cacheImageBytes("img-1", byteArrayOf(1), ImageFormat.JPEG)
        manager.cacheImageBytes("img-2", byteArrayOf(2), ImageFormat.JPEG)
        try {
            manager.cacheImageBytes("img-3", byteArrayOf(3), ImageFormat.JPEG)
            fail("Expected CACHE_FULL when adding a third staged entry")
        } catch (error: CaptureSessionException) {
            assertEquals("CACHE_FULL", error.code)
        }

        assertArrayEquals(byteArrayOf(2), manager.getImageData("img-2"))
        assertArrayEquals(byteArrayOf(1), manager.getImageData("img-1"))
        assertNull(manager.getImageData("img-3"))
    }

    @Test
    fun `memory stats reflect cached byte usage`() {
        val manager = createManager(maxCacheSize = 4)

        manager.cacheImageBytes("img-1", ByteArray(16), ImageFormat.JPEG)

        val stats = manager.getMemoryStats()

        assertEquals(1, stats["cachedImages"])
        assertEquals(4, stats["maxCacheSize"])
        assertNotNull(stats["currentUsageMB"])
        assertNotNull(stats["usagePercent"])
    }

    @Test
    fun `versioned memory policy includes metadata in 200 MiB cap`() {
        val payloadBytes = 4128L * 2580L * 3L

        val entries = CaptureMemoryBudgetPolicy.maxEntries(
            budgetBytes = CaptureMemoryBudgetPolicy.MAX_CACHE_BYTES,
            payloadBytesPerImage = payloadBytes,
            requestedEntries = 10,
        )
        val totalBytes =
            entries * (payloadBytes + CaptureMemoryBudgetPolicy.METADATA_BYTES_PER_IMAGE)

        assertEquals(6, entries)
        assertTrue(totalBytes <= CaptureMemoryBudgetPolicy.MAX_CACHE_BYTES)
    }

    @Test
    fun `capture capacity reflects staged entries and cache fullness`() {
        val manager = createManager(maxCacheSize = 2)

        manager.cacheImageBytes("img-1", ByteArray(8), ImageFormat.JPEG)
        manager.cacheImageBytes("img-2", ByteArray(4), ImageFormat.JPEG)

        val capacity = manager.getCaptureCapacity()

        assertEquals(2, capacity["maxEntries"])
        assertEquals(2, capacity["usedEntries"])
        assertEquals(2, capacity["readyEntries"])
        assertEquals(0, capacity["persistingEntries"])
        assertEquals(0, capacity["pendingRetryEntries"])
        assertEquals(false, capacity["canCapture"])
        assertEquals("cacheFull", capacity["blockedReason"])
    }

    @Test
    fun `reserveCaptureSlot counts reserved entries and blocks new captures at max entries`() {
        val manager = createManager(maxCacheSize = 2)

        val reservation = manager.reserveCaptureSlot()
        manager.cacheImageBytes("img-1", ByteArray(8), ImageFormat.JPEG)
        val capacity = manager.getCaptureCapacity()

        assertEquals(2, capacity["usedEntries"])
        assertEquals(1, capacity["reservedEntries"])
        assertEquals(1, capacity["readyEntries"])
        assertEquals(false, capacity["canCapture"])
        assertEquals("cacheFull", capacity["blockedReason"])
        assertTrue(manager.releaseReservation(reservation))
    }

    @Test
    fun `commitReservedImage converts reservation into ready entry`() {
        val manager = createManager(maxCacheSize = 2)
        val payload = byteArrayOf(1, 2, 3)
        val reservation = manager.reserveCaptureSlot()

        manager.commitReservedImage(reservation, "img-1", payload, ImageFormat.JPEG)

        val capacity = manager.getCaptureCapacity()
        assertEquals(1, capacity["usedEntries"])
        assertEquals(0, capacity["reservedEntries"])
        assertEquals(1, capacity["readyEntries"])
        assertArrayEquals(payload, manager.getImageData("img-1"))
    }

    @Test
    fun `commitReservedImage rejects unknown reservation token`() {
        val manager = createManager(maxCacheSize = 2)

        try {
            manager.commitReservedImage("missing", "img-1", byteArrayOf(1), ImageFormat.JPEG)
            fail("Expected INVALID_RESERVATION for unknown reservation token")
        } catch (error: CaptureSessionException) {
            assertEquals("INVALID_RESERVATION", error.code)
        }
    }

    @Test
    fun `paired assets become visible under one cache entry atomically`() {
        val manager = createManager(maxCacheSize = 2)
        val reservation = manager.reserveCaptureSlot()
        val jpeg = byteArrayOf(1, 2, 3)
        val dng = byteArrayOf(4, 5, 6, 7)

        manager.commitReservedAssets(
            reservationToken = reservation,
            imageId = "pair-1",
            assets = mapOf(
                "jpeg" to CachedImageAsset(jpeg, ImageFormat.JPEG),
                "dng" to CachedImageAsset(dng, ImageFormat.RAW_SENSOR),
            ),
        )

        assertEquals(1, manager.getCaptureCapacity()["usedEntries"])
        assertArrayEquals(jpeg, manager.getImageData("pair-1", "jpeg"))
        assertArrayEquals(dng, manager.getImageData("pair-1", "dng"))
        assertEquals(
            7L,
            manager.getImageSize("pair-1")["totalBytes"],
        )
    }

    @Test
    fun `paired persistence writes both assets and evicts them together`() {
        val manager = createManager(maxCacheSize = 2)
        val reservation = manager.reserveCaptureSlot()
        val jpeg = byteArrayOf(1, 2, 3)
        val dng = byteArrayOf(4, 5, 6, 7)
        manager.commitReservedAssets(
            reservationToken = reservation,
            imageId = "pair-1",
            assets = mapOf(
                "jpeg" to CachedImageAsset(jpeg, ImageFormat.JPEG),
                "dng" to CachedImageAsset(dng, ImageFormat.RAW_SENSOR),
            ),
        )

        val persisted = manager.persistCapture(
            imageId = "pair-1",
            destinationRoot = temporaryFolder.root.absolutePath,
            sessionFolder = "raw-session",
            baseName = "capture-001",
            format = "raw+jpeg",
        )

        val files = persisted["files"] as Map<*, *>
        assertArrayEquals(jpeg, File(files["jpeg"] as String).readBytes())
        assertArrayEquals(dng, File(files["dng"] as String).readBytes())
        assertNull(manager.getImageData("pair-1"))
        assertEquals(0, manager.getCaptureCapacity()["usedEntries"])
    }

    @Test
    fun `requesting absent dng asset fails explicitly`() {
        val manager = createManager(maxCacheSize = 2)
        manager.cacheImageBytes("jpeg-1", byteArrayOf(1), ImageFormat.JPEG)

        try {
            manager.getImageData("jpeg-1", "dng")
            fail("Expected FORMAT_NOT_CAPTURED")
        } catch (error: CaptureSessionException) {
            assertEquals("FORMAT_NOT_CAPTURED", error.code)
        }
    }

    @Test
    fun `capture capacity reports memory pressure when staged bytes exceed byte budget`() {
        val manager = createManager(maxCacheSize = 4, availableMemoryMBOverride = 4)
        manager.configure(maxCacheSize = 4, estimatedMemoryMB = 1)
        manager.cacheImageBytes("img-1", ByteArray(1_258_291), ImageFormat.JPEG)

        val capacity = manager.getCaptureCapacity()

        assertEquals(false, capacity["canCapture"])
        assertEquals("memoryFull", capacity["blockedReason"])
        assertEquals(1, capacity["readyEntries"])
    }

    @Test
    fun `jpeg raw pair and png estimates share one reserved byte budget`() {
        val manager = createManager(maxCacheSize = 8, availableMemoryMBOverride = 4)

        val jpeg = manager.reserveCaptureSlot(estimatedIncomingBytes = 200_000)
        val rawJpeg = manager.reserveCaptureSlot(estimatedIncomingBytes = 700_000)
        try {
            manager.reserveCaptureSlot(estimatedIncomingBytes = 500_000)
            fail("Expected PNG estimate to exceed aggregate reservation budget")
        } catch (error: CaptureSessionException) {
            assertEquals("CACHE_FULL", error.code)
        }

        assertTrue(manager.releaseReservation(jpeg))
        assertTrue(manager.releaseReservation(rawJpeg))
    }

    @Test
    fun `actual encoded size is reconciled against reservation estimate`() {
        val manager = createManager(maxCacheSize = 4, availableMemoryMBOverride = 4)
        val token = manager.reserveCaptureSlot(estimatedIncomingBytes = 100_000)

        try {
            manager.commitReservedImage(
                token,
                "oversized",
                ByteArray(1_300_000),
                ImageFormat.JPEG,
            )
            fail("Expected actual encoded size reconciliation failure")
        } catch (error: CaptureSessionException) {
            assertEquals("CACHE_BYTE_BUDGET_EXCEEDED", error.code)
        }

        assertEquals(0, manager.getCaptureCapacity()["reservedEntries"])
        assertNull(manager.getImageData("oversized"))
    }

    @Test
    fun `cleanup removes expired cached entries`() {
        val manager = createManager(maxCacheSize = 4, bufferStrategy = "memory")
        manager.cacheImageBytes("img-1", byteArrayOf(1, 2, 3), ImageFormat.JPEG)

        val cacheField = ImageCacheManager::class.java.getDeclaredField("imageCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(manager) as MutableMap<String, CachedImage>
        val original = cache.getValue("img-1")
        cache["img-1"] = original.copy(timestamp = 0L)

        manager.cleanup()

        assertNull(manager.getImageData("img-1"))
    }

    @Test
    fun `getImage returns null for byte backed cache`() {
        val manager = createManager(maxCacheSize = 4)
        manager.cacheImageBytes("img-1", byteArrayOf(1), ImageFormat.JPEG)

        assertNull(manager.getImage("img-1"))
    }

    @Test
    fun `saveImageToFile returns false for missing entry`() {
        val manager = createManager(maxCacheSize = 4)
        val destination = File(temporaryFolder.root, "cache/missing.jpg")

        val saved = manager.saveImageToFile(
            "missing",
            destination.absolutePath,
            ImageFormat.JPEG,
        )

        assertFalse(saved)
        assertFalse(destination.exists())
    }

    @Test
    fun `persistCapture writes part file removes cache entry and reports hash`() {
        val manager = createManager(maxCacheSize = 4)
        val payload = byteArrayOf(4, 5, 6)
        manager.cacheImageBytes("img-1", payload, ImageFormat.JPEG)

        val persisted =
            manager.persistCapture(
                imageId = "img-1",
                destinationRoot = temporaryFolder.root.absolutePath,
                sessionFolder = "session-a",
                baseName = "capture-001",
                format = "jpeg",
            )

        val persistedPath = ((persisted["files"] as Map<*, *>)["jpeg"] as String)
        assertTrue(File(persistedPath).exists())
        assertArrayEquals(payload, File(persistedPath).readBytes())
        assertNull(manager.getImageData("img-1"))
        assertNotNull((persisted["hashes"] as Map<*, *>)["jpeg"])
    }

    @Test
    fun `persistCapture keeps entry pending retry when destination write fails`() {
        val manager = createManager(maxCacheSize = 4)
        val payload = byteArrayOf(4, 5, 6)
        manager.cacheImageBytes("img-1", payload, ImageFormat.JPEG)
        val blockingDirectory = File(temporaryFolder.root, "session-a/capture-001.jpg.part")
        blockingDirectory.mkdirs()

        try {
            manager.persistCapture(
                imageId = "img-1",
                destinationRoot = temporaryFolder.root.absolutePath,
                sessionFolder = "session-a",
                baseName = "capture-001",
                format = "jpeg",
            )
            fail("Expected PERSIST_FAILED when destination part path is a directory")
        } catch (error: CaptureSessionException) {
            assertEquals("PERSIST_FAILED", error.code)
        }

        val capacity = manager.getCaptureCapacity()
        assertEquals(1, capacity["usedEntries"])
        assertEquals(0, capacity["readyEntries"])
        assertEquals(0, capacity["persistingEntries"])
        assertEquals(1, capacity["pendingRetryEntries"])
        assertArrayEquals(payload, manager.getImageData("img-1"))
    }

    @Test
    fun `discardCapture removes cached entry`() {
        val manager = createManager(maxCacheSize = 4)
        manager.cacheImageBytes("img-1", byteArrayOf(1, 2), ImageFormat.JPEG)

        val discarded = manager.discardCapture("img-1")

        assertTrue(discarded)
        assertNull(manager.getImageData("img-1"))
    }

    @Test
    fun `string format helpers enforce jpeg contract`() {
        val manager = createManager(maxCacheSize = 4)
        val payload = byteArrayOf(9, 9, 9)
        val destination = File(temporaryFolder.root, "cache/string-path.jpg")
        manager.cacheImageBytes("img-1", payload, ImageFormat.JPEG)

        val bytes = manager.getImageData("img-1", "jpeg")
        val saved = manager.saveImageToFile("img-1", destination.absolutePath, "jpeg")

        assertArrayEquals(payload, bytes)
        assertTrue(saved)
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun `capacity callback emits on reservation lifecycle transitions`() {
        val capacityEvents = mutableListOf<Map<String, Any?>>()
        val manager =
            createManager(
                maxCacheSize = 2,
                onCapacityChanged = { capacityEvents += it },
            )
        val reservation = manager.reserveCaptureSlot()
        manager.commitReservedImage(reservation, "img-1", byteArrayOf(1, 2), ImageFormat.JPEG)
        manager.persistCapture(
            imageId = "img-1",
            destinationRoot = temporaryFolder.root.absolutePath,
            sessionFolder = "session-a",
            baseName = "capture-001",
            format = "jpeg",
        )

        assertTrue(capacityEvents.any { it["reservedEntries"] == 1 })
        assertTrue(capacityEvents.any { it["readyEntries"] == 1 })
        assertTrue(capacityEvents.any { it["persistingEntries"] == 1 })
        assertEquals(0, capacityEvents.last()["usedEntries"])
    }

    private fun createManager(
        maxCacheSize: Int,
        bufferStrategy: String = "balanced",
        availableMemoryMBOverride: Int = 2048,
        onCapacityChanged: ((Map<String, Any?>) -> Unit)? = null,
    ): ImageCacheManager {
        val config = ParsedCaptureConfig(
            enableHighResCapture = true,
            captureIntervalMs = 1000,
            resolution = Size(640, 480),
            format = ImageFormat.JPEG,
            maxCacheSize = maxCacheSize,
            jpegQuality = 95,
            autoExposure = true,
            autoWhiteBalance = true,
            defaultISO = null,
            defaultExposureTimeMicros = null,
            enablePoseStream = true,
            bufferStrategy = bufferStrategy,
        )
        return ImageCacheManager(
            config = config,
            context = null,
            availableMemoryMBOverride = availableMemoryMBOverride,
            onCapacityChanged = onCapacityChanged,
        )
    }
}
