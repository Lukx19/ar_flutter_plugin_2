import Flutter
import UIKit

/// Services the plugin registers with one engine's messenger and releases
/// when that engine detaches; the latest engine owns each of them.
struct PluginEngineServices {
  var gridShell: VisibilityGridShellRegistry = .shared
  var gridRenderer: GridRendererChannels = .shared
  var sessionLifecycle: SessionLifecycleServices = .shared
  var capture: NativeCaptureRegistration = .shared

  /// Grid shell, grid renderer channels, session log, world map and
  /// background task services, still capture channels.
  func register(messenger: FlutterBinaryMessenger) {
    gridShell.register(messenger: messenger)
    gridRenderer.register(messenger: messenger)
    sessionLifecycle.register(messenger: messenger)
    capture.register(messenger: messenger)
  }

  /// Releases what the engine of [messenger] owns; a later engine's
  /// registrations stay.
  func unregister(messenger: FlutterBinaryMessenger) {
    gridShell.unregister(messenger: messenger)
    gridRenderer.unregister(messenger: messenger)
    sessionLifecycle.unregister(messenger: messenger)
    capture.unregister(messenger: messenger)
  }
}

public class SwiftArFlutterPlugin: NSObject, FlutterPlugin {
  private let services: PluginEngineServices
  /// The engine's messenger from registration. The services own channels by
  /// messenger identity, and during engine teardown `registrar.messenger()`
  /// may already be nil, so detach unregisters with this one. Held strongly
  /// (the messenger does not retain the plugin) so the services' weak owner
  /// references stay valid until detach; cleared there.
  private var messenger: FlutterBinaryMessenger?
  private let lock = NSLock()

  init(messenger: FlutterBinaryMessenger, services: PluginEngineServices = PluginEngineServices()) {
    self.messenger = messenger
    self.services = services
    super.init()
  }

  public static func register(with registrar: FlutterPluginRegistrar) {
    let messenger = registrar.messenger()
    let channel = FlutterMethodChannel(name: "ar_flutter_plugin_2", binaryMessenger: messenger)
    let instance = SwiftArFlutterPlugin(messenger: messenger)
    registrar.addMethodCallDelegate(instance, channel: channel)
    // Published so the engine calls `detachFromEngine(for:)` on teardown.
    registrar.publish(instance)

    let factory = IosARViewFactory(messenger: registrar.messenger())
    registrar.register(factory, withId: "ar_flutter_plugin_2")
    CaptureARViewFactory.register(with: registrar)

    let capabilitiesChannel = FlutterMethodChannel(
      name: ARKitCapabilityChannel.name,
      binaryMessenger: registrar.messenger()
    )
    registrar.addMethodCallDelegate(CameraCapabilitiesHandler(), channel: capabilitiesChannel)
    instance.services.register(messenger: messenger)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    result("iOS " + UIDevice.current.systemVersion)
  }

  /// The engine went away: release the grid shell (channels, frame
  /// observer, parked pull), the grid renderer channels, the session log,
  /// world map and background task services and the still capture channels
  /// it owns, unless a later engine registered since. Uses the messenger kept
  /// from registration, never `registrar.messenger()` (nil while the engine
  /// deallocates).
  public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
    detach()
  }

  /// Unregisters with the registration messenger once; later calls do nothing.
  func detach() {
    lock.lock()
    let owner = messenger
    messenger = nil
    lock.unlock()
    guard let owner = owner else { return }
    services.unregister(messenger: owner)
  }

}

private final class CameraCapabilitiesHandler: NSObject, FlutterPlugin {
  static func register(with registrar: FlutterPluginRegistrar) {}

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case ARKitCapabilityChannel.method:
      result(ARCameraCapabilities.current().channelMap)
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
