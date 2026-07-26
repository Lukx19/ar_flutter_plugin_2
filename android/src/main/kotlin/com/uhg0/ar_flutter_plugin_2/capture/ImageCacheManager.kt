package com.uhg0.ar_flutter_plugin_2.capture

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object CaptureMemoryBudgetPolicy {
    const val VERSION = "capture-memory-v1"
    const val AVAILABLE_MEMORY_FRACTION = 0.30
    const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
    const val METADATA_BYTES_PER_IMAGE = 1024L

    fun maxEntries(
        budgetBytes: Long,
        payloadBytesPerImage: Long,
        requestedEntries: Int,
    ): Int {
        val perEntryBytes = payloadBytesPerImage + METADATA_BYTES_PER_IMAGE
        return minOf(
            (budgetBytes / perEntryBytes).toInt().coerceAtLeast(1),
            requestedEntries,
        )
    }
}

data class MemoryUsageEstimate(
    val perImageBytes: Long,
    val totalCacheBytes: Long,
    val overheadBytes: Long,
    val totalBytes: Long
) {
    val totalMB: Double get() = totalBytes / (1024.0 * 1024.0)
    val isWithinBounds: Boolean
        get() = totalBytes <= CaptureMemoryBudgetPolicy.MAX_CACHE_BYTES
}

data class CacheConfiguration(
    val maxCacheSize: Int,
    val bufferStrategy: String,
    val memoryUsageEstimate: MemoryUsageEstimate,
    val compressionEnabled: Boolean,
    val maxImageAgeMins: Int
)

enum class CachedImageState {
    READY,
    PERSISTING,
    PENDING_RETRY,
}

data class CacheReservation(
    val token: String,
    val timestamp: Long,
    val sequence: Long,
    val reservedBytes: Long = 0L,
)

class ImageCacheManager(
    private val config: ParsedCaptureConfig,
    private val context: Context?,
    private val availableMemoryMBOverride: Int? = null,
    private val onCapacityChanged: ((Map<String, Any?>) -> Unit)? = null,
) {
    private val imageCache = ConcurrentHashMap<String, CachedImage>()
    private val reservations = ConcurrentHashMap<String, CacheReservation>()
    private val memoryUsage = AtomicLong(0L)
    private val cacheSequence = AtomicLong(0L)
    private var cacheConfiguration: CacheConfiguration? = null
    private val cacheLock = Any()

    // Memory management
    private val availableMemoryMB: Int = availableMemoryMBOverride ?: getAvailableMemoryMB()
    private var maxAllowedMemoryBytes: Long = 0L

    init {
        val availableFractionBytes =
            (availableMemoryMB * CaptureMemoryBudgetPolicy.AVAILABLE_MEMORY_FRACTION * 1024 * 1024)
                .toLong()
        maxAllowedMemoryBytes =
            minOf(availableFractionBytes, CaptureMemoryBudgetPolicy.MAX_CACHE_BYTES)
        cacheConfiguration = configure()
        Log.i(
            "ImageCacheManager",
            "Initialized with ${CaptureMemoryBudgetPolicy.VERSION}, " +
                "cacheBudgetBytes=$maxAllowedMemoryBytes config=$cacheConfiguration",
        )
    }

    /// Configure cache based on ARCaptureConfig settings and available memory
    fun configure(): CacheConfiguration {
        val baseEstimate = estimateMemoryUsage(config.maxCacheSize)

        // Adjust for memory constraints
        val adjustedConfig = if (baseEstimate.totalBytes > maxAllowedMemoryBytes) {
            adjustForMemoryConstraints()
        } else {
            CacheConfiguration(
                maxCacheSize = config.maxCacheSize,
                bufferStrategy = config.bufferStrategy,
                memoryUsageEstimate = baseEstimate,
                compressionEnabled = shouldEnableCompression(),
                maxImageAgeMins = getMaxImageAge()
            )
        }

        Log.i("ImageCacheManager", "Cache configured: ${adjustedConfig.maxCacheSize} images, ${adjustedConfig.memoryUsageEstimate.totalMB}MB")
        return adjustedConfig
    }

    /// Configure with external parameters
    fun configure(maxCacheSize: Int, estimatedMemoryMB: Int) {
        val estimate = estimateMemoryUsage(maxCacheSize)
        cacheConfiguration = CacheConfiguration(
            maxCacheSize = maxCacheSize,
            bufferStrategy = config.bufferStrategy,
            memoryUsageEstimate = estimate,
            compressionEnabled = shouldEnableCompression(),
            maxImageAgeMins = getMaxImageAge()
        )
        Log.i("ImageCacheManager", "Cache configured externally: $maxCacheSize images, ${estimate.totalMB}MB")
    }

    /// Get optimal cache size based on configuration and available memory
    fun getOptimalCacheSize(): Int {
        return CaptureMemoryBudgetPolicy.maxEntries(
            budgetBytes = maxAllowedMemoryBytes,
            payloadBytesPerImage = getPerImageBytes(),
            requestedEntries = config.maxCacheSize,
        )
    }

    /// Estimate memory usage for given cache size
    fun estimateMemoryUsage(cacheSize: Int = config.maxCacheSize): MemoryUsageEstimate {
        val perImageBytes = getPerImageBytes()
        val totalCacheBytes = perImageBytes * cacheSize
        val overheadBytes = cacheSize * CaptureMemoryBudgetPolicy.METADATA_BYTES_PER_IMAGE
        val totalBytes = totalCacheBytes + overheadBytes

        return MemoryUsageEstimate(
            perImageBytes = perImageBytes,
            totalCacheBytes = totalCacheBytes,
            overheadBytes = overheadBytes,
            totalBytes = totalBytes
        )
    }

    /// Adjust configuration for memory constraints
    fun adjustForMemoryConstraints(): CacheConfiguration {
        val optimalCacheSize = getOptimalCacheSize()
        val adjustedEstimate = estimateMemoryUsage(optimalCacheSize)

        return CacheConfiguration(
            maxCacheSize = optimalCacheSize,
            bufferStrategy = "memory", // Force memory-optimized strategy
            memoryUsageEstimate = adjustedEstimate,
            compressionEnabled = true, // Enable compression to save memory
            maxImageAgeMins = 5 // Shorter image lifetime to free memory faster
        )
    }

    /// Cache image with configuration-based management
    fun cacheImage(imageId: String, image: Image, format: Int): Boolean {
        return try {
            val imageBytes = imageToByteArray(image, format)
            cacheImageBytes(imageId, imageBytes, format)
        } catch (e: Exception) {
            Log.e("ImageCacheManager", "Failed to cache image $imageId", e)
            false
        }
    }

    /// Cache already-encoded image bytes.
    fun cacheImageBytes(imageId: String, imageBytes: ByteArray, format: Int): Boolean {
        return cacheImageAssets(
            imageId = imageId,
            assets = mapOf(formatName(format) to CachedImageAsset(imageBytes.copyOf(), format)),
        )
    }

    private fun cacheImageAssets(
        imageId: String,
        assets: Map<String, CachedImageAsset>,
        sequence: Long = cacheSequence.incrementAndGet(),
    ): Boolean {
        require(assets.isNotEmpty()) { "At least one capture asset is required" }
        synchronized(cacheLock) {
            val existingImage = imageCache[imageId]
            val currentConfig = cacheConfiguration ?: configure()
            val usedEntries = imageCache.size + reservations.size

            if (existingImage == null && usedEntries >= currentConfig.maxCacheSize) {
                throw CaptureSessionException(
                    code = "CACHE_FULL",
                    message = "Capture cache is full",
                )
            }

            val cachedImage = CachedImage(
                id = imageId,
                assets = assets.mapValues { (_, asset) -> asset.copy(bytes = asset.bytes.copyOf()) },
                timestamp = System.currentTimeMillis(),
                sequence = sequence,
                state = CachedImageState.READY,
            )
            existingImage?.let { memoryUsage.addAndGet(-it.sizeBytes) }
            imageCache[imageId] = cachedImage
            memoryUsage.addAndGet(cachedImage.sizeBytes)
        }

        notifyCapacityChanged()
        Log.d("ImageCacheManager", "Cached capture $imageId, total memory: ${memoryUsage.get() / 1024 / 1024}MB")
        return true
    }

    fun reserveCaptureSlot(estimatedIncomingBytes: Long = getPerImageBytes().coerceAtLeast(1L)): String {
        require(estimatedIncomingBytes > 0L) { "estimatedIncomingBytes must be positive" }
        val reservation =
            synchronized(cacheLock) {
                val currentConfig = cacheConfiguration ?: configure()
                val stagedBytes = imageCache.values.sumOf { it.sizeBytes }
                val reservedBytes = reservations.values.sumOf { it.reservedBytes }
                val usedEntries = imageCache.size + reservations.size
                val maxStagedBytes = effectiveMaxStagedBytes(currentConfig)

                when {
                    usedEntries >= currentConfig.maxCacheSize ->
                        throw CaptureSessionException(
                            code = "CACHE_FULL",
                            message = "Capture cache is full",
                        )
                    stagedBytes + reservedBytes + estimatedIncomingBytes > maxStagedBytes ->
                        throw CaptureSessionException(
                            code = "CACHE_FULL",
                            message = "Capture cache has reached the staged byte budget",
                        )
                }

                CacheReservation(
                    token = "reservation_${cacheSequence.incrementAndGet()}",
                    timestamp = System.currentTimeMillis(),
                    sequence = cacheSequence.get(),
                    reservedBytes = estimatedIncomingBytes,
                ).also { reservations[it.token] = it }
            }
        notifyCapacityChanged()
        return reservation.token
    }

    fun releaseReservation(reservationToken: String): Boolean {
        val released =
            synchronized(cacheLock) {
                reservations.remove(reservationToken) != null
            }
        if (released) {
            notifyCapacityChanged()
        }
        return released
    }

    fun commitReservedImage(
        reservationToken: String,
        imageId: String,
        imageBytes: ByteArray,
        format: Int,
    ): Boolean {
        return commitReservedAssets(
            reservationToken = reservationToken,
            imageId = imageId,
            assets = mapOf(formatName(format) to CachedImageAsset(imageBytes.copyOf(), format)),
        )
    }

    fun commitReservedAssets(
        reservationToken: String,
        imageId: String,
        assets: Map<String, CachedImageAsset>,
    ): Boolean {
        require(assets.isNotEmpty()) { "At least one capture asset is required" }
        synchronized(cacheLock) {
            val reservation =
                reservations[reservationToken]
                    ?: throw CaptureSessionException(
                        code = "INVALID_RESERVATION",
                        message = "No active reservation exists for token=$reservationToken",
                    )
            val existingImage = imageCache[imageId]
            val actualBytes = assets.values.sumOf { it.bytes.size.toLong() }
            val currentConfig = cacheConfiguration ?: configure()
            val stagedWithoutExisting =
                imageCache.values.sumOf { it.sizeBytes } - (existingImage?.sizeBytes ?: 0L)
            val otherReservedBytes =
                reservations.values.sumOf { it.reservedBytes } - reservation.reservedBytes
            if (stagedWithoutExisting + otherReservedBytes + actualBytes >
                effectiveMaxStagedBytes(currentConfig)
            ) {
                reservations.remove(reservationToken)
                throw CaptureSessionException(
                    code = "CACHE_BYTE_BUDGET_EXCEEDED",
                    message = "Encoded capture exceeds the staged byte budget",
                )
            }
            reservations.remove(reservationToken)
            val cachedImage =
                CachedImage(
                    id = imageId,
                    assets = assets.mapValues { (_, asset) -> asset.copy(bytes = asset.bytes.copyOf()) },
                    timestamp = System.currentTimeMillis(),
                    sequence = reservation.sequence,
                    state = CachedImageState.READY,
                )
            existingImage?.let { memoryUsage.addAndGet(-it.sizeBytes) }
            imageCache[imageId] = cachedImage
            memoryUsage.addAndGet(cachedImage.sizeBytes)
        }
        notifyCapacityChanged()
        return true
    }

    /// Get image from cache
    fun getImage(imageId: String): Image? {
        Log.w("ImageCacheManager", "getImage is unsupported for byte-backed cached images: $imageId")
        return null
    }

    /// Get image data as byte array
    fun getImageData(imageId: String): ByteArray? {
        val cachedImage = imageCache[imageId] ?: return null
        return cachedImage.assets["jpeg"]?.bytes?.copyOf()
    }

    fun getImageData(imageId: String, format: String): ByteArray {
        return requireAsset(requireCachedImage(imageId), format).bytes.copyOf()
    }

    fun getCaptureCapacity(): Map<String, Any?> {
        val currentConfig = cacheConfiguration ?: configure()
        val stagedBytes = imageCache.values.sumOf { it.sizeBytes }
        val reservedEntries = reservations.size
        val usedEntries = imageCache.size + reservedEntries
        val maxEntries = currentConfig.maxCacheSize
        val maxStagedBytes = effectiveMaxStagedBytes(currentConfig)
        val readyEntries = imageCache.values.count { it.state == CachedImageState.READY }
        val persistingEntries =
            imageCache.values.count { it.state == CachedImageState.PERSISTING }
        val pendingRetryEntries =
            imageCache.values.count { it.state == CachedImageState.PENDING_RETRY }
        val canCapture = usedEntries < maxEntries && stagedBytes < maxStagedBytes
        val blockedReason =
            when {
                usedEntries >= maxEntries -> "cacheFull"
                stagedBytes >= maxStagedBytes -> "memoryFull"
                else -> null
            }

        return mapOf(
            "maxEntries" to maxEntries,
            "usedEntries" to usedEntries,
            "reservedEntries" to reservedEntries,
            "readyEntries" to readyEntries,
            "persistingEntries" to persistingEntries,
            "pendingRetryEntries" to pendingRetryEntries,
            "stagedBytes" to stagedBytes,
            "maxStagedBytes" to maxStagedBytes,
            "canCapture" to canCapture,
            "blockedReason" to blockedReason,
        )
    }

    fun getImageSize(imageId: String): Map<String, Any> {
        val cachedImage = requireCachedImage(imageId)
        return mapOf(
            "width" to config.resolution.width,
            "height" to config.resolution.height,
            "bytesPerPixel" to 1,
            "totalBytes" to cachedImage.sizeBytes,
            "bytesByFormat" to cachedImage.assets.mapValues { it.value.sizeBytes },
        )
    }

    /// Save image to file
    fun saveImageToFile(imageId: String, filePath: String, format: Int): Boolean {
        val cachedImage = imageCache[imageId] ?: return false

        return try {
            val imageData = requireAsset(cachedImage, formatName(format)).bytes
            val destination = java.io.File(filePath)
            destination.parentFile?.mkdirs()
            destination.writeBytes(imageData)
            Log.d("ImageCacheManager", "Saved image $imageId to $filePath")
            true
        } catch (e: Exception) {
            Log.e("ImageCacheManager", "Failed to save image $imageId", e)
            false
        }
    }

    fun saveImageToFile(imageId: String, filePath: String, format: String): Boolean {
        val cachedImage = requireCachedImage(imageId)
        requireFormatPath(format, filePath)
        val asset = requireAsset(cachedImage, format)
        val destination = File(filePath)
        destination.parentFile?.mkdirs()
        destination.writeBytes(asset.bytes)
        return true
    }

    fun persistCapture(
        imageId: String,
        destinationRoot: String,
        sessionFolder: String,
        baseName: String,
        format: String,
    ): Map<String, Any> {
        require(baseName.isNotBlank()) { "baseName is required" }
        val cachedImage = requireCachedImage(imageId)
        imageCache[imageId] = cachedImage.copy(state = CachedImageState.PERSISTING)
        notifyCapacityChanged()
        val destinationDirectory =
            if (sessionFolder.isBlank()) {
                File(destinationRoot)
            } else {
                File(destinationRoot, sessionFolder)
            }
        return try {
            destinationDirectory.mkdirs()
            val files = linkedMapOf<String, String>()
            val sizes = linkedMapOf<String, Int>()
            val hashes = linkedMapOf<String, String>()
            val created = mutableListOf<File>()
            try {
                cachedImage.assets.forEach { (assetFormat, asset) ->
                    val extension = when (assetFormat) {
                        "dng" -> "dng"
                        else -> "jpg"
                    }
                    val partFile = File(destinationDirectory, "$baseName.$extension.part")
                    partFile.writeBytes(asset.bytes)
                    created += partFile
                    files[assetFormat] = partFile.absolutePath
                    sizes[assetFormat] = asset.bytes.size
                    hashes[assetFormat] = sha256(asset.bytes)
                }
            } catch (error: Throwable) {
                created.forEach { it.delete() }
                throw error
            }
            removeImage(imageId)
            notifyCapacityChanged()
            mapOf(
                "files" to files,
                "sizes" to sizes,
                "hashes" to hashes,
            )
        } catch (error: Exception) {
            imageCache[imageId] = cachedImage.copy(state = CachedImageState.PENDING_RETRY)
            notifyCapacityChanged()
            throw CaptureSessionException(
                code = "PERSIST_FAILED",
                message = "Failed to persist cached capture: ${error.message}",
            )
        }
    }

    fun discardCapture(imageId: String): Boolean {
        val existed = imageCache.containsKey(imageId)
        removeImage(imageId)
        if (existed) {
            notifyCapacityChanged()
        }
        return existed
    }

    /// Get current memory statistics
    fun getMemoryStats(): Map<String, Any> {
        val currentUsageMB = memoryUsage.get() / (1024.0 * 1024.0)
        val maxUsageMB = maxAllowedMemoryBytes / (1024.0 * 1024.0)
        val usagePercent = (currentUsageMB / maxUsageMB * 100.0).toInt()

        return mapOf(
            "currentUsageMB" to currentUsageMB,
            "maxUsageMB" to maxUsageMB,
            "usagePercent" to usagePercent,
            "cachedImages" to imageCache.size,
            "maxCacheSize" to (cacheConfiguration?.maxCacheSize ?: 0)
        )
    }

    /// Cleanup cache (remove expired images)
    fun cleanup() {
        val maxAge = (cacheConfiguration?.maxImageAgeMins ?: 10) * 60 * 1000L // Convert to milliseconds
        val currentTime = System.currentTimeMillis()
        val expiredImages = imageCache.values.filter {
            currentTime - it.timestamp > maxAge
        }

        expiredImages.forEach { cachedImage ->
            removeImage(cachedImage.id)
        }

        Log.d("ImageCacheManager", "Cleanup removed ${expiredImages.size} expired images")
        if (expiredImages.isNotEmpty()) {
            notifyCapacityChanged()
        }
    }

    // Private helper methods
    private fun getPerImageBytes(): Long {
        val pixelsPerImage = config.resolution.width.toLong() * config.resolution.height.toLong()
        val bytesPerPixel = when (config.format) {
            ImageFormat.JPEG -> 3L
            ImageFormat.RAW_SENSOR -> 2L
            else -> 3L
        }
        return pixelsPerImage * bytesPerPixel
    }

    private fun shouldEnableCompression(): Boolean {
        return config.bufferStrategy == "memory" || availableMemoryMB < 1024 // Enable if memory strategy or low memory device
    }

    private fun getMaxImageAge(): Int {
        return when (config.bufferStrategy) {
            "memory" -> 5 // 5 minutes for memory strategy
            "balanced" -> 10 // 10 minutes for balanced strategy
            "performance" -> 30 // 30 minutes for performance strategy
            else -> 10
        }
    }

    private fun getAvailableMemoryMB(): Int {
        val safeContext = context ?: return 2048
        val activityManager = safeContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / (1024 * 1024)).toInt()
    }

    private fun removeImage(imageId: String) {
        imageCache[imageId]?.let { cachedImage ->
            memoryUsage.addAndGet(-cachedImage.sizeBytes)
            imageCache.remove(imageId)
        }
    }

    private fun effectiveMaxStagedBytes(configuration: CacheConfiguration): Long {
        val memoryBudget =
            if (maxAllowedMemoryBytes > 0L) {
                maxAllowedMemoryBytes
            } else {
                configuration.memoryUsageEstimate.totalCacheBytes
            }
        val configuredEstimate = configuration.memoryUsageEstimate.totalCacheBytes
        return if (configuredEstimate > 0L) {
            minOf(configuredEstimate, memoryBudget).coerceAtLeast(1L)
        } else {
            memoryBudget.coerceAtLeast(1L)
        }
    }

    private fun imageToByteArray(image: Image, format: Int): ByteArray {
        return when (format) {
            ImageFormat.JPEG -> {
                val buffer = image.planes.first().buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            }
            ImageFormat.RAW_SENSOR -> {
                val buffer = image.planes.first().buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            }
            ImageFormat.YUV_420_888 -> {
                ByteArrayOutputStream().use { output ->
                    image.planes.forEach { plane ->
                        val buffer = plane.buffer.duplicate()
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        output.write(bytes)
                    }
                    output.toByteArray()
                }
            }
            else -> {
                val buffer = image.planes.first().buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            }
        }
    }

    private fun requireCachedImage(imageId: String): CachedImage {
        return imageCache[imageId] ?: throw CaptureSessionException(
            code = "IMAGE_NOT_FOUND",
            message = "No cached capture exists for imageId=$imageId",
        )
    }

    private fun requireAsset(cachedImage: CachedImage, format: String): CachedImageAsset {
        if (format !in setOf("jpeg", "dng")) {
            throw CaptureSessionException(
                code = "FORMAT_NOT_CAPTURED",
                message = "Unknown capture asset format=$format",
            )
        }
        return cachedImage.assets[format] ?: throw CaptureSessionException(
            code = "FORMAT_NOT_CAPTURED",
            message = "Capture ${cachedImage.id} does not contain a $format asset",
        )
    }

    private fun requireFormatPath(format: String, filePath: String) {
        val lowerPath = filePath.lowercase()
        val matches =
            when (format) {
                "jpeg" -> lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")
                "dng" -> lowerPath.endsWith(".dng")
                else -> false
            }
        if (!matches) {
            throw CaptureSessionException(
                code = "FORMAT_MISMATCH",
                message = "$format capture asset has an incompatible destination extension",
            )
        }
    }

    private fun formatName(format: Int): String =
        when (format) {
            ImageFormat.JPEG -> "jpeg"
            ImageFormat.RAW_SENSOR -> "dng"
            else -> throw CaptureSessionException(
                code = "FORMAT_NOT_CAPTURED",
                message = "Unsupported cached image format=$format",
            )
        }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun notifyCapacityChanged() {
        onCapacityChanged?.invoke(getCaptureCapacity())
    }
}

data class CachedImage(
    val id: String,
    val assets: Map<String, CachedImageAsset>,
    val timestamp: Long,
    val sequence: Long,
    val state: CachedImageState,
) {
    val sizeBytes: Long get() = assets.values.sumOf { it.sizeBytes }
}

data class CachedImageAsset(
    val bytes: ByteArray,
    val format: Int,
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}
