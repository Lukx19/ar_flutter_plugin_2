package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

internal data class CaptureConfig(
    val format: String,
    val captureIntervalMs: Int,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val maxCacheSize: Int,
    val jpegQuality: Int,
) {
    companion object {
        fun fromMap(configMap: Map<String, Any?>): CaptureConfig {
            val format = configMap["format"] as? String ?: "jpeg"
            val captureIntervalMs = (configMap["captureIntervalMs"] as? Number)?.toInt() ?: 5000
            val resolutionMap = configMap["resolution"] as? Map<*, *>
            val resolutionWidth = (resolutionMap?.get("width") as? Number)?.toInt() ?: 0
            val resolutionHeight = (resolutionMap?.get("height") as? Number)?.toInt() ?: 0
            val maxCacheSize = (configMap["maxCacheSize"] as? Number)?.toInt() ?: 10
            val jpegQuality = (configMap["jpegQuality"] as? Number)?.toInt() ?: 95

            if (format !in setOf("jpeg", "raw+jpeg", "raw", "png")) {
                throw CaptureSessionException(
                    code = "FORMAT_NOT_CAPTURED",
                    message = "Unsupported capture mode=$format",
                )
            }
            if (captureIntervalMs < 0) {
                throw CaptureSessionException(
                    code = "CONFIG_INVALID",
                    message = "captureIntervalMs must be zero or a positive value",
                )
            }
            if (captureIntervalMs in 1..99) {
                throw CaptureSessionException(
                    code = "CONFIG_INVALID",
                    message = "captureIntervalMs must be 0 or at least 100ms",
                )
            }
            if (captureIntervalMs > 3600000) {
                throw CaptureSessionException(
                    code = "CONFIG_INVALID",
                    message = "captureIntervalMs must not exceed 3600000ms",
                )
            }
            if (resolutionWidth <= 0 || resolutionHeight <= 0) {
                throw CaptureSessionException(
                    code = "CONFIG_INVALID",
                    message = "resolution.width and resolution.height must be positive",
                )
            }
            if (maxCacheSize !in 1..100) {
                throw CaptureSessionException(
                    code = "CONFIG_INVALID",
                    message = "maxCacheSize must be between 1 and 100",
                )
            }
            if (jpegQuality !in 10..100) {
                throw CaptureSessionException(
                    code = "CONFIG_INVALID",
                    message = "jpegQuality must be between 10 and 100",
                )
            }

            return CaptureConfig(
                format = format,
                captureIntervalMs = captureIntervalMs,
                resolutionWidth = resolutionWidth,
                resolutionHeight = resolutionHeight,
                maxCacheSize = maxCacheSize,
                jpegQuality = jpegQuality,
            )
        }
    }
}

internal data class CachedCapture(
    val imageId: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
)

internal class CaptureByteCache {
    private var config: CaptureConfig? = null
    private var isDisposed = false
    private val cache = LinkedHashMap<String, CachedCapture>(16, 0.75f, true)

    fun initialize(config: CaptureConfig) {
        isDisposed = false
        this.config = config
    }

    fun nextImageId(timestampMs: Long): String = "img_${timestampMs}_${cache.size + 1}"

    fun cacheCapture(
        imageId: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    ): CachedCapture {
        requireInitialized()
        val maxEntries = config?.maxCacheSize ?: 0
        if (!cache.containsKey(imageId) && cache.size >= maxEntries) {
            throw CaptureSessionException(
                code = "CACHE_FULL",
                message = "Capture cache is full",
            )
        }
        val cachedCapture = CachedCapture(
            imageId = imageId,
            bytes = bytes.copyOf(),
            width = width,
            height = height,
            timestampMs = timestampMs,
        )
        cache[imageId] = cachedCapture
        return cachedCapture
    }

    fun getCaptureCapacity(): Map<String, Any?> {
        requireInitialized()
        val currentConfig = config ?: throw CaptureSessionException(
            code = "CAPTURE_NOT_INITIALIZED",
            message = "Capture session is not initialized",
        )
        val stagedBytes = cache.values.sumOf { it.bytes.size.toLong() }
        val maxStagedBytes =
            currentConfig.maxCacheSize.toLong() *
                currentConfig.resolutionWidth.toLong() *
                currentConfig.resolutionHeight.toLong() * 4L
        val usedEntries = cache.size

        return mapOf(
            "maxEntries" to currentConfig.maxCacheSize,
            "usedEntries" to usedEntries,
            "reservedEntries" to 0,
            "readyEntries" to usedEntries,
            "persistingEntries" to 0,
            "pendingRetryEntries" to 0,
            "stagedBytes" to stagedBytes,
            "maxStagedBytes" to maxStagedBytes,
            "canCapture" to (usedEntries < currentConfig.maxCacheSize),
            "blockedReason" to if (usedEntries < currentConfig.maxCacheSize) null else "cacheFull",
        )
    }

    fun getImageData(imageId: String, format: String): ByteArray {
        requireInitialized()
        requireJpegFormat(format)
        return requireCachedCapture(imageId).bytes.copyOf()
    }

    fun getImageSize(imageId: String): Map<String, Any> {
        requireInitialized()
        val capture = requireCachedCapture(imageId)
        return mapOf(
            "width" to capture.width,
            "height" to capture.height,
            "bytesPerPixel" to 1,
            "totalBytes" to capture.bytes.size,
        )
    }

    fun saveImageToFile(imageId: String, filePath: String, format: String): Boolean {
        requireInitialized()
        requireJpegFormat(format)
        requireJpegPath(filePath)
        val capture = requireCachedCapture(imageId)
        val destination = File(filePath)
        destination.parentFile?.mkdirs()
        destination.writeBytes(capture.bytes)
        return true
    }

    fun persistCapture(
        imageId: String,
        destinationRoot: String,
        sessionFolder: String,
        baseName: String,
        format: String,
    ): Map<String, Any> {
        requireInitialized()
        requireJpegFormat(format)
        require(baseName.isNotBlank()) { "baseName is required" }
        val capture = requireCachedCapture(imageId)
        val destinationDirectory =
            if (sessionFolder.isBlank()) {
                File(destinationRoot)
            } else {
                File(destinationRoot, sessionFolder)
            }
        destinationDirectory.mkdirs()
        val destinationPartFile = File(destinationDirectory, "$baseName.jpg.part")
        destinationPartFile.writeBytes(capture.bytes)
        cache.remove(imageId)
        return mapOf(
            "files" to mapOf("jpeg" to destinationPartFile.absolutePath),
            "sizes" to mapOf("jpeg" to capture.bytes.size),
            "hashes" to mapOf("jpeg" to sha256(capture.bytes)),
        )
    }

    fun discardCapture(imageId: String): Boolean {
        requireInitialized()
        return cache.remove(imageId) != null
    }

    fun dispose() {
        cache.clear()
        config = null
        isDisposed = true
    }

    private fun requireInitialized() {
        if (isDisposed || config == null) {
            throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "Capture session is not initialized",
            )
        }
    }

    private fun requireJpegFormat(format: String) {
        if (format != "jpeg") {
            throw CaptureSessionException(
                code = "FORMAT_NOT_CAPTURED",
                message = "Only JPEG capture is currently supported",
            )
        }
    }

    private fun requireJpegPath(filePath: String) {
        val lowerPath = filePath.lowercase()
        if (!lowerPath.endsWith(".jpg") && !lowerPath.endsWith(".jpeg")) {
            throw CaptureSessionException(
                code = "FORMAT_MISMATCH",
                message = "JPEG captures must be saved to a .jpg or .jpeg path",
            )
        }
    }

    private fun requireCachedCapture(imageId: String): CachedCapture {
        return cache[imageId] ?: throw CaptureSessionException(
            code = "IMAGE_NOT_FOUND",
            message = "No cached capture exists for imageId=$imageId",
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
