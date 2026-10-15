import ARKit
import XCTest
@testable import ar_flutter_plugin_2

/// The anchor tracker reads only the anchors the source reported as added
/// or updated (ARKit `didUpdate anchors`, ARCore `getUpdatedAnchors`), not
/// every anchor of the session; a camera tracking change reaches every
/// anchor once, and the reused frame keeps its buffer between frames.
final class UpdatedAnchorFrameTests: XCTestCase {
  private let image = RecordedPlaceholderImage.make(width: 64, height: 48)!

  private func frame(_ anchors: [ARAnchor], tracking: ARCamera.TrackingState = .normal, at time: TimeInterval = 1) -> CaptureFrame {
    RecordedCaptureFrame(
      timestamp: time, recordedTimestamp: time, cameraTransform: matrix_identity_float4x4,
      intrinsics: matrix_identity_float3x3, imageResolution: CGSize(width: 64, height: 48), trackingState: tracking,
      capturedImage: image, depth: nil, featurePoints: nil, lightEstimate: nil, anchors: anchors)
  }

  private func read(_ anchorFrame: UpdatedAnchorFrame) -> [UUID] {
    (0..<anchorFrame.loadUpdatedAnchors()).map { (anchorFrame.updatedAnchor($0) as! ARAnchor).identifier }
  }

  func testOnlyReportedAnchorsAreReadAndATrackingChangeReadsAllOnce() {
    let session = (0..<256).map { ARAnchor(name: "a\($0)", transform: matrix_identity_float4x4) }
    let anchorFrame = UpdatedAnchorFrame()

    // First frame of a run: the camera state is new, every anchor is read.
    anchorFrame.bind(frame(session))
    XCTAssertEqual(read(anchorFrame).count, 256)
    anchorFrame.clear()

    // Unchanged frames read nothing although the session holds 256 anchors.
    for step in 0..<3 {
      anchorFrame.bind(frame(session, at: 2 + Double(step)))
      XCTAssertEqual(anchorFrame.loadUpdatedAnchors(), 0)
      anchorFrame.clear()
    }

    anchorFrame.noteUpdated([session[7]])
    anchorFrame.noteUpdated([session[9], session[11]])
    anchorFrame.bind(frame(session, at: 6))
    XCTAssertEqual(read(anchorFrame), [session[7], session[9], session[11]].map { $0.identifier })
    anchorFrame.clear()
    XCTAssertEqual(anchorFrame.pendingCount, 0)

    // Limited tracking pauses every plain anchor: all are read once.
    anchorFrame.bind(frame(session, tracking: .limited(.excessiveMotion), at: 7))
    XCTAssertEqual(read(anchorFrame).count, 256)
    anchorFrame.clear()
    anchorFrame.bind(frame(session, tracking: .limited(.excessiveMotion), at: 8))
    XCTAssertEqual(anchorFrame.loadUpdatedAnchors(), 0)
    anchorFrame.clear()

    // A new run forgets the camera state.
    anchorFrame.reset()
    anchorFrame.bind(frame(session, tracking: .limited(.excessiveMotion), at: 9))
    XCTAssertEqual(read(anchorFrame).count, 256)
  }

  func testRecordedReplayReportsAnAddedAnchorOnce() throws {
    let directory = try RecordedSessionFixture.room2URL()
    var options = ARSessionOptions()
    options.sceneDepth = false
    let source = RecordedFrameSource(
      session: try RecordedSession(directory: directory),
      config: RecordedPlaybackConfig(directory: directory, restart: .off, restartGeneration: 0),
      options: options, registry: nil)
    defer { source.dispose() }
    let observer = RecordingFrameObserver()
    let first = expectation(description: "first frame")
    first.assertForOverFulfill = false
    observer.onEvent = { [weak observer] in if observer?.frames.isEmpty == false { first.fulfill() } }
    source.addFrameObserver(observer)
    source.start()
    wait(for: [first], timeout: 5)
    let anchor = ARAnchor(name: "photo", transform: matrix_identity_float4x4)
    source.anchorHost.add(anchor: anchor)
    let reported = expectation(description: "frame after the report")
    reported.assertForOverFulfill = false
    observer.onEvent = { [weak observer] in
      guard let observer = observer, observer.anchorUpdates.count == 1, observer.events.last == .frame else { return }
      reported.fulfill()
    }
    wait(for: [reported], timeout: 5)
    let framesAtReport = observer.frames.count
    let more = expectation(description: "later frames")
    more.assertForOverFulfill = false
    observer.onEvent = { [weak observer] in if (observer?.frames.count ?? 0) >= framesAtReport + 3 { more.fulfill() } }
    wait(for: [more], timeout: 5)
    XCTAssertEqual(observer.anchorUpdates.map { $0.map { $0.identifier } }, [[anchor.identifier]], "reported once, then never again")
  }
}
