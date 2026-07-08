package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import android.util.Size
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraCapabilityQuerier
import io.github.sceneview.ar.ARSceneView
import java.io.ByteArrayOutputStream

internal class CaptureSessionException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

class ArCaptureSession(
    private val sceneView: ARSceneView,
    private val capabilityQuerier: CameraCapabilityQuerier,
) {
    private var config: CaptureConfig? = null
    private var isCaptureInProgress = false
    private val byteCache = CaptureByteCache()

    fun initialize(configMap: Map<String, Any?>) {
        val parsedConfig = CaptureConfig.fromMap(configMap)
        config = parsedConfig
        byteCache.initialize(parsedConfig)
    }

    fun captureImage(): Map<String, Any?> {
        requireInitialized()
        if (isCaptureInProgress) {
            throw CaptureSessionException(
                code = "CAPTURE_IN_PROGRESS",
                message = "A capture is already in progress",
            )
        }

        isCaptureInProgress = true
        try {
            val frame = sceneView.session?.update() ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "No AR frame is available for capture",
            )
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                throw CaptureSessionException(
                    code = "NOT_TRACKING",
                    message = "AR camera is not tracking",
                )
            }

            val image = try {
                frame.acquireCameraImage()
            } catch (error: NotYetAvailableException) {
                throw CaptureSessionException(
                    code = "CAPTURE_FAILED",
                    message = "Camera image is not available yet",
                )
            }

            image.use { acquiredImage ->
                val jpegBytes = imageToJpegBytes(acquiredImage, config!!.jpegQuality)
                val timestampMs = System.currentTimeMillis()
                val imageId = byteCache.nextImageId(timestampMs)
                byteCache.cacheCapture(
                    imageId = imageId,
                    bytes = jpegBytes,
                    width = acquiredImage.width,
                    height = acquiredImage.height,
                    timestampMs = timestampMs,
                )

                return mapOf(
                    "imageId" to imageId,
                    "pose" to buildPoseMap(camera),
                    "resolution" to mapOf(
                        "width" to acquiredImage.width,
                        "height" to acquiredImage.height,
                    ),
                    "format" to "jpeg",
                    "captureTimestampMs" to timestampMs,
                    "imageSizeBytes" to jpegBytes.size,
                    "isHighResolution" to false,
                    "filePath" to null,
                )
            }
        } finally {
            isCaptureInProgress = false
        }
    }

    fun getImageData(imageId: String, format: String): ByteArray? {
        requireInitialized()
        return byteCache.getImageData(imageId, format)
    }

    fun getImageSize(imageId: String): Map<String, Any>? {
        requireInitialized()
        return byteCache.getImageSize(imageId)
    }

    fun saveImageToFile(imageId: String, filePath: String, format: String): Boolean {
        requireInitialized()
        return byteCache.saveImageToFile(imageId, filePath, format)
    }

    fun getCameraIntrinsics(): Map<String, Any>? {
        requireInitialized()
        val captureConfig = config ?: throw CaptureSessionException(
            code = "CAPTURE_NOT_INITIALIZED",
            message = "Capture session is not initialized",
        )

        return capabilityQuerier.getCameraIntrinsicsForSize(
            Size(captureConfig.resolutionWidth, captureConfig.resolutionHeight),
        ) ?: capabilityQuerier.getCameraIntrinsics()
    }

    fun dispose() {
        byteCache.dispose()
        config = null
    }

    private fun requireInitialized() {
        if (config == null) {
            throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "Capture session is not initialized",
            )
        }
    }

    private fun buildPoseMap(camera: com.google.ar.core.Camera): Map<String, Any?> {
        val pose = camera.pose
        val transform = FloatArray(16)
        pose.toMatrix(transform, 0)

        return mapOf(
            "position" to mapOf(
                "x" to pose.tx().toDouble(),
                "y" to pose.ty().toDouble(),
                "z" to pose.tz().toDouble(),
            ),
            "rotation" to mapOf(
                "x" to pose.rotationQuaternion[0].toDouble(),
                "y" to pose.rotationQuaternion[1].toDouble(),
                "z" to pose.rotationQuaternion[2].toDouble(),
                "w" to pose.rotationQuaternion[3].toDouble(),
            ),
            "transform" to transform.map { it.toDouble() },
            "timestampMs" to System.currentTimeMillis(),
            "confidence" to 1.0,
            "isTracking" to true,
        )
    }

    private fun imageToJpegBytes(image: Image, jpegQuality: Int): ByteArray {
        return when (image.format) {
            ImageFormat.JPEG -> image.planes.first().buffer.let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }

            ImageFormat.YUV_420_888 -> {
                val nv21 = yuv420888ToNv21(image)
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                ByteArrayOutputStream().use { output ->
                    if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, output)) {
                        error("Failed to encode JPEG")
                    }
                    output.toByteArray()
                }
            }

            else -> error("Unsupported camera image format: ${image.format}")
        }
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = image.width * image.height
        val uvSize = image.width * image.height / 2
        val output = ByteArray(ySize + uvSize)

        copyPlane(
            plane = yPlane,
            width = image.width,
            height = image.height,
            output = output,
            offset = 0,
            pixelStrideOut = 1,
        )
        interleaveChromaPlanes(
            uPlane = uPlane,
            vPlane = vPlane,
            width = image.width / 2,
            height = image.height / 2,
            output = output,
            offset = ySize,
        )

        return output
    }

    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        pixelStrideOut: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputOffset = offset

        val rowData = ByteArray(rowStride)
        for (row in 0 until height) {
            val bytesPerRow = if (pixelStride == 1 && pixelStrideOut == 1) width else (width - 1) * pixelStride + 1
            buffer.position(row * rowStride)
            buffer.get(rowData, 0, bytesPerRow)

            if (pixelStride == 1 && pixelStrideOut == 1) {
                System.arraycopy(rowData, 0, output, outputOffset, width)
                outputOffset += width
            } else {
                for (column in 0 until width) {
                    output[outputOffset] = rowData[column * pixelStride]
                    outputOffset += pixelStrideOut
                }
            }
        }
    }

    private fun interleaveChromaPlanes(
        uPlane: Image.Plane,
        vPlane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        var outputOffset = offset

        for (row in 0 until height) {
            for (column in 0 until width) {
                val uIndex = row * uRowStride + column * uPixelStride
                val vIndex = row * vRowStride + column * vPixelStride
                output[outputOffset++] = vBuffer.get(vIndex)
                output[outputOffset++] = uBuffer.get(uIndex)
            }
        }
    }
}

private inline fun <T : Image, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } catch (error: Exception) {
        Log.e("ArCaptureSession", "Capture pipeline failed", error)
        throw error
    } finally {
        close()
    }
}
