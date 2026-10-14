import ARKit
import Flutter
import MetalKit
import UIKit

/// Capture-channel methods owned by another component (still capture). The
/// session host keeps `initializeCapture` and `dispose` for the pose stream and
/// then offers them here too; every other method goes here first and replies
/// `FlutterMethodNotImplemented` when nothing handles it.
protocol CaptureChannelExtension: AnyObject {
  /// Returns true when the call was handled; the extension then owns [result].
  func handleCaptureCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) -> Bool
  /// The view is going away; release capture resources. Main thread.
  func captureViewWillDispose()
}

/// Everything a capture-channel extension may use for one view.
struct CaptureChannelContext {
  let viewId: Int64
  let channel: FlutterMethodChannel
  let frameSource: ARFrameSource
}

/// Set once at plugin registration by the component that owns still capture.
final class CaptureChannelExtensionRegistry {
  static let shared = CaptureChannelExtensionRegistry()
  var makeExtension: ((CaptureChannelContext) -> CaptureChannelExtension)?
}

/// The capture route's platform view on iOS: one `ARSession` ([ARSessionHost])
/// drawn by a plain Metal `MTKView` ([CaptureRenderLoop]), reached through the
/// [CaptureSessionSource] seam. Serves the per-view
/// channels the capture route uses on Android with the same method names and
/// reply shapes:
///
/// - `arsession_<id>`: `init`, `showPlanes`, `getCameraPose`, `pauseSession` /
///   `disableCamera`, `resumeSession` / `enableCamera`, `dispose`; sends
///   `onError`.
/// - `arcapture_<id>`: `initializeCapture`, `dispose`, and the host replies in
///   [CaptureChannelHostReplies] (`replayNativeCaptureRecoveryV2`); sends
///   `onPoseBatch` (`pose_batch_v2`, 30 Hz, one batch in flight, none while
///   the session is paused).
/// - `arobjects_<id>`, `aranchors_<id>`: `init` only (the capture route does
///   not place objects or cloud anchors on iOS).
///
/// Every other method replies `FlutterMethodNotImplemented`, never nil.
final class CaptureARView: NSObject, FlutterPlatformView {
  let viewId: Int64
  let source: CaptureSessionSource
  private let metalView: MTKView
  private let renderLoop: CaptureRenderLoop?
  private let sessionChannel: FlutterMethodChannel
  private let captureChannel: FlutterMethodChannel
  private let objectChannel: FlutterMethodChannel
  private let anchorChannel: FlutterMethodChannel
  private let poseStream: PoseStreamObserver
  private let poseSink: PoseBatchSink
  private var captureExtension: CaptureChannelExtension?
  private var disposed = false

  init(frame: CGRect, viewIdentifier viewId: Int64, arguments: Any?, binaryMessenger messenger: FlutterBinaryMessenger) {
    self.viewId = viewId
    source = CaptureARView.makeSource(creationParams: arguments)
    sessionChannel = FlutterMethodChannel(name: "arsession_\(viewId)", binaryMessenger: messenger)
    captureChannel = FlutterMethodChannel(name: "arcapture_\(viewId)", binaryMessenger: messenger)
    objectChannel = FlutterMethodChannel(name: "arobjects_\(viewId)", binaryMessenger: messenger)
    anchorChannel = FlutterMethodChannel(name: "aranchors_\(viewId)", binaryMessenger: messenger)

    let channel = captureChannel
    poseSink = PoseBatchSink(dispatcher: PoseBatchDispatcher { batch, acknowledge in
      channel.invokeMethod("onPoseBatch", arguments: CaptureARView.poseBatchArguments(batch)) { _ in
        acknowledge()
      }
    })
    let sink = poseSink
    poseStream = PoseStreamObserver { [weak sink] sample in
      DispatchQueue.main.async { sink?.offer(sample) }
    }

    let device = MTLCreateSystemDefaultDevice()
    metalView = MTKView(frame: frame, device: device)
    metalView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    metalView.backgroundColor = .black
    if let device = device {
      renderLoop = try? CaptureRenderLoop(device: device, source: source)
    } else {
      renderLoop = nil
    }
    super.init()

    renderLoop?.configure(metalView)
    source.addFrameObserver(poseStream)
    source.onSessionFailure = { [weak self] error in
      NSLog("[CaptureARView] ARKit session failed: %@", error.localizedDescription)
      self?.sessionChannel.invokeMethod("onError", arguments: [CaptureViewMessageCode.sessionFailure(error)])
    }
    if let makeExtension = CaptureChannelExtensionRegistry.shared.makeExtension {
      captureExtension = makeExtension(
        CaptureChannelContext(viewId: viewId, channel: captureChannel, frameSource: source))
    }
    sessionChannel.setMethodCallHandler { [weak self] call, result in
      guard let self = self else { return result(FlutterMethodNotImplemented) }
      self.handleSessionCall(call, result: result)
    }
    captureChannel.setMethodCallHandler { [weak self] call, result in
      guard let self = self else { return result(FlutterMethodNotImplemented) }
      self.handleCaptureCall(call, result: result)
    }
    let initOnly: FlutterMethodCallHandler = { call, result in
      result(call.method == "init" ? nil : FlutterMethodNotImplemented)
    }
    objectChannel.setMethodCallHandler(initOnly)
    anchorChannel.setMethodCallHandler(initOnly)

    if renderLoop == nil {
      DispatchQueue.main.async { [weak self] in
        self?.sessionChannel.invokeMethod("onError", arguments: [CaptureViewMessageCode.metalUnavailable])
      }
    }
    source.start()
  }

  /// The frame source of the view: the live session host.
  static func makeSource(creationParams: Any?) -> CaptureSessionSource {
    let options = ARSessionOptions(creationParams: creationParams)
    let plan = ARSessionConfigurationPlan(options: options, capabilities: ARCameraCapabilities.current())
    return ARSessionHost(plan: plan)
  }

  deinit {
    dispose()
  }

  func view() -> UIView { metalView }

  static func poseBatchArguments(_ batch: PoseBatch) -> [String: Any] {
    [
      "wireVersion": PoseBatchWire.version,
      "sampleCount": batch.sampleCount,
      "sampleBytes": FlutterStandardTypedData(bytes: batch.sampleBytes),
      "droppedOldestCount": batch.droppedOldestCount,
    ]
  }

  // MARK: arsession_<id>

  private func handleSessionCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "init":
      // Android semantics: configuration only; a paused session stays paused.
      if !disposed { source.initialize() }
      result(nil)
    case "showPlanes":
      result(nil)
    case "getCameraPose":
      guard !disposed, let frame = source.currentFrame else { return result(nil) }
      result(CaptureARView.columnMajor(frame.cameraTransform))
    case "pauseSession", "disableCamera":
      pauseSession()
      result(nil)
    case "resumeSession", "enableCamera":
      guard !disposed else {
        return result(FlutterError(code: "SESSION_DISPOSED", message: "The AR view was disposed.", details: nil))
      }
      resumeSession()
      result(nil)
    case "dispose":
      dispose()
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func pauseSession() {
    guard !disposed else { return }
    metalView.isPaused = true
    poseSink.pause()
    source.pause()
  }

  private func resumeSession() {
    metalView.isPaused = false
    poseSink.resume()
    source.resume()
  }

  // MARK: arcapture_<id>

  private func handleCaptureCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "initializeCapture":
      guard !disposed else {
        return result(FlutterError(code: "CAPTURE_NOT_INITIALIZED", message: "The AR view was disposed.", details: nil))
      }
      let config = call.arguments as? [String: Any] ?? [:]
      poseSink.open()
      if config["enablePoseStream"] as? Bool ?? true {
        poseStream.enable()
      } else {
        poseStream.disable()
      }
      if captureExtension?.handleCaptureCall(call, result: result) == true { return }
      result([
        "mode": "previewFallback",
        "warning": CaptureViewMessageCode.stillCaptureUnavailable,
      ])
    case "dispose":
      poseStream.disable()
      poseSink.close()
      if captureExtension?.handleCaptureCall(call, result: result) == true { return }
      result(nil)
    default:
      if !disposed, captureExtension?.handleCaptureCall(call, result: result) == true { return }
      if let reply = CaptureChannelHostReplies.reply(for: call.method) {
        return result(reply)
      }
      result(FlutterMethodNotImplemented)
    }
  }

  // MARK: Teardown

  /// Idempotent. Stops the pose stream, pauses and releases the session and
  /// the render loop, and detaches every channel handler.
  private func dispose() {
    guard !disposed else { return }
    disposed = true
    poseStream.disable()
    poseSink.close()
    source.removeFrameObserver(poseStream)
    source.onSessionFailure = nil
    captureExtension?.captureViewWillDispose()
    captureExtension = nil
    source.dispose()
    metalView.isPaused = true
    metalView.delegate = nil
    sessionChannel.setMethodCallHandler(nil)
    captureChannel.setMethodCallHandler(nil)
    objectChannel.setMethodCallHandler(nil)
    anchorChannel.setMethodCallHandler(nil)
  }

  static func columnMajor(_ matrix: simd_float4x4) -> [Double] {
    [matrix.columns.0, matrix.columns.1, matrix.columns.2, matrix.columns.3]
      .flatMap { [Double($0.x), Double($0.y), Double($0.z), Double($0.w)] }
  }
}

/// Creates [CaptureARView]s for the view type `capture3d/capture_ar_view`.
final class CaptureARViewFactory: NSObject, FlutterPlatformViewFactory {
  static let viewType = "capture3d/capture_ar_view"

  private let messenger: FlutterBinaryMessenger

  init(messenger: FlutterBinaryMessenger) {
    self.messenger = messenger
    super.init()
  }

  /// Registers the capture view factory. The ARKit capabilities query is
  /// served on [ARKitCapabilityChannel] by the plugin registration.
  static func register(with registrar: FlutterPluginRegistrar) {
    registrar.register(CaptureARViewFactory(messenger: registrar.messenger()), withId: viewType)
  }

  func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
    CaptureARView(frame: frame, viewIdentifier: viewId, arguments: args, binaryMessenger: messenger)
  }

  func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
    FlutterStandardMessageCodec.sharedInstance()
  }
}
