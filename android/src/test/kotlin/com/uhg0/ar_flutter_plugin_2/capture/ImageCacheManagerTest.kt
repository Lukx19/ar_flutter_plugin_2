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
    fun `cache evicts oldest entry when max cache size is reached`() {
        val manager = createManager(maxCacheSize = 2)

        manager.cacheImageBytes("img-1", byteArrayOf(1), ImageFormat.JPEG)
        manager.cacheImageBytes("img-2", byteArrayOf(2), ImageFormat.JPEG)
        manager.cacheImageBytes("img-3", byteArrayOf(3), ImageFormat.JPEG)

        assertNull(manager.getImageData("img-1"))
        assertArrayEquals(byteArrayOf(2), manager.getImageData("img-2"))
        assertArrayEquals(byteArrayOf(3), manager.getImageData("img-3"))
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

    private fun createManager(
        maxCacheSize: Int,
        bufferStrategy: String = "balanced",
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
            availableMemoryMBOverride = 2048,
        )
    }
}
