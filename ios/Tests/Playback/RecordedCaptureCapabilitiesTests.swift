import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// Capabilities during recorded playback: the capability query and the
/// capture view's still mode describe the recorded iPhone (from the
/// recording), not the host (a simulator reports a Mac model and no LiDAR).
final class RecordedCaptureCapabilitiesTests: XCTestCase {
  /// A host without LiDAR or high-resolution frames, like the simulator.
  private static let host = ARCameraCapabilities(
    modelIdentifier: "Mac15,6", worldTrackingSupported: false, sceneDepthSupported: false,
    smoothedSceneDepthSupported: false, sceneReconstructionSupported: false, videoFormats: [],
    highResolutionFrameCaptureFormat: nil)

  private static let playbackArguments: [String: Any] = [
    "arcorePlaybackDataset": "room2_trimmed", "arcorePlaybackRestart": "view", "arcorePlaybackRestartGeneration": 0,
  ]

  /// Selection with the recordings folder holding [directory].
  private func select(_ directory: URL) -> (Any?) -> RecordedPlaybackSelection {
    let recordings = directory.deletingLastPathComponent()
    return {
      RecordedPlaybackSelector.select(creationParams: $0, allowsPlayback: true, recordingsDirectory: recordings)
    }
  }

  func testTheLiDARRecordingReportsTheRecordedDeviceWithDepthAndStills() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    let capabilities = ARCameraCapabilities.recorded(session)
    XCTAssertEqual(capabilities.modelIdentifier, "iPhone13,3")
    XCTAssertTrue(capabilities.recordedPlayback)
    XCTAssertTrue(capabilities.sceneDepthSupported)
    XCTAssertTrue(capabilities.hasLiDARDepth)
    XCTAssertFalse(capabilities.smoothedSceneDepthSupported, "the recorder writes raw depth only")
    XCTAssertTrue(capabilities.supportsHighResolutionFrameCapture)
    let still = try XCTUnwrap(session.stills.first)
    XCTAssertEqual(capabilities.highResolutionFrameCaptureFormat?.width, still.width)
    XCTAssertEqual(capabilities.highResolutionFrameCaptureFormat?.height, still.height)
    XCTAssertEqual(capabilities.videoFormats.map { [$0.width, $0.height, $0.framesPerSecond] }, [[240, 180, 30]])
    XCTAssertEqual(capabilities.channelMap[ARKitCapabilityChannel.Key.recordedPlayback] as? Bool, true)
  }

  func testARecordingWithoutDepthOrStillsIsPointsOnlyWithoutHighResolutionFrames() throws {
    let directory = try SyntheticRecording(frames: [.init(timestamp: 1), .init(timestamp: 1.1)]).write()
    defer { try? FileManager.default.removeItem(at: directory) }
    let capabilities = ARCameraCapabilities.recorded(try RecordedSession(directory: directory))
    XCTAssertEqual(capabilities.modelIdentifier, "recorded", "meta.json without a device")
    XCTAssertFalse(capabilities.sceneDepthSupported)
    XCTAssertFalse(capabilities.supportsHighResolutionFrameCapture)
    XCTAssertTrue(capabilities.recordedPlayback)
  }

  func testTheQueryDescribesTheRecordingOnlyWhenItsArgumentsSelectOne() throws {
    let room2 = try RecordedSessionFixture.room2URL()
    let recorded = ARCameraCapabilities.forQuery(Self.playbackArguments, select: select(room2), host: { Self.host })
    XCTAssertEqual(recorded.modelIdentifier, "iPhone13,3")
    XCTAssertTrue(recorded.recordedPlayback)

    XCTAssertEqual(ARCameraCapabilities.forQuery(nil, select: select(room2), host: { Self.host }), Self.host)
    XCTAssertEqual(ARCameraCapabilities.forQuery([String: Any](), select: select(room2), host: { Self.host }), Self.host)
    XCTAssertEqual(
      ARCameraCapabilities.forQuery(["arcorePlaybackDataset": "room9"], select: select(room2), host: { Self.host }),
      Self.host, "a missing recording leaves the host's answer")
    XCTAssertEqual(
      ARCameraCapabilities.forQuery(
        Self.playbackArguments, select: select(room2), open: { _ in throw RecordedSessionError.malformed("test") },
        host: { Self.host }),
      Self.host, "a recording that does not open leaves the host's answer")
    XCTAssertNil(Self.host.channelMap[ARKitCapabilityChannel.Key.recordedPlayback])
  }

  func testTheCaptureViewTakesTheRecordedCapabilitiesWhileARecordingReplays() throws {
    let room2 = try RecordedSessionFixture.room2URL()
    let source = RecordedFrameSource(
      session: try RecordedSession(directory: room2),
      config: RecordedPlaybackConfig(directory: room2, restart: .off, restartGeneration: 0),
      options: ARSessionOptions(), registry: nil)
    defer { source.dispose() }
    let capabilities = ARCameraCapabilities.of(source, host: { Self.host })
    XCTAssertTrue(capabilities.recordedPlayback)
    XCTAssertTrue(capabilities.supportsHighResolutionFrameCapture)
    XCTAssertEqual(ARCameraCapabilities.of(nil, host: { Self.host }), Self.host)
  }

  /// The reply of the shared contract fixture (`capabilityQuery.recordedPlayback`),
  /// which the plugin's Dart contract test parses too.
  func testTheRecordedReplyMatchesTheSharedContract() throws {
    guard let url = GoldenFixture.bundle.resourceURL?
      .appendingPathComponent("arkit_session/arkit_session_contract.json") else {
      throw GoldenFixtureError.missing("arkit_session/arkit_session_contract.json")
    }
    let fixture = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    let query = try XCTUnwrap(fixture["capabilityQuery"] as? [String: Any])
    let playback = try XCTUnwrap(query["recordedPlayback"] as? [String: Any])
    let arguments = try XCTUnwrap(playback["arguments"] as? [String: Any])
    let room2 = try RecordedSessionFixture.room2URL()
    let reply = ARCameraCapabilities.forQuery(arguments, select: select(room2), host: { Self.host }).channelMap
    let expected = try XCTUnwrap(playback["reply"] as? [String: Any])
    for (key, value) in expected {
      switch value {
      case let text as String:
        XCTAssertEqual(reply[key] as? String, text, key)
      case let number as NSNumber:
        XCTAssertEqual(reply[key] as? Bool, number.boolValue, key)
      default:
        XCTFail("unexpected fixture value for \(key)")
      }
    }
  }
}
