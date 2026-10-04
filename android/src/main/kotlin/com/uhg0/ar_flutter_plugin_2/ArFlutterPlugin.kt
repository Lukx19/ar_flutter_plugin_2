package com.uhg0.ar_flutter_plugin_2

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import com.uhg0.ar_flutter_plugin_2.capabilities.MethodChannelARCameraCapabilities
import com.uhg0.ar_flutter_plugin_2.capture.NativeCapturePreviewOwnerV2
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineAuthority

class ArFlutterPlugin: FlutterPlugin, ActivityAware {
    private var activity: Activity? = null
    private var lifecycle: Lifecycle? = null
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var cameraCapabilities: MethodChannelARCameraCapabilities? = null
    private var nativeCapturePreviewChannel: MethodChannel? = null
    private val CommittedBaselineAuthority = CommittedBaselineAuthority()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding
        val previewOwner = NativeCapturePreviewOwnerV2(binding.applicationContext.filesDir)
        nativeCapturePreviewChannel = MethodChannel(
            binding.binaryMessenger,
            "ar_flutter_plugin_2/native_capture_preview",
        ).apply {
            setMethodCallHandler { call, result ->
                if (call.method !in setOf("materializeNativeCapturePreview", "beginNativeCapturePreviewDeletion",
                        "commitNativeCapturePreviewDeletion", "rollbackNativeCapturePreviewDeletion",
                        "reconcileNativeCapturePreviews")) {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                Thread({
                    runCatching {
                        when (call.method) {
                            "reconcileNativeCapturePreviews" -> {
                                previewOwner.reconcile(requireNotNull(call.argument<List<String>>("references")))
                                null
                            }
                            "materializeNativeCapturePreview" -> previewOwner.materialize(
                                requireNotNull(call.argument<String>("manifestId")),
                                requireNotNull(call.argument<String>("captureId")),
                            )
                            "beginNativeCapturePreviewDeletion" -> previewOwner.beginDeletion(
                                requireNotNull(call.argument<List<String>>("references")),
                            )
                            "commitNativeCapturePreviewDeletion" -> {
                                previewOwner.commitDeletion(requireNotNull(call.argument<String>("token")))
                                null
                            }
                            else -> {
                                previewOwner.rollbackDeletion(requireNotNull(call.argument<String>("token")))
                                null
                            }
                        }
                    }.onSuccess(result::success)
                        .onFailure { error -> result.error("NATIVE_CAPTURE_PREVIEW_FAILED", error.message, null) }
                }, "capture3d-v2-preview-reader").apply { isDaemon = true }.start()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        nativeCapturePreviewChannel?.setMethodCallHandler(null)
        nativeCapturePreviewChannel = null
        flutterPluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        lifecycle = (activity as LifecycleOwner).lifecycle
        
        // Enregistrer la factory une fois que nous avons l'activité et le lifecycle
        flutterPluginBinding?.let { flutterBinding ->
            flutterBinding.platformViewRegistry.registerViewFactory(
                "ar_flutter_plugin_2",
                ArViewFactory(
                    messenger = flutterBinding.binaryMessenger,
                    activity = activity!!,
                    lifecycle = lifecycle!!,
                    CommittedBaselineAuthority = CommittedBaselineAuthority,
                )
            )
            
            // Initialize camera capabilities method channel
            cameraCapabilities = MethodChannelARCameraCapabilities(activity!!.applicationContext)
            cameraCapabilities?.setupMethodChannel(flutterBinding.binaryMessenger)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        lifecycle = null
        cameraCapabilities = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        lifecycle = (activity as LifecycleOwner).lifecycle
        
        // Réenregistrer la factory après les changements de configuration
        flutterPluginBinding?.let { flutterBinding ->
            flutterBinding.platformViewRegistry.registerViewFactory(
                "ar_flutter_plugin_2",
                ArViewFactory(
                    messenger = flutterBinding.binaryMessenger,
                    activity = activity!!,
                    lifecycle = lifecycle!!,
                    CommittedBaselineAuthority = CommittedBaselineAuthority,
                )
            )
            
            // Re-initialize camera capabilities method channel after config changes
            cameraCapabilities = MethodChannelARCameraCapabilities(activity!!.applicationContext)
            cameraCapabilities?.setupMethodChannel(flutterBinding.binaryMessenger)
        }
    }

    override fun onDetachedFromActivity() {
        activity = null
        lifecycle = null
        cameraCapabilities = null
    }
}
