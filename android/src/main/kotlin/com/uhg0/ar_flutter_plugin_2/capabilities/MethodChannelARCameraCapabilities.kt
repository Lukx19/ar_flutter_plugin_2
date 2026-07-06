package com.uhg0.ar_flutter_plugin_2.capabilities

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraCapabilityQuerier
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraResolution
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.ImageFormat

class MethodChannelARCameraCapabilities(private val context: Context) : MethodCallHandler {

    companion object {
        const val CHANNEL_NAME = "ar_flutter_plugin_2/camera_capabilities"
    }

    private val capabilityQuerier: CameraCapabilityQuerier = CameraCapabilityQuerier(context)
    private var methodChannel: MethodChannel? = null

    fun setupMethodChannel(binaryMessenger: BinaryMessenger) {
        methodChannel = MethodChannel(binaryMessenger, CHANNEL_NAME).apply {
            setMethodCallHandler(this@MethodChannelARCameraCapabilities)
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getSupportedResolutions" -> getSupportedResolutions(result)
                "getSupportedFormats" -> getSupportedFormats(result)
                "getSupportedISORange" -> getSupportedISORange(result)
                "getSupportedExposureRange" -> getSupportedExposureRange(result)
                "isResolutionSupported" -> isResolutionSupported(call, result)
                "isFormatSupported" -> isFormatSupported(call, result)
                "getCameraIntrinsics" -> getCameraIntrinsics(result)
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("CAMERA_CAPABILITY_ERROR", e.message, e.toString())
        }
    }

    private fun getSupportedResolutions(result: MethodChannel.Result) {
        val resolutions = capabilityQuerier.getSupportedResolutions()
        val resolutionMaps = resolutions.map { resolution ->
            mapOf(
                "width" to resolution.width,
                "height" to resolution.height
            )
        }
        result.success(resolutionMaps)
    }

    private fun getSupportedFormats(result: MethodChannel.Result) {
        val formats = capabilityQuerier.getSupportedFormats()
        val formatStrings = formats.map { it.name }
        result.success(formatStrings)
    }

    private fun getSupportedISORange(result: MethodChannel.Result) {
        val isoRange = capabilityQuerier.getSupportedISORange()
        result.success(isoRange)
    }

    private fun getSupportedExposureRange(result: MethodChannel.Result) {
        val exposureRange = capabilityQuerier.getSupportedExposureRange()
        val rangeMap = mapOf(
            "min" to exposureRange["min"],
            "max" to exposureRange["max"]
        )
        result.success(rangeMap)
    }

    private fun isResolutionSupported(call: MethodCall, result: MethodChannel.Result) {
        val resolutionMap = call.arguments as? Map<String, Any>
        if (resolutionMap == null) {
            result.error("INVALID_ARGUMENTS", "Resolution map is required", null)
            return
        }

        val width = resolutionMap["width"] as? Int
        val height = resolutionMap["height"] as? Int

        if (width == null || height == null) {
            result.error("INVALID_ARGUMENTS", "Width and height are required", null)
            return
        }

        val resolution = CameraResolution(width, height)
        val isSupported = capabilityQuerier.isResolutionSupported(resolution)
        result.success(isSupported)
    }

    private fun isFormatSupported(call: MethodCall, result: MethodChannel.Result) {
        val formatString = call.arguments as? String
        if (formatString == null) {
            result.error("INVALID_ARGUMENTS", "Format string is required", null)
            return
        }

        val format = try {
            ImageFormat.valueOf(formatString.uppercase())
        } catch (e: IllegalArgumentException) {
            result.success(false)
            return
        }

        val isSupported = capabilityQuerier.isFormatSupported(format)
        result.success(isSupported)
    }

    private fun getCameraIntrinsics(result: MethodChannel.Result) {
        try {
            val intrinsics = capabilityQuerier.getCameraIntrinsics()
            if (intrinsics != null) {
                result.success(intrinsics)
            } else {
                result.error("INTRINSICS_ERROR", "Unable to extract camera intrinsics", null)
            }
        } catch (e: Exception) {
            result.error("INTRINSICS_ERROR", "Failed to get camera intrinsics: ${e.message}", null)
        }
    }
}