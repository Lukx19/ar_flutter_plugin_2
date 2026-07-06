package com.uhg0.ar_flutter_plugin_2.capture

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class MemoryUsageEstimate(
    val perImageBytes: Long,
    val totalCacheBytes: Long,
    val overheadBytes: Long,
    val totalBytes: Long
) {
    val totalMB: Double get() = totalBytes / (1024.0 * 1024.0)
    val isWithinBounds: Boolean get() = totalMB < 200.0 // 200MB limit
}

data class CacheConfiguration(
    val maxCacheSize: Int,
    val bufferStrategy: String,
    val memoryUsageEstimate: MemoryUsageEstimate,
    val compressionEnabled: Boolean,
    val maxImageAgeMins: Int
)

class ImageCacheManager(
    private val config: ParsedCaptureConfig,
    private val context: Context
) {
    private val imageCache = ConcurrentHashMap<String, CachedImage>()
    private val memoryUsage = AtomicLong(0L)
    private var cacheConfiguration: CacheConfiguration? = null

    // Memory management
    private val availableMemoryMB: Int = getAvailableMemoryMB()
    private var maxAllowedMemoryBytes: Long = 0L

    init {
        cacheConfiguration = configure()
        maxAllowedMemoryBytes = (availableMemoryMB * 0.3 * 1024 * 1024).toLong() // Use 30% of available memory
        Log.i("ImageCacheManager", "Initialized with config: $cacheConfiguration")
    }

    /// Configure cache based on ARCaptureConfig settings and available memory
    fun configure(): CacheConfiguration {
        val baseEstimate = estimateMemoryUsage(config.maxCacheSize)

        // Adjust for memory constraints
        val adjustedConfig = if (baseEstimate.totalMB > availableMemoryMB * 0.3) {
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
        val perImageBytes = getPerImageBytes()
        val maxImages = (maxAllowedMemoryBytes / perImageBytes).toInt()
        return Math.min(maxImages, config.maxCacheSize)
    }

    /// Estimate memory usage for given cache size
    fun estimateMemoryUsage(cacheSize: Int = config.maxCacheSize): MemoryUsageEstimate {
        val perImageBytes = getPerImageBytes()
        val totalCacheBytes = perImageBytes * cacheSize
        val overheadBytes = cacheSize * 1024L // 1KB overhead per image for metadata
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
        if (imageCache.size >= (cacheConfiguration?.maxCacheSize ?: 10)) {
            evictOldestImages(1)
        }

        return try {
            val cachedImage = CachedImage(
                id = imageId,
                image = cloneImage(image),
                format = format,
                timestamp = System.currentTimeMillis(),
                sizeBytes = estimateImageSize(image)
            )

            // Check if adding this image would exceed memory limits
            val newMemoryUsage = memoryUsage.get() + cachedImage.sizeBytes
            if (newMemoryUsage > maxAllowedMemoryBytes) {
                val imagesToEvict = calculateEvictionCount(cachedImage.sizeBytes)
                evictOldestImages(imagesToEvict)
            }

            imageCache[imageId] = cachedImage
            memoryUsage.addAndGet(cachedImage.sizeBytes)

            // Optionally compress if enabled
            if (cacheConfiguration?.compressionEnabled == true && format == ImageFormat.JPEG) {
                compressImage(cachedImage)
            }

            Log.d("ImageCacheManager", "Cached image $imageId (${cachedImage.sizeBytes} bytes), total memory: ${memoryUsage.get() / 1024 / 1024}MB")
            true
        } catch (e: Exception) {
            Log.e("ImageCacheManager", "Failed to cache image $imageId", e)
            false
        }
    }

    /// Get image from cache
    fun getImage(imageId: String): Image? {
        return imageCache[imageId]?.image
    }

    /// Get image data as byte array
    fun getImageData(imageId: String): ByteArray? {
        val cachedImage = imageCache[imageId] ?: return null
        return imageToByteArray(cachedImage.image, cachedImage.format)
    }

    /// Save image to file
    fun saveImageToFile(imageId: String, filePath: String, format: Int): Boolean {
        val cachedImage = imageCache[imageId] ?: return false

        return try {
            val imageData = imageToByteArray(cachedImage.image, format)
            java.io.File(filePath).writeBytes(imageData)
            Log.d("ImageCacheManager", "Saved image $imageId to $filePath")
            true
        } catch (e: Exception) {
            Log.e("ImageCacheManager", "Failed to save image $imageId", e)
            false
        }
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
    }

    // Private helper methods
    private fun getPerImageBytes(): Long {
        val pixelsPerImage = config.resolution.width * config.resolution.height
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
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / (1024 * 1024)).toInt()
    }

    private fun calculateEvictionCount(newImageSize: Long): Int {
        val requiredFreeSpace = newImageSize * 2 // Double the space to prevent frequent evictions
        val currentOverage = memoryUsage.get() + requiredFreeSpace - maxAllowedMemoryBytes

        if (currentOverage <= 0) return 0

        val averageImageSize = if (imageCache.isEmpty()) getPerImageBytes() else memoryUsage.get() / imageCache.size
        return Math.max(1, (currentOverage / averageImageSize).toInt())
    }

    private fun evictOldestImages(count: Int) {
        val sortedImages = imageCache.values.sortedBy { it.timestamp }
        repeat(Math.min(count, sortedImages.size)) { index ->
            removeImage(sortedImages[index].id)
        }
    }

    private fun removeImage(imageId: String) {
        imageCache[imageId]?.let { cachedImage ->
            cachedImage.image.close()
            memoryUsage.addAndGet(-cachedImage.sizeBytes)
            imageCache.remove(imageId)
        }
    }

    private fun cloneImage(original: Image): Image {
        // Implementation would clone the Image object for safe caching
        // This is a simplified placeholder - in practice, you'd need to copy the image data
        return original
    }

    private fun compressImage(cachedImage: CachedImage) {
        // Implementation would compress JPEG images to save memory
        // This is a placeholder for compression logic
    }

    private fun estimateImageSize(image: Image): Long {
        return getPerImageBytes() // Simplified estimation
    }

    private fun imageToByteArray(image: Image, format: Int): ByteArray {
        // Implementation would convert Image to byte array based on format
        // This is a placeholder
        return ByteArray(0)
    }
}

data class CachedImage(
    val id: String,
    val image: Image,
    val format: Int,
    val timestamp: Long,
    val sizeBytes: Long
)