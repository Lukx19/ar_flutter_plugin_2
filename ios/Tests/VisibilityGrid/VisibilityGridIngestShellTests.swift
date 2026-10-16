import Foundation
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Shell state machine: inert until start, depth and points routing, group
/// epoch on AR session restarts, resume and tracking events.
final class VisibilityGridIngestShellTests: XCTestCase {
  private let clock = FakeIngestClock(1)
  private lazy var shell = VisibilityGridIngestShell(clock: clock)
  private let capture = ReplyCapture()
  private let frame = FakeIngestFrame()

  private func config(flags: Int = 0, preference: Int = IngestShellConfig.depthAutomatic, epoch: Int = 3) -> IngestShellConfig {
    IngestShellConfig(flags: flags, depthSourcePreference: preference, runMode: 0, markerDetectionHz: 0,
                      markerSizeMetres: 0, groupEpoch: epoch)
  }

  private func frameAt(_ ns: Int64, tracking: Int = IngestTrackingState.tracking) {
    frame.timestampNs = ns
    frame.trackingState = tracking
    shell.process(frame)
  }

  /// Every queued message, in delivery order.
  private func drain() -> [Data] {
    var out: [Data] = []
    while true {
      let sink = ReplyCapture()
      shell.pull.onPull(pullRequest(), reply: sink.reply)
      guard let m = sink.last else {
        // Parked: nothing queued. Release the parked pull.
        clock.now += IngestPullHandler.idleAfterNs
        shell.pull.tick()
        return out
      }
      out.append(m)
    }
  }

  private func events(_ messages: [Data]) -> [Int] {
    messages.filter { $0.ingestKind == IngestMessageLayout.kindSessionEvent }.map { $0.u8(IngestMessageLayout.headerBytes) }
  }

  func testInertUntilStarted() {
    frame.lidarDepth()
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    frameAt(1_000_000_000)
    XCTAssertEqual(shell.depth.acquired, 0)
    XCTAssertTrue(drain().isEmpty)
  }

  func testTheSourceDepthSizeReachesTheDepthProducer() {
    frame.lidarDepth()
    shell.onSourceAttached(
      sourceEpoch: 1, depthMode: IngestDepthMode.raw, depthSize: IngestDepthSize(width: 256, height: 192),
      replacesSession: false)
    XCTAssertEqual(shell.depth.requestedSize, IngestDepthSize(width: 256, height: 192))
    shell.start(config())
    frameAt(1_000_000_000)
    let depth = drain().filter { $0.ingestKind == IngestMessageLayout.kindDepthImage }
    XCTAssertEqual(depth.first?.u16(IngestMessageLayout.width), 256)
    shell.onSessionStarted(sourceEpoch: 2, depthMode: IngestDepthMode.raw, depthSize: IngestDepthSize(width: 128, height: 96))
    XCTAssertEqual(shell.depth.requestedSize, IngestDepthSize(width: 128, height: 96))
  }

  func testLidarDepthFlowsWithTheStartEpochAndPointsOnlyWhenRequested() {
    frame.lidarDepth()
    frame.points = [[0, 0, -1]]
    frame.identifiers = [7]
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config(epoch: 5))
    frameAt(1_000_000_000)
    var messages = drain()
    XCTAssertEqual(messages.map(\.ingestKind), [IngestMessageLayout.kindDepthImage])
    XCTAssertEqual(messages[0].u16(IngestMessageLayout.groupEpoch), 5)
    XCTAssertNotEqual(messages[0].u8(IngestMessageLayout.sourceFlags) & IngestMessageLayout.sourceLidarClass, 0)

    shell.start(config(flags: ShellControlLayout.flagPointCloud, epoch: 5))
    frameAt(2_000_000_000)
    messages = drain()
    XCTAssertEqual(Set(messages.map(\.ingestKind)), [IngestMessageLayout.kindDepthImage, IngestMessageLayout.kindPointCloud])
  }

  func testPointsOnlyPreferenceAndNonLidarSessionsNeverSendDepth() {
    frame.lidarDepth()
    frame.points = [[0, 0, -1]]
    frame.identifiers = [7]
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config(preference: IngestShellConfig.depthPointsOnly))
    frameAt(1_000_000_000)
    XCTAssertEqual(drain().map(\.ingestKind), [IngestMessageLayout.kindPointCloud])

    let other = VisibilityGridIngestShell(clock: clock)
    other.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.disabled, replacesSession: false)
    other.start(config())
    frame.timestampNs = 3_000_000_000
    other.process(frame)
    XCTAssertEqual(other.depth.acquired, 0)
    XCTAssertEqual(other.points.published, 1)
  }

  func testNoSamplesWhileTrackingIsLimitedAndLossRegainEmitTrackingResets() {
    frame.lidarDepth()
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config())
    frameAt(1_000_000_000)
    frame.trackingFailureReason = 3
    frameAt(1_300_000_000, tracking: IngestTrackingState.paused)
    frame.trackingFailureReason = 0
    frameAt(1_600_000_000)
    let messages = drain()
    XCTAssertEqual(events(messages), [
      IngestMessageLayout.sessionTrackingLost, IngestMessageLayout.sessionTrackingRegained,
      IngestMessageLayout.sessionTrackingReset,
    ])
    let reset = messages.last { $0.ingestKind == IngestMessageLayout.kindSessionEvent }!
    XCTAssertEqual(reset.i64(IngestMessageLayout.headerBytes + 8),
                   Int64(IngestMessageLayout.trackingResetTrackingFailure) | 3 << 8)
    XCTAssertEqual(shell.depth.acquired, 2)
  }

  func testNewSessionGenerationIncrementsTheGroupEpoch() {
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config(epoch: 3))
    frameAt(1_000_000_000)
    shell.onSessionStarted(sourceEpoch: 2, depthMode: IngestDepthMode.raw)
    frameAt(2_000_000_000)
    let messages = drain()
    XCTAssertEqual(events(messages), [IngestMessageLayout.sessionArRestarted])
    let restart = messages.first { $0.ingestKind == IngestMessageLayout.kindSessionEvent }!
    XCTAssertEqual(restart.i64(IngestMessageLayout.headerBytes + 8), 4)
    XCTAssertEqual(restart.u16(IngestMessageLayout.groupEpoch), 4)
    XCTAssertEqual(shell.eventEpoch, 4)
    XCTAssertEqual(shell.restarts, 1)
  }

  func testRunWithoutResetAfterAPauseIsAResume() {
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config())
    frameAt(1_000_000_000)
    shell.onSessionStopped()
    shell.onSessionStarted(sourceEpoch: 1, depthMode: IngestDepthMode.raw)
    frameAt(3_000_000_000)
    let messages = drain()
    XCTAssertEqual(events(messages), [IngestMessageLayout.sessionArResumed, IngestMessageLayout.sessionTrackingReset])
    XCTAssertEqual(messages.first { $0.ingestKind == IngestMessageLayout.kindSessionEvent }!
      .i64(IngestMessageLayout.headerBytes + 8), 2_000_000_000)
    XCTAssertEqual(shell.eventEpoch, 3)
  }

  func testInterruptionEndIsAResumeAndANewViewIsARestart() {
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config())
    frameAt(1_000_000_000)
    shell.onSessionInterruptionEnded()
    frameAt(1_100_000_000)
    XCTAssertEqual(events(drain()), [IngestMessageLayout.sessionArResumed, IngestMessageLayout.sessionTrackingReset])
    shell.onSourceAttached(sourceEpoch: nil, depthMode: IngestDepthMode.raw, replacesSession: true)
    shell.onSessionStarted(sourceEpoch: 1, depthMode: IngestDepthMode.raw)
    frameAt(1_200_000_000)
    XCTAssertEqual(events(drain()), [IngestMessageLayout.sessionArRestarted])
  }

  func testDepthModeChangeIsReported() {
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.raw, replacesSession: false)
    shell.start(config())
    frameAt(1_000_000_000)
    shell.onSessionStarted(sourceEpoch: 1, depthMode: IngestDepthMode.disabled)
    frameAt(1_100_000_000)
    let messages = drain()
    XCTAssertEqual(events(messages), [IngestMessageLayout.sessionDepthModeChanged])
  }

  func testPhotoSavedIsQueuedOnlyWhileStartedAndCarriesTheExposureEpoch() {
    final class Listener: DurablePhotoListener {
      var events: [[UInt8]] = []
      func onPhotoDurable(event: [UInt8], record: DurablePhotoRecord) { events.append(event) }
    }
    let listener = Listener()
    shell.photos.durableListeners = { [listener] }
    XCTAssertTrue(shell.photos.onDurableTerminal(PhotoSavedFixture.record()))
    XCTAssertTrue(drain().isEmpty, "not started: listeners only")
    XCTAssertEqual(listener.events.count, 1)

    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.disabled, replacesSession: false)
    shell.start(config(epoch: 3))
    frameAt(PhotoSavedFixture.exposureNs - 1_000)
    shell.start(config(epoch: 4))
    frameAt(PhotoSavedFixture.exposureNs + 100_000_000)
    let laterId = (32..<64).map { String(format: "%02x", $0) }.joined()
    XCTAssertTrue(shell.photos.onDurableTerminal(PhotoSavedFixture.record(captureId: laterId)))
    XCTAssertFalse(shell.photos.onDurableTerminal(PhotoSavedFixture.record(captureId: laterId)), "exactly once")
    let photos = drain().filter { $0.ingestKind == IngestMessageLayout.kindPhotoSaved }
    XCTAssertEqual(photos.count, 1)
    let m = photos[0]
    XCTAssertEqual(m.u8(IngestMessageLayout.headerBytes + PhotoSavedLayout.groupEpochLow), 3, "epoch at exposure")
    XCTAssertEqual(m.i64(IngestMessageLayout.sampleTimestampNs), PhotoSavedFixture.exposureNs)
    XCTAssertEqual(m.u8(IngestMessageLayout.poseMatch), IngestMessageLayout.poseMatchExact)
    XCTAssertEqual(m.f32(IngestMessageLayout.cameraToWorld + 14 * 4), 0.3)
    XCTAssertEqual(m.u16(IngestMessageLayout.width), 4000)
    XCTAssertEqual(m.f32(IngestMessageLayout.intrinsics + 4), 2801)
    XCTAssertEqual(shell.photos.duplicates, 1)
    XCTAssertEqual(listener.events.count, 2)
  }

  /// Epoch byte of the one photo saved message the shell queued for a
  /// photo exposed at [PhotoSavedFixture.exposureNs].
  private func photoEpoch() -> Int? {
    XCTAssertTrue(shell.photos.onDurableTerminal(PhotoSavedFixture.record()))
    let photos = drain().filter { $0.ingestKind == IngestMessageLayout.kindPhotoSaved }
    XCTAssertEqual(photos.count, 1)
    guard let m = photos.first else { return nil }
    XCTAssertEqual(m.u16(IngestMessageLayout.groupEpoch),
                   m.u8(IngestMessageLayout.headerBytes + PhotoSavedLayout.groupEpochLow), "header and event agree")
    return m.u8(IngestMessageLayout.headerBytes + PhotoSavedLayout.groupEpochLow)
  }

  func testPhotoExposedBeforeTheFirstGroupBelongsToTheGroupOfTheSameSession() {
    // The shell starts ungrouped, then the first group relabels the same AR
    // session; a still exposed before that relabel (a playback still up to
    // half the still interval older than its request) is the group's photo.
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.disabled, replacesSession: false)
    shell.start(config(epoch: PhotoSavedEmitter.ungroupedEpoch))
    frameAt(PhotoSavedFixture.exposureNs - 1_000_000_000)
    shell.start(config(epoch: 1))
    frameAt(PhotoSavedFixture.exposureNs + 500_000_000)
    XCTAssertEqual(photoEpoch(), 1)
  }

  func testPhotoExposedBeforeARestartIntoTheFirstGroupStaysUngrouped() {
    // A new AR session generation (other world frame) between the ungrouped
    // exposure and the group: the photo is not the group's.
    shell.onSourceAttached(sourceEpoch: 1, depthMode: IngestDepthMode.disabled, replacesSession: false)
    shell.start(config(epoch: PhotoSavedEmitter.ungroupedEpoch))
    frameAt(PhotoSavedFixture.exposureNs - 1_000_000_000)
    shell.onSessionStarted(sourceEpoch: 2, depthMode: IngestDepthMode.disabled)
    shell.start(config(epoch: 1))
    frameAt(PhotoSavedFixture.exposureNs + 500_000_000)
    XCTAssertEqual(photoEpoch(), PhotoSavedEmitter.ungroupedEpoch)
  }

  func testPhotoWithoutPoseCarriesNoPoseOrIntrinsics() {
    var record = PhotoSavedFixture.record()
    record.cameraToWorld = nil
    let event = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes)
    PhotoSavedEmitter.fillEvent(event, record: record, groupEpoch: 1)
    let bytes = event.data(PhotoSavedLayout.bytes)
    XCTAssertEqual(bytes.subdata(in: PhotoSavedLayout.cameraToWorldRows..<PhotoSavedLayout.sharpness),
                   Data(count: PhotoSavedLayout.sharpness - PhotoSavedLayout.cameraToWorldRows))
  }

  func testNonCanonicalIdsAreHashed() {
    XCTAssertEqual(PhotoSavedEmitter.photoIdBytes("capture-1").count, 32)
    XCTAssertNotEqual(PhotoSavedEmitter.photoIdBytes("capture-1"), [UInt8](repeating: 0, count: 32))
    XCTAssertEqual(PhotoSavedEmitter.attemptUuidBytes("not-a-uuid").count, 16)
    XCTAssertEqual(PhotoSavedEmitter.attemptUuidBytes(PhotoSavedFixture.attemptId), (0xa0...0xaf).map { UInt8($0) })
  }
}
