import ARKit
import Flutter
import simd
import XCTest
@testable import ar_flutter_plugin_2

/// The capture view's pose stream across capture-channel calls: a repeated
/// `initializeCapture` keeps the running stream, so `onPoseBatch` sequences
/// never go backwards on one view.
final class CaptureARViewPoseStreamTests: XCTestCase {
  private var savedExtension: ((CaptureChannelContext) -> CaptureChannelExtension)?

  override func setUp() {
    super.setUp()
    savedExtension = CaptureChannelExtensionRegistry.shared.makeExtension
    CaptureChannelExtensionRegistry.shared.makeExtension = nil
  }

  override func tearDown() {
    CaptureChannelExtensionRegistry.shared.makeExtension = savedExtension
    super.tearDown()
  }

  func testRepeatedInitializeCaptureKeepsPoseSequencesIncreasing() {
    let messenger = PoseRecordingMessenger()
    let source = ObservedSessionSource()
    let view = CaptureARView(frame: CGRect(x: 0, y: 0, width: 64, height: 48), viewIdentifier: 7,
                             source: source, binaryMessenger: messenger)

    XCTAssertTrue(messenger.call("arcapture_7", "initializeCapture", ["enablePoseStream": true]))
    source.deliver(timestamps: [1.00, 1.04, 1.08])
    drainMainQueue()
    XCTAssertTrue(messenger.call("arcapture_7", "initializeCapture", ["enablePoseStream": true]))
    source.deliver(timestamps: [1.12, 1.16, 1.20])
    drainMainQueue()

    let sequences = messenger.poseSequences
    XCTAssertEqual(sequences.count, 6, "every frame 40 ms apart is due at 30 Hz")
    XCTAssertEqual(sequences.first, 1)
    for (previous, next) in zip(sequences, sequences.dropFirst()) {
      XCTAssertGreaterThan(next, previous, "sequences \(sequences) must strictly increase")
    }

    messenger.call("arcapture_7", "dispose", nil)
    withExtendedLifetime(view) {}
  }

  func testDisposeThenInitializeCaptureRestartsTheSequence() {
    let messenger = PoseRecordingMessenger()
    let source = ObservedSessionSource()
    let view = CaptureARView(frame: CGRect(x: 0, y: 0, width: 64, height: 48), viewIdentifier: 8,
                             source: source, binaryMessenger: messenger)

    messenger.call("arcapture_8", "initializeCapture", ["enablePoseStream": true])
    source.deliver(timestamps: [1.00, 1.04])
    drainMainQueue()
    messenger.call("arcapture_8", "dispose", nil)
    messenger.call("arcapture_8", "initializeCapture", ["enablePoseStream": true])
    source.deliver(timestamps: [2.00])
    drainMainQueue()

    XCTAssertEqual(messenger.poseSequences, [1, 2, 1])
    withExtendedLifetime(view) {}
  }

  private func drainMainQueue() {
    let drained = expectation(description: "main queue drained")
    DispatchQueue.main.async { drained.fulfill() }
    wait(for: [drained], timeout: 2)
  }
}

/// A session source whose frames the test hands to the registered observers.
private final class ObservedSessionSource: CaptureSessionSource {
  var onSessionFailure: ((Error) -> Void)?
  let anchorHost: CaptureAnchorHost = RecordedAnchorStore()
  var currentFrame: CaptureFrame? { nil }
  var runConfiguration: CaptureRunConfiguration? { CaptureRunConfiguration(depth: .none) }
  var lifecycleState: ARSessionLifecycleState { .running }
  var deliversSceneDepth: Bool { false }
  var depthOutputSize: ARDepthOutputSize { .standard }
  var epoch: Int { 1 }
  private var observers: [ARFrameObserver] = []

  func addFrameObserver(_ observer: ARFrameObserver) { observers.append(observer) }
  func removeFrameObserver(_ observer: ARFrameObserver) { observers.removeAll { $0 === observer } }
  func captureHighResolutionFrame(completion: @escaping (CaptureFrame?, Error?) -> Void) { completion(nil, nil) }
  func getCurrentWorldMap(completion: @escaping (ARWorldMap?, Error?) -> Void) { completion(nil, nil) }
  func start() {}
  func initialize() {}
  func pause() {}
  func resume() {}
  func restart() {}
  func dispose() {}

  func deliver(timestamps: [TimeInterval]) {
    for timestamp in timestamps {
      let frame = ShotFixture.frame(timestamp: timestamp, pose: ShotFixture.pose(x: Float(timestamp)))
      for observer in observers { observer.frameSource(self, didUpdate: frame) }
    }
  }
}

/// Answers channel calls through the installed handlers and records the
/// `onPoseBatch` sequences the view sends, acknowledging each batch at once.
private final class PoseRecordingMessenger: NSObject, FlutterBinaryMessenger {
  private var handlers: [String: FlutterBinaryMessageHandler] = [:]
  private let codec = FlutterStandardMethodCodec.sharedInstance()
  private(set) var poseSequences: [Int64] = []

  /// Returns true when the handler replied synchronously.
  @discardableResult
  func call(_ channel: String, _ method: String, _ arguments: Any?) -> Bool {
    guard let handler = handlers[channel] else { return false }
    var replied = false
    handler(codec.encode(FlutterMethodCall(methodName: method, arguments: arguments))) { _ in replied = true }
    return replied
  }

  func send(onChannel channel: String, message: Data?) {
    send(onChannel: channel, message: message, binaryReply: nil)
  }

  func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply? = nil) {
    guard let message = message else { return }
    let call = codec.decodeMethodCall(message)
    if call.method == "onPoseBatch",
       let arguments = call.arguments as? [String: Any],
       let bytes = (arguments["sampleBytes"] as? FlutterStandardTypedData)?.data {
      for offset in stride(from: 0, to: bytes.count, by: PoseBatchWire.sampleBytes) {
        let sequence = bytes.subdata(in: offset..<offset + 8).withUnsafeBytes { $0.loadUnaligned(as: Int64.self) }
        poseSequences.append(Int64(littleEndian: sequence))
      }
    }
    callback?(codec.encodeSuccessEnvelope(nil))
  }

  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil
  ) -> FlutterBinaryMessengerConnection {
    handlers[channel] = handler
    return FlutterBinaryMessengerConnection(handlers.count)
  }

  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
