import Flutter
import ARKit
import Darwin
import UIKit

/// Services the plugin registers with one engine's messenger and releases
/// when that engine detaches; the latest engine owns each of them.
struct PluginEngineServices {
  var gridShell: VisibilityGridShellRegistry = .shared
  var gridRenderer: GridRendererChannels = .shared
  var sessionLifecycle: SessionLifecycleServices = .shared
  var capture: NativeCaptureRegistration = .shared
  var markerDetection: GridMarkerDetection = .shared

  /// Grid shell with marker detection, grid renderer channels, session log,
  /// world map and background task services, still capture channels.
  func register(messenger: FlutterBinaryMessenger) {
    gridShell.register(messenger: messenger)
    markerDetection.install(registry: gridShell)
    gridRenderer.register(messenger: messenger)
    sessionLifecycle.register(messenger: messenger)
    capture.register(messenger: messenger)
  }

  /// Releases what the engine of [messenger] owns; a later engine's
  /// registrations stay.
  func unregister(messenger: FlutterBinaryMessenger) {
    gridShell.unregister(messenger: messenger)
    if gridShell.activeShell == nil { markerDetection.uninstall() }
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
  /// observer, parked pull) and its marker detection, the grid renderer channels, the session log,
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

  private func profileStore() -> CameraCapabilityProfileStore {
    let snapshot = CameraCapabilityQueries.current()
    let still = snapshot.stillSize.map { ["width": Int($0.width), "height": Int($0.height)] }
    var system = utsname()
    uname(&system)
    let machineCapacity = MemoryLayout.size(ofValue: system.machine)
    let model = withUnsafePointer(to: &system.machine) {
      $0.withMemoryRebound(to: CChar.self, capacity: machineCapacity) {
        String(cString: $0)
      }
    }
    let fingerprint = "\(model)|\(UIDevice.current.systemVersion)|arkit-primary|\(still?["width"] ?? 0)x\(still?["height"] ?? 0)"
    return CameraCapabilityProfileStore(
      defaults: .standard, fingerprint: fingerprint,
      supported: ARWorldTrackingConfiguration.isSupported,
      advertisedStillResolutions: still.map { [$0] } ?? [],
      manualSensorControls: snapshot.isoRange != nil && snapshot.exposureRange != nil)
  }

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case ARKitCapabilityChannel.method:
      // During recorded playback Dart sends the playback creation parameters.
      result(ARCameraCapabilities.forQuery(call.arguments).channelMap)
    case "getDeviceCapabilityProfile":
      var profile = profileStore().profile
      profile["capabilityReport"] = CameraCapabilityQueries.capabilityReport()
      result(profile)
    case let method where CameraCapabilityQueries.handles(method):
      // Android-shaped queries answered from ARKit and its configurable camera.
      switch CameraCapabilityQueries.answer(method, arguments: call.arguments, snapshot: CameraCapabilityQueries.current()) {
      case .success(let value): result(value)
      case .failure(let error): result(FlutterError(code: error.code, message: error.message, details: nil))
      }
    case "getSupportedSharedCameraResolutions":
      result(profileStore().validatedResolutions)
    case "getARCoreAvailability":
      result([
        "name": "UNSUPPORTED_PLATFORM",
        "supported": false,
        "transient": false,
        "unknown": false,
      ])
    case "saveSharedCameraSupported":
      profileStore().saveSupported(arguments: call.arguments)
      result(nil)
    case "saveSharedCameraUnsupported":
      let arguments = call.arguments as? [String: Any]
      profileStore().saveUnsupported(reason: arguments?["reason"] as? String)
      result(nil)
    case "resetCapabilityProfileForTesting":
      profileStore().reset()
      result(nil)
    case "saveRawJpegProbeResult":
      // This ARKit route delivers processed JPEGs only. This says nothing
      // about ProRAW support in a separate AVCaptureSession.
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
