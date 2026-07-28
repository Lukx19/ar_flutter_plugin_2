package com.uhg0.ar_flutter_plugin_2.capabilities

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.ar.core.ArCoreApk
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraCapabilityQuerier
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraResolution
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CaptureFormat

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
                "getSupportedSharedCameraResolutions" ->
                    getSupportedSharedCameraResolutions(result)
                "getSupportedFormats" -> getSupportedFormats(result)
                "getSupportedISORange" -> getSupportedISORange(result)
                "getSupportedExposureRange" -> getSupportedExposureRange(result)
                "isResolutionSupported" -> isResolutionSupported(call, result)
                "isFormatSupported" -> isFormatSupported(call, result)
                "getCameraIntrinsics" -> getCameraIntrinsics(result)
                "getDeviceCapabilityProfile" ->
                    result.success(capabilityQuerier.getDeviceCapabilityProfile())
                "getSelectableRearCameras" ->
                    result.success(capabilityQuerier.getSelectableRearCameras())
                "getARCoreAvailability" -> {
                    val availability = ArCoreApk.getInstance().checkAvailability(context)
                    result.success(
                        mapOf(
                            "name" to availability.name,
                            "supported" to availability.isSupported,
                            "transient" to availability.isTransient,
                            "unknown" to availability.isUnknown,
                        ),
                    )
                }
                "saveSharedCameraUnsupported" -> {
                    capabilityQuerier.saveSharedCameraUnsupported(
                        call.argument<String>("reason") ?: "Shared camera is unsupported",
                    )
                    result.success(null)
                }
                "saveSharedCameraSupported" -> {
                    capabilityQuerier.saveSharedCameraSupported()
                    result.success(null)
                }
                "saveRawJpegProbeResult" -> {
                    capabilityQuerier.saveRawJpegProbeResult(
                        call.argument<Boolean>("supported") ?: false,
                        call.argument<String>("reason"),
                    )
                    result.success(null)
                }
                "resetCapabilityProfileForTesting" -> {
                    val isDebuggable =
                        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!isDebuggable) {
                        result.error(
                            "DEBUG_ONLY",
                            "Capability profile reset is available only in debuggable builds",
                            null,
                        )
                    } else {
                        capabilityQuerier.resetCapabilityProfileForTesting()
                        result.success(null)
                    }
                }
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

    private fun getSupportedSharedCameraResolutions(result: MethodChannel.Result) {
        val resolutions = capabilityQuerier.getSupportedSharedCameraResolutions()
        result.success(
            resolutions.map { resolution ->
                mapOf(
                    "width" to resolution.width,
                    "height" to resolution.height,
                )
            },
        )
    }

    private fun getSupportedFormats(result: MethodChannel.Result) {
        val formats = capabilityQuerier.getSupportedFormats()
        val formatStrings = formats.map { it.wireValue }
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

        val format = when (formatString) {
            "jpeg" -> CaptureFormat.JPEG
            "raw+jpeg" -> CaptureFormat.RAW_JPEG
            else -> {
                result.success(false)
                return
            }
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
