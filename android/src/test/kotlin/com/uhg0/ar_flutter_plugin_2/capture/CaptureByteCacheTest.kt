package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureByteCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `config parser accepts supported jpeg defaults`() {
        val config = CaptureConfig.fromMap(
            mapOf(
                "format" to "jpeg",
                "resolution" to mapOf(
                    "width" to 640,
                    "height" to 480,
                ),
                "maxCacheSize" to 2,
                "jpegQuality" to 95,
            ),
        )

        assertEquals("jpeg", config.format)
        assertEquals(5000, config.captureIntervalMs)
        assertEquals(640, config.resolutionWidth)
        assertEquals(480, config.resolutionHeight)
        assertEquals(2, config.maxCacheSize)
        assertEquals(95, config.jpegQuality)
    }

    @Test
    fun `config parser accepts paired capture mode`() {
        val config = CaptureConfig.fromMap(
            mapOf(
                "format" to "raw+jpeg",
                "resolution" to mapOf("width" to 640, "height" to 480),
                "maxCacheSize" to 2,
                "jpegQuality" to 95,
            ),
        )

        assertEquals("raw+jpeg", config.format)
    }

    @Test
    fun `config parser accepts raw only and png formats`() {
        listOf("raw", "png").forEach { format ->
            val config = CaptureConfig.fromMap(
                mapOf(
                    "format" to format,
                    "resolution" to mapOf(
                        "width" to 640,
                        "height" to 480,
                    ),
                    "maxCacheSize" to 2,
                    "jpegQuality" to 95,
                ),
            )
            assertEquals(format, config.format)
        }
    }

    @Test
    fun `config parser rejects invalid cache size`() {
        val error = captureException {
            CaptureConfig.fromMap(
                mapOf(
                    "format" to "jpeg",
                    "resolution" to mapOf(
                        "width" to 640,
                        "height" to 480,
                    ),
                    "maxCacheSize" to 0,
                    "jpegQuality" to 95,
                ),
            )
        }

        assertEquals("CONFIG_INVALID", error.code)
        assertTrue(error.message!!.contains("maxCacheSize"))
    }

    @Test
    fun `config parser rejects cache size above limit`() {
        val error = captureException {
            CaptureConfig.fromMap(
                mapOf(
                    "format" to "jpeg",
                    "resolution" to mapOf(
                        "width" to 640,
                        "height" to 480,
                    ),
                    "maxCacheSize" to 101,
                    "jpegQuality" to 95,
                ),
            )
        }

        assertEquals("CONFIG_INVALID", error.code)
        assertTrue(error.message!!.contains("maxCacheSize"))
    }

    @Test
    fun `config parser rejects invalid jpeg quality`() {
        val error = captureException {
            CaptureConfig.fromMap(
                mapOf(
                    "format" to "jpeg",
                    "resolution" to mapOf(
                        "width" to 640,
                        "height" to 480,
                    ),
                    "maxCacheSize" to 2,
                    "jpegQuality" to 9,
                ),
            )
        }

        assertEquals("CONFIG_INVALID", error.code)
        assertTrue(error.message!!.contains("jpegQuality"))
    }

    @Test
    fun `config parser accepts manual only capture interval`() {
        val config = CaptureConfig.fromMap(
            mapOf(
                "format" to "jpeg",
                "captureIntervalMs" to 0,
                "resolution" to mapOf(
                    "width" to 640,
                    "height" to 480,
                ),
                "maxCacheSize" to 2,
                "jpegQuality" to 95,
            ),
        )

        assertEquals(0, config.captureIntervalMs)
    }

    @Test
    fun `config parser rejects invalid automatic capture interval`() {
        val error = captureException {
            CaptureConfig.fromMap(
                mapOf(
                    "format" to "jpeg",
                    "captureIntervalMs" to 50,
                    "resolution" to mapOf(
                        "width" to 640,
                        "height" to 480,
                    ),
                    "maxCacheSize" to 2,
                    "jpegQuality" to 95,
                ),
            )
        }

        assertEquals("CONFIG_INVALID", error.code)
        assertTrue(error.message!!.contains("captureIntervalMs"))
    }

    @Test
    fun `config parser rejects missing resolution`() {
        val error = captureException {
            CaptureConfig.fromMap(
                mapOf(
                    "format" to "jpeg",
                    "maxCacheSize" to 2,
                    "jpegQuality" to 95,
                ),
            )
        }

        assertEquals("CONFIG_INVALID", error.code)
        assertTrue(error.message!!.contains("resolution"))
    }

    @Test
    fun `cache reports full instead of evicting staged captures`() {
        val cache = initializedCache(maxCacheSize = 2)

        cache.cacheCapture("img-1", byteArrayOf(1), 1, 1, 1L)
        cache.cacheCapture("img-2", byteArrayOf(2), 1, 1, 2L)
        val error = captureException {
            cache.cacheCapture("img-3", byteArrayOf(3), 1, 1, 3L)
        }

        assertEquals("CACHE_FULL", error.code)
        assertArrayEquals(byteArrayOf(1), cache.getImageData("img-1", "jpeg"))
        assertArrayEquals(byteArrayOf(2), cache.getImageData("img-2", "jpeg"))
    }

    @Test
    fun `capture capacity reports staged entry usage and blockage`() {
        val cache = initializedCache(maxCacheSize = 2)
        cache.cacheCapture("img-1", byteArrayOf(9, 8, 7, 6), 640, 480, 1L)
        cache.cacheCapture("img-2", byteArrayOf(5, 4, 3), 640, 480, 2L)

        val capacity = cache.getCaptureCapacity()

        assertEquals(2, capacity["maxEntries"])
        assertEquals(2, capacity["usedEntries"])
        assertEquals(2, capacity["readyEntries"])
        assertEquals(7L, capacity["stagedBytes"])
        assertEquals(false, capacity["canCapture"])
        assertEquals("cacheFull", capacity["blockedReason"])
    }

    @Test
    fun `get image size returns cached byte dimensions`() {
        val cache = initializedCache()
        cache.cacheCapture("img-1", byteArrayOf(9, 8, 7, 6), 640, 480, 1L)

        val size = cache.getImageSize("img-1")

        assertEquals(640, size["width"])
        assertEquals(480, size["height"])
        assertEquals(4, size["totalBytes"])
    }

    @Test
    fun `save image writes byte identical jpeg payload`() {
        val cache = initializedCache()
        val payload = byteArrayOf(5, 4, 3, 2, 1)
        cache.cacheCapture("img-1", payload, 10, 10, 1L)
        val destination = File(temporaryFolder.root, "capture/output.jpg")

        val saved = cache.saveImageToFile("img-1", destination.absolutePath, "jpeg")

        assertTrue(saved)
        assertTrue(destination.exists())
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun `persist capture writes bytes and removes staged entry`() {
        val cache = initializedCache()
        val payload = byteArrayOf(5, 4, 3, 2, 1)
        cache.cacheCapture("img-1", payload, 10, 10, 1L)
        val destinationRoot = temporaryFolder.root.absolutePath

        val persisted = cache.persistCapture(
            "img-1",
            destinationRoot,
            "capture",
            "persisted",
            "jpeg",
        )
        val destination = File(temporaryFolder.root, "capture/persisted.jpg.part")

        assertEquals(destination.absolutePath, (persisted["files"] as Map<*, *>)["jpeg"])
        assertEquals(payload.size, (persisted["sizes"] as Map<*, *>)["jpeg"])
        assertEquals(
            "6ca634de76e9d886a6e5f19e5daa54ccd9c0af3338ec96bbcf1cefc0691663df",
            (persisted["hashes"] as Map<*, *>)["jpeg"],
        )
        assertTrue(destination.exists())
        assertArrayEquals(payload, destination.readBytes())
        val error = captureException {
            cache.getImageData("img-1", "jpeg")
        }
        assertEquals("IMAGE_NOT_FOUND", error.code)
    }

    @Test
    fun `discard capture removes staged entry`() {
        val cache = initializedCache()
        cache.cacheCapture("img-1", byteArrayOf(1, 2, 3), 10, 10, 1L)

        val discarded = cache.discardCapture("img-1")

        assertTrue(discarded)
        val error = captureException {
            cache.getImageData("img-1", "jpeg")
        }
        assertEquals("IMAGE_NOT_FOUND", error.code)
    }

    @Test
    fun `save image rejects non jpeg extension`() {
        val cache = initializedCache()
        cache.cacheCapture("img-1", byteArrayOf(1, 2, 3), 10, 10, 1L)
        val destination = File(temporaryFolder.root, "capture/output.png")

        val error = captureException {
            cache.saveImageToFile("img-1", destination.absolutePath, "jpeg")
        }

        assertEquals("FORMAT_MISMATCH", error.code)
        assertFalse(destination.exists())
    }

    @Test
    fun `dispose clears cache and requires reinitialization`() {
        val cache = initializedCache()
        cache.cacheCapture("img-1", byteArrayOf(1, 2, 3), 10, 10, 1L)

        cache.dispose()

        val error = captureException {
            cache.getImageData("img-1", "jpeg")
        }
        assertEquals("CAPTURE_NOT_INITIALIZED", error.code)
    }

    @Test
    fun `missing image raises stable error`() {
        val cache = initializedCache()

        val error = captureException {
            cache.getImageData("missing-image", "jpeg")
        }

        assertEquals("IMAGE_NOT_FOUND", error.code)
        assertTrue(error.message!!.contains("missing-image"))
    }

    @Test
    fun `non jpeg format requests are rejected`() {
        val cache = initializedCache()
        cache.cacheCapture("img-1", byteArrayOf(1, 2, 3), 10, 10, 1L)

        val error = captureException {
            cache.getImageData("img-1", "raw")
        }

        assertEquals("FORMAT_NOT_CAPTURED", error.code)
    }

    private fun initializedCache(maxCacheSize: Int = 4): CaptureByteCache {
        return CaptureByteCache().apply {
            initialize(
                CaptureConfig(
                    format = "jpeg",
                    captureIntervalMs = 5000,
                    resolutionWidth = 640,
                    resolutionHeight = 480,
                    maxCacheSize = maxCacheSize,
                    jpegQuality = 95,
                ),
            )
        }
    }

    private fun captureException(block: () -> Unit): CaptureSessionException {
        try {
            block()
        } catch (error: CaptureSessionException) {
            return error
        }
        throw AssertionError("Expected CaptureSessionException to be thrown")
    }
}
