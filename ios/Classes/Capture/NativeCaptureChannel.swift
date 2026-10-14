import ARKit
import Flutter
import Foundation
import simd

/// Registers native still capture with the plugin: the `arcapture_<id>`
/// extension of every capture view, the app-wide preview channel and the
/// store format versions channel. Called once from `SwiftArFlutterPlugin.register`.
enum NativeCaptureRegistration {
  private static var channels: [FlutterMethodChannel] = []

  static func register(with registrar: FlutterPluginRegistrar) {
    CaptureChannelExtensionRegistry.shared.makeExtension = { NativeCaptureChannel(context: $0) }
    channels = [
      NativeCapturePreviewChannel.register(messenger: registrar.messenger()),
      NativeStoreFormatVersions.register(messenger: registrar.messenger()),
    ]
  }
}

/// Methods of the Android capture channel that drive Camera2 controls. ARKit
/// owns exposure, focus, white balance and the torch while a session runs, so
/// iOS answers each with the defined `CAMERA_CONTROL_UNSUPPORTED` error.
enum AndroidOnlyCameraControls {
  static let errorCode = "CAMERA_CONTROL_UNSUPPORTED"
  static let methods: Set<String> = [
    "setISO", "setExposureTime", "setAutoExposureEnabled", "getCurrentISO", "getCurrentExposureTime",
    "getSupportedISORange", "getSupportedExposureRange", "getCurrentExposureState", "getExposureCompensationInfo",
    "setExposureCompensation", "lockExposure", "unlockExposure", "setFocusDistance", "setAutofocusEnabled",
    "focusAtPoint", "getCurrentFocusState", "getSupportedFocusModes", "setFocusMode", "setWhiteBalanceMode",
    "setColorTemperature", "getCurrentWhiteBalanceState", "getSupportedColorTemperatureRange", "lockWhiteBalance",
    "unlockWhiteBalance", "setWhiteBalanceFromPoint", "getSupportedWhiteBalanceModes", "setFlashMode",
    "getCurrentFlashState", "isFlashAvailable", "setTorchEnabled",
  ]

  static func error(_ method: String) -> FlutterError {
    FlutterError(
      code: errorCode,
      message: "\(method) is not available on iOS: ARKit controls exposure, focus, white balance and flash.",
      details: ["platform": "ios", "method": method])
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

  init(context: CaptureChannelContext) {
    self.context = context
  }

  func captureViewWillDispose() {
    guard !disposed else { return }
    disposed = true
    if let observer = pauseObserver { context.frameSource.removeFrameObserver(observer) }
    pauseObserver = nil
    binding?.close()
    cache?.clear()
  }

  func handleCaptureCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) -> Bool {
    let method = call.method
    if AndroidOnlyCameraControls.methods.contains(method) {
      result(AndroidOnlyCameraControls.error(method))
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
      binding.admit(call.arguments) { reply(result, $0) { $0 } }
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
    case "getPerformanceSnapshot": performanceSnapshot(result)
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
    config = parsed
    cache = StagedStillCache(config: parsed)
    let frameSource = context.frameSource
    // One exposure per view: the binding keeps the first one, so a repeated
    // initialize only updates its quality.
    let exposure: HighResolutionStillExposure
    if let existing = stills {
      existing.jpegQuality = parsed.jpegQuality
      exposure = existing
    } else {
      exposure = HighResolutionStillExposure(session: { [weak frameSource] in frameSource?.session }, jpegQuality: parsed.jpegQuality)
      stills = exposure
    }
    if binding == nil {
      let channel = context.channel
      let created = NativeCaptureBinding(
        openResources: { try NativeCaptureBinding.productionResources() },
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
    var reply: [String: Any] = ["mode": ARCameraCapabilities.current().supportsHighResolutionFrameCapture ? "sharedCamera" : "previewFallback"]
    if !ARCameraCapabilities.current().supportsHighResolutionFrameCapture {
      reply["warning"] = "High-resolution ARKit frames are unavailable; stills use the video frame."
    }
    result(reply)
  }

  /// One call per committed durable photo, after its durable terminal.
  static func reportPhotoSaved(_ photo: DurableTerminalPhoto) {
    guard photo.terminal.kind == .committedPicture, let captureId = photo.terminal.captureId,
          let emitter = VisibilityGridShellRegistry.shared.photoSavedEmitter else { return }
    let metadata = photo.metadata
    emitter.onDurableTerminal(DurablePhotoRecord(
      captureId: captureId, attemptId: photo.terminal.identity.attemptId,
      exposureTimestampNs: metadata?.exposureTimestampNs ?? 0, cameraToWorld: metadata?.cameraToWorld,
      intrinsics: metadata?.intrinsics, width: metadata?.width ?? 0, height: metadata?.height ?? 0,
      sharpness: metadata?.sharpness, automatic: photo.request.accepted.lane == .automatic, blurRejected: false))
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
    if requiresPose, context.frameSource.session.currentFrame?.camera.trackingState != .normal {
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

  private func performanceSnapshot(_ result: @escaping FlutterResult) {
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
    ]
    guard let binding = binding else { return result(snapshot) }
    binding.health { outcome in
      if case .success(let health) = outcome { snapshot["nativeCaptureHealth"] = health["health"] ?? NSNull() }
      result(snapshot)
    }
  }

  private func currentIntrinsicsMap() -> [String: Any]? {
    guard let frame = context.frameSource.session.currentFrame else { return nil }
    let width = CVPixelBufferGetWidth(frame.capturedImage), height = CVPixelBufferGetHeight(frame.capturedImage)
    guard let k = StillIntrinsics.scaled(frame.camera.intrinsics, imageResolution: frame.camera.imageResolution, width: width, height: height) else {
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
        "horizontal": 2 * atan(Double(width) / (2 * fx)) * 180 / .pi,
        "vertical": 2 * atan(Double(height) / (2 * fy)) * 180 / .pi,
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
  func frameSource(_ source: ARFrameSource, didUpdate frame: ARFrame) {}
  func frameSourceDidStop(_ source: ARFrameSource) { onStop() }
}

/// `ar_flutter_plugin_2/native_capture_preview`: History and detail previews
/// of committed durable photos without a live view, plus the two-phase
/// preview deletion used when the app deletes captures.
enum NativeCapturePreviewChannel {
  static let name = "ar_flutter_plugin_2/native_capture_preview"
  private static let queue = DispatchQueue(label: "capture3d.native-capture.preview", qos: .utility)
  private static let lock = NSLock()
  private static var cancelledRequests: [String] = []

  static func register(messenger: FlutterBinaryMessenger) -> FlutterMethodChannel {
    let channel = FlutterMethodChannel(name: name, binaryMessenger: messenger)
    let owner = NativeCapturePreviewOwner()
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
                                "reconcileNativeCapturePreviews"]
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
                                          edge: arguments["detailed"] as? Bool == true ? 1024 : 512, cancelled: cancelled) ?? NSNull()
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
