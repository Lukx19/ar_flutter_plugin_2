import ARKit
import Flutter
import Foundation
import QuartzCore
import simd

/// Registers native still capture with the plugin: the `arcapture_<id>`
/// extension of every capture view, the app-wide preview channel and the
/// store format versions channel. `SwiftArFlutterPlugin` registers each
/// engine's messenger and unregisters it on that engine's detach; the latest
/// engine owns the channels.
final class NativeCaptureRegistration {
  static let shared = NativeCaptureRegistration()

  private let lock = NSLock()
  private var channels: [FlutterMethodChannel] = []
  private weak var owner: FlutterBinaryMessenger?

  func register(messenger: FlutterBinaryMessenger) {
    CaptureChannelExtensionRegistry.shared.makeExtension = { NativeCaptureChannel(context: $0) }
    let created = [
      NativeCapturePreviewChannel.register(messenger: messenger),
      NativeStoreFormatVersions.register(messenger: messenger),
    ]
    lock.lock()
    let replaced = channels
    channels = created
    owner = messenger
    lock.unlock()
    replaced.forEach { $0.setMethodCallHandler(nil) }
  }

  /// The engine of [messenger] detached: removes its channel handlers; a
  /// later engine's registration stays. Capture views close their own
  /// `arcapture_<id>` channels on dispose.
  func unregister(messenger: FlutterBinaryMessenger) {
    lock.lock()
    guard let current = owner, current === messenger else {
      lock.unlock()
      return
    }
    let removed = channels
    channels = []
    owner = nil
    lock.unlock()
    removed.forEach { $0.setMethodCallHandler(nil) }
  }

  func isRegistered(messenger: FlutterBinaryMessenger) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return owner === messenger && !channels.isEmpty
  }
}

/// The Android runtime camera controls of `arcapture_<id>` (Camera2 on
/// Android) answered on the camera ARKit runs ([CameraControlBridge]).
/// Without a configurable camera (simulator, recorded playback) every one
/// answers the defined `CONTROL_UNSUPPORTED` error.
enum CameraControlMethods {
  static let errorCode = CameraControlError.unsupportedCode
  static var methods: Set<String> { CameraControlBridge.methods }

  static func unavailable(_ method: String, reason: String) -> FlutterError {
    FlutterError(
      code: errorCode, message: "\(method) is not available: \(reason)",
      details: ["platform": "ios", "method": method])
  }

  static func error(_ error: CameraControlError) -> FlutterError {
    FlutterError(code: error.code, message: error.message, details: ["platform": "ios"])
  }
}

/// Debug routes of the Android capture channel (synthetic exposure and pose
/// fixtures for the tablet campaigns) that iOS does not provide.
enum AndroidOnlyCaptureDebugRoutes {
  static let errorCode = "DEBUG_ROUTE_UNSUPPORTED"
  static let methods: Set<String> = [
    "debugNativeCaptureV2Synthetic", "debugNativeCaptureV2AdvanceRecovery", "debugNativeCaptureV2CompleteDeferred",
    "debugSyntheticPoseFixture", "debugClearSyntheticPoseFixture",
  ]

  static func error(_ method: String) -> FlutterError {
    FlutterError(code: errorCode, message: "\(method) is an Android debug route; iOS has none.",
                 details: ["platform": "ios", "method": method])
  }
}

/// `arcapture_<id>` still capture of one iOS capture view: the durable
/// `native_capture_v2` admission wire (the production shutter), the legacy
/// staged route (`captureHighResImage`, `persistCapture`, `discardCapture`),
/// capacity, performance snapshot and the photo saved event per committed
/// durable photo. Main thread only; durable work runs on the binding queue.
final class NativeCaptureChannel: CaptureChannelExtension {
  private let context: CaptureChannelContext
  private var config: StillCaptureConfig?
  private var cache: StagedStillCache?
  private var stills: HighResolutionStillExposure?
  private var binding: NativeCaptureBinding?
  private var pauseObserver: SessionPauseObserver?
  private var captureInProgress = false
  private var disposed = false
  private let previewOwner = NativeCapturePreviewOwner()
  private let openResources: () throws -> NativeCaptureRootResources
  private let controlDevice: () -> CameraControlDevice?
  private var controls: CameraControlBridge?

  /// [openResources] opens the durable root (tests pass a temporary one);
  /// [controlDevice] gives the AR session's configurable camera.
  init(context: CaptureChannelContext,
       openResources: @escaping () throws -> NativeCaptureRootResources = {
         try NativeCaptureBinding.productionResources()
       },
       controlDevice: @escaping () -> CameraControlDevice? = { ARKitCaptureControlDevice.primary() }) {
    self.context = context
    self.openResources = openResources
    self.controlDevice = controlDevice
  }

  func captureViewWillDispose() {
    guard !disposed else { return }
    disposed = true
    // The configurable camera is shared by the process: return it to auto
    // (torch off) so a later view or screen never inherits manual settings.
    if let controls = controls {
      do { try controls.reset() } catch { NSLog("capture3d camera controls: reset on dispose failed: %@", "\(error)") }
    }
    controls = nil
    if let observer = pauseObserver { context.frameSource.removeFrameObserver(observer) }
    pauseObserver = nil
    binding?.close()
    cache?.clear()
  }

  func handleCaptureCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) -> Bool {
    let method = call.method
    if CameraControlMethods.methods.contains(method) {
      cameraControl(method, call.arguments as? [String: Any] ?? [:], result)
      return true
    }
    if AndroidOnlyCaptureDebugRoutes.methods.contains(method) {
      result(AndroidOnlyCaptureDebugRoutes.error(method))
      return true
    }
    let arguments = call.arguments as? [String: Any] ?? [:]
    switch method {
    case "initializeCapture": initialize(call.arguments, result)
    case "dispose":
      captureViewWillDispose()
      result(nil)
    case "admitNativeCaptureV2":
      guard let binding = requireBinding(result, code: "NATIVE_CAPTURE_V2_CLOSED") else { return true }
      binding.admit(call.arguments) { outcome in
        if case .failure(let error) = outcome {
          NSLog("capture3d native capture admission refused: code=%@", NativeCaptureChannel.flutterError(error).code)
        }
        reply(result, outcome) { $0 }
      }
    case "replayNativeCaptureRecoveryV2":
      guard let binding = requireBinding(result, code: "NATIVE_CAPTURE_V2_CLOSED") else { return true }
      binding.replay { reply(result, $0) { $0 } }
    case "acknowledgeNativeCaptureTerminalV2":
      guard let binding = requireBinding(result, code: "NATIVE_CAPTURE_V2_CLOSED") else { return true }
      binding.acknowledge(arguments["attemptId"] as? String ?? "") { reply(result, $0) { $0 } }
    case "getNativeCaptureHealthV2":
      guard let binding = requireBinding(result, code: "NATIVE_CAPTURE_V2_CLOSED") else { return true }
      binding.health { reply(result, $0) { $0 } }
    case "notifyNativeCaptureLifecycleV2":
      guard let binding = requireBinding(result, code: "NATIVE_CAPTURE_V2_CLOSED") else { return true }
      binding.lifecycle(arguments["event"] as? String ?? "") { reply(result, $0) { $0 } }
    case "materializeNativeCapturePreview": materializePreview(arguments, result)
    case "getCaptureCapacity":
      guard let cache = requireInitialized(result) else { return true }
      result(cache.capacity())
    case "captureHighResImage": captureStaged(arguments, result)
    case "getImageData", "getImageSize", "saveImageToFile", "persistCapture", "discardCapture":
      stagedOperation(method, arguments, result)
    case "getCameraIntrinsics":
      guard requireInitialized(result) != nil else { return true }
      result(currentIntrinsicsMap() ?? NSNull())
    case "getPerformanceSnapshot": performanceSnapshot(arguments, result)
    default:
      return false
    }
    return true
  }

  // MARK: Initialization

  private func initialize(_ arguments: Any?, _ result: @escaping FlutterResult) {
    guard !disposed else {
      return result(FlutterError(code: "CAPTURE_NOT_INITIALIZED", message: "The AR view was disposed.", details: nil))
    }
    let parsed: StillCaptureConfig
    do {
      parsed = try StillCaptureConfig.parse(arguments)
    } catch let invalid as StillCaptureConfig.Invalid {
      return result(FlutterError(code: invalid.code, message: invalid.message, details: nil))
    } catch {
      return result(FlutterError(code: "CONFIG_INVALID", message: "\(error)", details: nil))
    }
    let map = arguments as? [String: Any] ?? [:]
    guard let requestedMode = StillCaptureMode.parse(map["stillCaptureMode"]) else {
      return result(FlutterError(code: "CONFIG_INVALID", message: "Unknown stillCaptureMode", details: nil))
    }
    guard let burst = BurstSettings.parse(map) else {
      return result(FlutterError(code: "CONFIG_INVALID", message: "burstImageCount must be 2-5 and burstIntervalMs 0-1000", details: nil))
    }
    config = parsed
    // A repeated initialize keeps the staged stills: the new configuration
    // applies to stills captured afterwards, and each staged one stays
    // readable, persistable and discardable by its id.
    if let existing = cache {
      existing.reconfigure(parsed)
    } else {
      cache = StagedStillCache(config: parsed)
    }
    let frameSource = context.frameSource
    // One exposure per view: the binding keeps the first one, so a repeated
    // initialize only updates its quality.
    let exposure: HighResolutionStillExposure
    if let existing = stills {
      existing.jpegQuality = parsed.jpegQuality
      exposure = existing
    } else {
      exposure = HighResolutionStillExposure(frameSource: { [weak frameSource] in frameSource }, jpegQuality: parsed.jpegQuality)
      stills = exposure
    }
    // A recording without stills has nothing to burst or process: its
    // stills are video frames, so the shot stays single.
    let highResolution = ARCameraCapabilities.of(frameSource).supportsHighResolutionFrameCapture
    let (effectiveMode, refusal) = StillCaptureMode.effective(
      requestedMode, replaysRecordedStills: frameSource.replaysRecordedStills, highResolution: highResolution)
    exposure.mode = effectiveMode
    exposure.burst = burst
    if binding == nil {
      let channel = context.channel
      let created = NativeCaptureBinding(
        openResources: openResources,
        exposure: exposure,
        events: { [weak self] event in
          let map = NativeCaptureWire.event(event)
          DispatchQueue.main.async {
            guard let self = self, !self.disposed else { return }
            channel.invokeMethod("onNativeCaptureV2Event", arguments: map)
          }
        },
        durableTerminal: { photo in NativeCaptureChannel.reportPhotoSaved(photo) })
      binding = created
      let observer = SessionPauseObserver { [weak created] in created?.onSessionPaused() }
      pauseObserver = observer
      frameSource.addFrameObserver(observer)
      created.start()
    }
    // A replayed recording takes its recorded stills: the recorded device's capabilities apply.
    var reply: [String: Any] = [
      "mode": highResolution ? "sharedCamera" : "previewFallback", "stillCaptureMode": effectiveMode.rawValue,
    ]
    if !highResolution {
      reply["warning"] = CaptureViewMessageCode.stillCaptureUnavailable
    }
    if let refusal = refusal {
      reply["stillCaptureModeUnsupported"] = requestedMode.rawValue
      reply["stillCaptureModeUnsupportedReason"] = refusal
    }
    result(reply)
  }

  /// One call per committed durable photo, after its durable terminal; a
  /// burst shot reports every committed image with its own frame.
  static func reportPhotoSaved(_ photo: DurableTerminalPhoto) {
    guard let emitter = VisibilityGridShellRegistry.shared.photoSavedEmitter else { return }
    photoSavedRecords(photo).forEach { emitter.onDurableTerminal($0) }
  }

  /// The photo saved records of [photo]: one, or one per committed image of
  /// a burst shot, each with that image's own pose, intrinsics and exposure
  /// time and the capture id the session manifest holds for it (image 0
  /// keeps the attempt's capture id; image k gets
  /// [ShotRecord.imageCaptureId]). Only images the committed terminal
  /// reports ([CaptureTerminal.shot]) are sent, so the grid credits exactly
  /// what the session holds. An image whose frame was not tracking normally
  /// carries no pose (no credit).
  static func photoSavedRecords(_ photo: DurableTerminalPhoto) -> [DurablePhotoRecord] {
    guard photo.terminal.kind == .committedPicture, let captureId = photo.terminal.captureId else { return [] }
    let automatic = photo.request.accepted.lane == .automatic
    let attemptId = photo.terminal.identity.attemptId
    guard let shot = photo.shot, !shot.images.isEmpty else {
      let metadata = photo.metadata
      return [DurablePhotoRecord(
        captureId: captureId, attemptId: attemptId,
        exposureTimestampNs: metadata?.exposureTimestampNs ?? 0, cameraToWorld: metadata?.cameraToWorld,
        intrinsics: metadata?.intrinsics, width: metadata?.width ?? 0, height: metadata?.height ?? 0,
        sharpness: metadata?.sharpness, automatic: automatic, blurRejected: false,
        imageURL: photo.imageURL)]
    }
    let committed = Set(photo.terminal.shot?.images.map { $0.index } ?? [])
    let reported = shot.images.filter { $0.index == 0 || committed.contains($0.index) }
    return reported.map { image in
      let m = image.metadata
      return DurablePhotoRecord(
        captureId: image.index == 0 ? captureId : ShotRecord.imageCaptureId(captureId, index: image.index),
        attemptId: attemptId, exposureTimestampNs: m.exposureTimestampNs,
        cameraToWorld: image.creditable ? m.cameraToWorld : nil, intrinsics: m.intrinsics,
        width: m.width, height: m.height, sharpness: m.sharpness, automatic: automatic,
        blurRejected: false, imageURL: image.index == 0 ? photo.imageURL : photo.shotImageURLs[image.index],
        shotImage: ShotImageRecord(
          shotId: shot.shotId, index: image.index, count: reported.count, mode: shot.mode.rawValue,
          flags: image.flags.rawValue))
    }
  }

  // MARK: Camera controls

  /// One Android runtime camera control on the camera ARKit runs, after
  /// `initializeCapture` (Android: `CAPTURE_NOT_INITIALIZED` before it).
  /// The AR session keeps running: controls only lock the capture device
  /// for configuration. A recorded playback has no camera to control.
  private func cameraControl(_ method: String, _ arguments: [String: Any], _ result: FlutterResult) {
    guard requireInitialized(result) != nil else { return }
    if context.frameSource.replaysRecordedStills {
      return result(CameraControlMethods.unavailable(method, reason: "a recorded session replays; it has no camera to control"))
    }
    if controls == nil, let device = controlDevice() { controls = CameraControlBridge(device: device) }
    guard let controls = controls else {
      return result(CameraControlMethods.unavailable(method, reason: "ARKit offers no configurable camera on this device"))
    }
    do {
      let value = try controls.handle(method, arguments)
      result(Self.channelValue(value))
    } catch let error as CameraControlError {
      result(CameraControlMethods.error(error))
    } catch {
      result(FlutterError(code: CameraControlError.updateFailedCode, message: "\(error)", details: nil))
    }
  }

  /// Optional map values become NSNull, as the Android codec sends null.
  static func channelValue(_ value: Any?) -> Any {
    switch value {
    case nil: return NSNull()
    case let map as [String: Any?]:
      return map.mapValues { channelValue($0) }
    default: return value!
    }
  }

  // MARK: Durable previews

  private func materializePreview(_ arguments: [String: Any], _ result: @escaping FlutterResult) {
    guard let manifestId = arguments["manifestId"] as? String, let captureId = arguments["captureId"] as? String else {
      return result(FlutterError(code: "CONFIG_INVALID", message: "manifestId and captureId are required", details: nil))
    }
    let owner = previewOwner
    DispatchQueue.global(qos: .utility).async {
      let value: Any
      do {
        value = try owner.materialize(manifestId: manifestId, captureId: captureId) ?? NSNull()
      } catch {
        value = FlutterError(code: "CAPTURE_FAILED", message: "\(error)", details: nil)
      }
      DispatchQueue.main.async { result(value) }
    }
  }

  // MARK: Staged stills

  private func captureStaged(_ arguments: [String: Any], _ result: @escaping FlutterResult) {
    guard let cache = requireInitialized(result), let stills = stills else { return }
    guard !captureInProgress else {
      return result(FlutterError(code: "CAPTURE_IN_PROGRESS", message: "A capture is already in progress", details: nil))
    }
    let requiresPose = arguments["requiresPose"] as? Bool ?? true
    let policy = arguments["qualityPolicy"] as? [String: Any]
    if requiresPose, context.frameSource.currentFrame?.trackingState != .normal {
      return result(FlutterError(code: "NOT_TRACKING", message: "AR tracking is not ready for a capture", details: nil))
    }
    captureInProgress = true
    let started = stills.captureStill { [weak self] outcome in
      DispatchQueue.main.async {
        guard let self = self else { return }
        self.captureInProgress = false
        switch outcome {
        case .failure(let failure):
          result(FlutterError(code: "CAPTURE_FAILED", message: failure.reason, details: nil))
        case .success(let still):
          self.finishStaged(still, cache: cache, requiresPose: requiresPose, policy: policy, result: result)
        }
      }
    }
    if !started {
      captureInProgress = false
      result(FlutterError(code: "CAPTURE_NOT_INITIALIZED", message: "No AR session is available for capture", details: nil))
    }
  }

  private func finishStaged(_ still: EncodedStill, cache: StagedStillCache, requiresPose: Bool, policy: [String: Any]?, result: FlutterResult) {
    let timestampMs = Int64(Date().timeIntervalSince1970 * 1000)
    let imageId = cache.nextImageId(timestampMs: timestampMs)
    let pose = still.metadata.cameraToWorld.map { Self.poseMap($0, still: still, timestampMs: timestampMs) }
    if requiresPose && pose == nil {
      return result(FlutterError(code: "POSE_SYNC_FAILED", message: "No aligned pose was available for the captured image", details: nil))
    }
    let blurEnabled = policy?["blurFilterEnabled"] as? Bool ?? true
    let threshold = (policy?["blurThreshold"] as? NSNumber)?.doubleValue ?? LaplacianSharpness.productionThreshold
    let keepRejected = policy?["keepRejectedCaptures"] as? Bool ?? false
    var quality: [String: Any]?
    if policy != nil {
      let score = blurEnabled ? (still.sharpness?.score ?? 0) : 0
      quality = [
        "blurScore": score, "blurThreshold": threshold, "blurPassed": !blurEnabled || score >= threshold,
        "analyzedWidth": blurEnabled ? (still.sharpness?.sampledWidth ?? 0) : 0,
        "analyzedHeight": blurEnabled ? (still.sharpness?.sampledHeight ?? 0) : 0,
        "algorithm": LaplacianSharpness.algorithm,
      ]
    }
    let metadata = still.metadata
    var capture: [String: Any] = [
      "imageId": imageId, "resolution": ["width": metadata.width, "height": metadata.height], "format": "jpeg",
      "formats": ["jpeg"], "captureTimestampMs": Int(timestampMs), "imageSizeBytes": still.jpeg.count,
      "imageSizeBytesByFormat": ["jpeg": still.jpeg.count], "isHighResolution": metadata.highResolution,
      "exposureStartTimestampNs": Int(metadata.exposureTimestampNs), "exposureTimeNs": 0, "rollingShutterSkewNs": 0,
      "filePath": NSNull(),
    ]
    capture["pose"] = pose ?? NSNull()
    capture["intrinsics"] = metadata.intrinsics.map { Self.intrinsicsMap($0, width: metadata.width, height: metadata.height) } ?? NSNull()
    let attemptId = "attempt_\(timestampMs)"
    let entry = StagedStillCache.Entry(imageId: imageId, bytes: still.jpeg, width: metadata.width, height: metadata.height, timestampMs: timestampMs)
    if let quality = quality, quality["blurPassed"] as? Bool == false {
      if keepRejected {
        do { try cache.cache(entry) } catch { return result(Self.flutterError(error)) }
        return result(["status": "rejectedBlur", "attemptId": attemptId, "imageId": imageId, "capture": capture, "quality": quality])
      }
      emitCapacity()
      return result(["status": "rejectedBlur", "attemptId": attemptId, "imageId": NSNull(), "capture": NSNull(), "quality": quality])
    }
    do { try cache.cache(entry) } catch { return result(Self.flutterError(error)) }
    emitCapacity()
    result(["status": "staged", "attemptId": attemptId, "imageId": imageId, "capture": capture, "quality": (quality as Any?) ?? NSNull()])
  }

  private func stagedOperation(_ method: String, _ arguments: [String: Any], _ result: @escaping FlutterResult) {
    guard let cache = requireInitialized(result) else { return }
    guard let imageId = arguments["imageId"] as? String else {
      return result(FlutterError(code: "CONFIG_INVALID", message: "imageId is required", details: nil))
    }
    do {
      switch method {
      case "getImageData":
        result(FlutterStandardTypedData(bytes: try cache.data(imageId, format: arguments["format"] as? String ?? "jpeg")))
      case "getImageSize":
        result(try cache.size(imageId))
      case "saveImageToFile":
        guard let path = arguments["filePath"] as? String else {
          return result(FlutterError(code: "CONFIG_INVALID", message: "filePath is required", details: nil))
        }
        result(try cache.save(imageId, to: path, format: arguments["format"] as? String ?? "jpeg"))
      case "persistCapture":
        guard let root = (arguments["destination"] as? [String: Any])?["root"] as? String else {
          return result(FlutterError(code: "CONFIG_INVALID", message: "destination.root is required", details: nil))
        }
        guard let folder = arguments["sessionFolder"] as? String, let baseName = arguments["baseName"] as? String else {
          return result(FlutterError(code: "CONFIG_INVALID", message: "sessionFolder and baseName are required", details: nil))
        }
        let persisted = try cache.persist(imageId, root: root, sessionFolder: folder, baseName: baseName, format: arguments["format"] as? String ?? "jpeg")
        emitCapacity()
        result(persisted)
      default:
        let discarded = cache.discard(imageId)
        emitCapacity()
        result(discarded)
      }
    } catch {
      result(Self.flutterError(error))
    }
  }

  private func emitCapacity() {
    guard !disposed, let cache = cache else { return }
    context.channel.invokeMethod("onCaptureCapacityChanged", arguments: cache.capacity())
  }

  // MARK: Diagnostics

  /// `resetDiagnosticTiming` starts a new capture view timing window after
  /// the reply's values were read.
  private func performanceSnapshot(_ arguments: Any?, _ result: @escaping FlutterResult) {
    let options = arguments as? [String: Any] ?? [:]
    let counters = stills?.snapshot
    var snapshot: [String: Any] = [
      "platform": "ios",
      "thermalStatus": Self.thermalStatus(ProcessInfo.processInfo.thermalState),
      "processPhysicalFootprintBytes": Self.physicalFootprint() ?? NSNull(),
      "stillCaptureMetadata": [
        "highResolutionStills": counters?.highResolution ?? 0,
        "videoFallbackStills": counters?.videoFallback ?? 0,
        "failedStills": counters?.failures ?? 0,
        "lastEncodeMs": counters?.lastEncodeMs ?? NSNull(),
        "lastTapToExposureMs": counters?.lastTapToExposureMs ?? NSNull(),
        "lastWidth": counters?.lastWidth ?? 0,
        "lastHeight": counters?.lastHeight ?? 0,
      ] as [String: Any],
      "captureView": CaptureViewFrameStats.shared.snapshot(nowS: CACurrentMediaTime()),
      "gridRenderer": GridRendererRegistry.shared.host?.diagnostics() ?? NSNull(),
    ]
    if options["resetDiagnosticTiming"] as? Bool == true { CaptureViewFrameStats.shared.reset() }
    guard let binding = binding else { return result(snapshot) }
    binding.health { outcome in
      if case .success(let health) = outcome { snapshot["nativeCaptureHealth"] = health["health"] ?? NSNull() }
      result(snapshot)
    }
  }

  private func currentIntrinsicsMap() -> [String: Any]? {
    guard let frame = context.frameSource.currentFrame else { return nil }
    let width = CVPixelBufferGetWidth(frame.capturedImage), height = CVPixelBufferGetHeight(frame.capturedImage)
    guard let k = StillIntrinsics.scaled(frame.intrinsics, imageResolution: frame.imageResolution, width: width, height: height) else {
      return nil
    }
    return Self.intrinsicsMap(k, width: width, height: height)
  }

  static func intrinsicsMap(_ k: simd_float4, width: Int, height: Int) -> [String: Any] {
    let fx = Double(k.x), fy = Double(k.y)
    return [
      "focalLength": ["fx": fx, "fy": fy],
      "principalPoint": ["cx": Double(k.z), "cy": Double(k.w)],
      "resolution": ["width": width, "height": height],
      "distortionCoefficients": [Double](),
      "fieldOfView": [
        // Radians, as Android and the Dart `ARCameraIntrinsics` (degrees
        // before the OpenCV pinhole convention).
        "horizontal": 2 * atan(Double(width) / (2 * fx)),
        "vertical": 2 * atan(Double(height) / (2 * fy)),
      ],
    ]
  }

  /// Pose map of the legacy capture result: OpenCV camera-to-world plus the
  /// ARKit tracking pose, as the Android `PoseDataExtractor.toPoseMap`.
  static func poseMap(_ transform: simd_float4x4, still: EncodedStill, timestampMs: Int64) -> [String: Any] {
    let tracking = [transform.columns.0, transform.columns.1, transform.columns.2, transform.columns.3]
      .flatMap { [$0.x, $0.y, $0.z, $0.w] }
    let openCv = PoseBatchWire.openCvCameraToWorld(tracking)
    let q = PoseBatchWire.quaternion(fromColumnMajor: openCv)
    let tq = PoseBatchWire.quaternion(fromColumnMajor: tracking)
    func vector(_ values: [Float]) -> [String: Any] { ["x": Double(values[0]), "y": Double(values[1]), "z": Double(values[2])] }
    func quaternion(_ values: [Float]) -> [String: Any] {
      ["x": Double(values[0]), "y": Double(values[1]), "z": Double(values[2]), "w": Double(values[3])]
    }
    return [
      "position": vector([openCv[12], openCv[13], openCv[14]]),
      "rotation": quaternion(q),
      "transform": openCv.map(Double.init),
      "convention": "opencv_c2w_v1",
      "timestampMs": Int(timestampMs),
      "sensorTimestampNs": Int(still.metadata.exposureTimestampNs),
      "confidence": still.isTracking ? 1.0 : 0.5,
      "isTracking": still.isTracking,
      "trackingState": still.isTracking ? "TRACKING" : "PAUSED",
      "poseSource": "arkit",
      "poseAlignment": "exact",
      "poseTimeErrorNs": 0,
      "trackingPose": [
        "convention": "arkit_c2w_v1",
        "position": vector([tracking[12], tracking[13], tracking[14]]),
        "rotation": quaternion(tq),
        "cameraToWorld": tracking.map(Double.init),
      ] as [String: Any],
    ]
  }

  // MARK: Helpers

  private func requireBinding(_ result: FlutterResult, code: String) -> NativeCaptureBinding? {
    guard !disposed, let binding = binding else {
      result(FlutterError(code: code, message: "Native capture durable owner is not initialized or closing", details: nil))
      return nil
    }
    return binding
  }

  private func requireInitialized(_ result: FlutterResult) -> StagedStillCache? {
    guard !disposed, let cache = cache else {
      result(FlutterError(code: "CAPTURE_NOT_INITIALIZED", message: "Capture session is not initialized", details: nil))
      return nil
    }
    return cache
  }

  static func flutterError(_ error: Error) -> FlutterError {
    switch error {
    case let failure as StagedStillCache.Failure: return FlutterError(code: failure.code, message: failure.message, details: nil)
    case let owner as NativeCaptureOwnerError: return FlutterError(code: owner.code, message: owner.message, details: nil)
    case let wire as NativeCaptureWireError:
      if case .invalid(let message) = wire { return FlutterError(code: "NATIVE_CAPTURE_V2_INVALID", message: message, details: nil) }
      return FlutterError(code: "NATIVE_CAPTURE_V2_FAILED", message: wire.description, details: nil)
    default: return FlutterError(code: "NATIVE_CAPTURE_V2_FAILED", message: "\(error)", details: nil)
    }
  }

  static func thermalStatus(_ state: ProcessInfo.ThermalState) -> String {
    switch state {
    case .nominal: return "none"
    case .fair: return "light"
    case .serious: return "severe"
    case .critical: return "critical"
    @unknown default: return "unknown"
    }
  }

  static func physicalFootprint() -> Int? {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let status = withUnsafeMutablePointer(to: &info) {
      $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
    }
    return status == KERN_SUCCESS ? Int(info.phys_footprint) : nil
  }
}

/// Replies a binding outcome on the main thread.
private func reply<T>(_ result: FlutterResult, _ outcome: Result<T, Error>, _ map: (T) -> Any) {
  switch outcome {
  case .success(let value): result(map(value))
  case .failure(let error): result(NativeCaptureChannel.flutterError(error))
  }
}

/// Forwards the AR session pause (and the view's stop) to the capture owner.
final class SessionPauseObserver: ARFrameObserver {
  private let onStop: () -> Void
  init(onStop: @escaping () -> Void) { self.onStop = onStop }
  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {}
  func frameSourceDidStop(_ source: ARFrameSource) { onStop() }
}

/// `ar_flutter_plugin_2/native_capture_preview`: History and detail previews
/// of committed durable photos without a live view, the two-phase preview
/// deletion used when the app deletes captures, and the durable photo
/// deletion of a deleted session (`deleteNativeCaptureSession`, replies the
/// reclaimed bytes).
enum NativeCapturePreviewChannel {
  static let name = "ar_flutter_plugin_2/native_capture_preview"
  private static let queue = DispatchQueue(label: "capture3d.native-capture.preview", qos: .utility)
  private static let lock = NSLock()
  private static var cancelledRequests: [String] = []

  /// [owner] defaults to the app's preview store; tests pass one rooted in
  /// a temporary folder.
  static func register(messenger: FlutterBinaryMessenger,
                       owner: NativeCapturePreviewOwner = NativeCapturePreviewOwner()) -> FlutterMethodChannel {
    let channel = FlutterMethodChannel(name: name, binaryMessenger: messenger)
    channel.setMethodCallHandler { call, result in
      let arguments = call.arguments as? [String: Any] ?? [:]
      let requestId = arguments["requestId"] as? String
      if call.method == "cancelNativeCapturePreview" {
        if let id = requestId {
          lock.lock(); cancelledRequests.append(id); if cancelledRequests.count > 64 { cancelledRequests.removeFirst() }; lock.unlock()
        }
        return result(nil)
      }
      let known: Set<String> = ["materializeNativeCapturePreview", "beginNativeCapturePreviewDeletion",
                                "commitNativeCapturePreviewDeletion", "rollbackNativeCapturePreviewDeletion",
                                "reconcileNativeCapturePreviews", "deleteNativeCaptureSession", "reconcileNativeCaptureSessions"]
      guard known.contains(call.method) else { return result(FlutterMethodNotImplemented) }
      queue.async {
        let cancelled: () -> Bool = {
          guard let id = requestId else { return false }
          lock.lock(); defer { lock.unlock() }
          return cancelledRequests.contains(id)
        }
        let value: Any
        do {
          switch call.method {
          case "reconcileNativeCapturePreviews":
            try owner.reconcile(liveReferences: arguments["references"] as? [String] ?? [])
            value = NSNull()
          case "materializeNativeCapturePreview":
            guard let manifestId = arguments["manifestId"] as? String, let captureId = arguments["captureId"] as? String else {
              throw DurableStoreStateError("manifestId and captureId are required")
            }
            value = try owner.materialize(manifestId: manifestId, captureId: captureId,
                                          edge: arguments["original"] as? Bool == true ? 0 : (arguments["detailed"] as? Bool == true ? 1024 : 512), cancelled: cancelled) ?? NSNull()
          case "reconcileNativeCaptureSessions":
            guard let sessionIds = arguments["sessionIds"] as? [String] else {
              throw NativeCaptureWireError.invalid("sessionIds are required")
            }
            value = Int(try NativeCaptureSessionDeletion.reconcile(liveSessionIds: sessionIds))
          case "deleteNativeCaptureSession":
            value = Int(try NativeCaptureSessionDeletion.delete(sessionId: arguments["sessionId"] as? String ?? ""))
          case "beginNativeCapturePreviewDeletion":
            value = try owner.beginDeletion(references: arguments["references"] as? [String] ?? [])
          case "commitNativeCapturePreviewDeletion":
            try owner.commitDeletion(token: arguments["token"] as? String ?? "")
            value = NSNull()
          default:
            try owner.rollbackDeletion(token: arguments["token"] as? String ?? "")
            value = NSNull()
          }
        } catch {
          value = FlutterError(code: "NATIVE_CAPTURE_PREVIEW_FAILED", message: "\(error)", details: nil)
        }
        DispatchQueue.main.async { result(value) }
      }
    }
    return channel
  }
}
