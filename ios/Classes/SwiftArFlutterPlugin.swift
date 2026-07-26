import Flutter
import UIKit

public class SwiftArFlutterPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "ar_flutter_plugin_2", binaryMessenger: registrar.messenger())
    let instance = SwiftArFlutterPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    
    let factory = IosARViewFactory(messenger: registrar.messenger())
    registrar.register(factory, withId: "ar_flutter_plugin_2")

    let capabilitiesChannel = FlutterMethodChannel(
      name: "ar_flutter_plugin_2/camera_capabilities",
      binaryMessenger: registrar.messenger()
    )
    registrar.addMethodCallDelegate(CameraCapabilitiesHandler(), channel: capabilitiesChannel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    result("iOS " + UIDevice.current.systemVersion)
  }

}

private final class CameraCapabilitiesHandler: NSObject, FlutterPlugin {
  static func register(with registrar: FlutterPluginRegistrar) {}

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getDeviceCapabilityProfile":
      result([
        "presetVersion": 11,
        "platformSupport": false,
        "unsupportedCode": "UNSUPPORTED_PLATFORM",
        "fingerprint": "",
        "primaryCameraId": "",
        "sharedResolutionValidationComplete": false,
        "sharedCameraProbeStatus": "unsupported",
        "sharedCameraProbeError": "iOS capture is not implemented.",
        "validatedSharedResolutions": [],
        "validatedRawJpegResolutions": [],
        "rawJpegProbeStatus": "unsupported",
        "rawJpegProbeError": "iOS capture is not implemented.",
        "rawCapture": false,
        "manualSensorControls": false,
        "flash": false,
        "primaryPhysicalCameraIds": [],
        "logicalMultiCamera": false,
        "concurrentCameraIdSets": [],
        "rearConcurrentCameraIds": [],
        "cached": false,
      ])
    case "getSupportedFormats", "getSupportedResolutions", "getSupportedSharedCameraResolutions", "getSupportedISORange":
      result([])
    case "isFormatSupported", "isResolutionSupported":
      result(false)
    case "getARCoreAvailability":
      result([
        "name": "UNSUPPORTED_PLATFORM",
        "supported": false,
        "transient": false,
        "unknown": false,
      ])
    case "getSupportedExposureRange", "getCameraIntrinsics":
      result(FlutterError(code: "UNSUPPORTED_PLATFORM", message: "iOS capture is not implemented.", details: nil))
    case "saveSharedCameraUnsupported", "saveSharedCameraSupported", "saveRawJpegProbeResult", "resetCapabilityProfileForTesting":
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
