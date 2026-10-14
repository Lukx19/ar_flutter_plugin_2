import Foundation
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Anchor pose tracking channel: query handling, anchor creation on the AR
/// queue, update records after movement, photo anchors, refusals.
final class AnchorPoseTrackerTests: XCTestCase {
  final class FakeAnchor {
    var tq: [Float]
    var tracking = AnchorTracking.tracking
    init(_ tq: [Float]) { self.tq = tq }
  }

  final class FakeBackend: AnchorBackend {
    var created: [FakeAnchor] = []
    var detached = 0
    var refuse = false
    func create(session: AnyObject, tq: UnsafePointer<Float>) -> AnyObject? {
      if refuse { return nil }
      let a = FakeAnchor(Array(UnsafeBufferPointer(start: tq, count: 7)))
      created.append(a)
      return a
    }
    func detach(_ anchor: AnyObject, session: AnyObject?) { detached += 1 }
    func key(of anchor: AnyObject) -> AnyHashable { AnyHashable(ObjectIdentifier(anchor)) }
    func tracking(_ anchor: AnyObject) -> Int { (anchor as! FakeAnchor).tracking }
    func readPose(_ anchor: AnyObject, into out: UnsafeMutablePointer<Float>) {
      for (i, v) in (anchor as! FakeAnchor).tq.enumerated() { out[i] = v }
    }
  }

  final class FakeFrame: AnchorFrame {
    var timestampNs: Int64 = 0
    var cameraTracking = IngestTrackingState.tracking
    var anchors: [AnyObject] = []
    func readCameraPose(into out: UnsafeMutablePointer<Float>) {
      let tq: [Float] = [0.5, 1.25, -2, 0, 0, 0, 1]
      for (i, v) in tq.enumerated() { out[i] = v }
    }
    func loadUpdatedAnchors() -> Int { anchors.count }
    func updatedAnchor(_ index: Int) -> AnyObject { anchors[index] }
  }

  private let clock = FakeIngestClock(1_000)
  private let backend = FakeBackend()
  private lazy var producer = AnchorPoseUpdateProducer(backend: backend, clock: clock, readTimer: FakeIngestClock(0))
  private let session = NSObject()
  private let frame = FakeFrame()
  private var replies: [Data] = []

  /// `C3PQ` query with keyframe requests at translations [xs].
  private func query(flags: Int = PoseQueryLayout.flagTracking, epoch: Int = 3, requests xs: [Float] = [],
                     submap: Bool = false) -> Data {
    var d = Data(count: PoseQueryLayout.headerBytes + xs.count * PoseQueryLayout.requestBytes)
    d.withUnsafeMutableBytes { raw in
      func u16(_ o: Int, _ v: Int) { raw.storeBytes(of: UInt16(v).littleEndian, toByteOffset: o, as: UInt16.self) }
      raw.storeBytes(of: PoseQueryLayout.magic.littleEndian, toByteOffset: 0, as: UInt32.self)
      u16(PoseQueryLayout.versionOffset, 1)
      u16(PoseQueryLayout.flagsOffset, flags)
      u16(PoseQueryLayout.countOffset, xs.count)
      u16(PoseQueryLayout.groupEpochOffset, epoch)
      for (i, x) in xs.enumerated() {
        let o = PoseQueryLayout.headerBytes + i * PoseQueryLayout.requestBytes
        raw.storeBytes(of: (AnchorIds.firstKeyframeId + UInt32(i)).littleEndian, toByteOffset: o, as: UInt32.self)
        raw.storeBytes(of: UInt32(submap ? PoseQueryLayout.requestFlagSubmap : 0).littleEndian,
                       toByteOffset: o + PoseQueryLayout.requestFlagsOffset, as: UInt32.self)
        raw.storeBytes(of: Int64(100 + i).littleEndian, toByteOffset: o + PoseQueryLayout.requestSubjectOffset, as: Int64.self)
        raw.storeBytes(of: Int64(5_000 + i).littleEndian, toByteOffset: o + PoseQueryLayout.requestCaptureNsOffset, as: Int64.self)
        let tq: [Float] = [x, 0, 0, 0, 0, 0, 1]
        for (k, v) in tq.enumerated() {
          raw.storeBytes(of: v.bitPattern.littleEndian, toByteOffset: o + PoseQueryLayout.requestPoseOffset + 4 * k, as: UInt32.self)
        }
      }
    }
    return d
  }

  private func send(_ q: Data?) -> Data {
    producer.onQuery(q) { replies.append($0) }
    return replies.last!
  }

  private func frameAt(_ ns: Int64) {
    frame.timestampNs = ns
    frame.anchors = backend.created
    producer.onFrame(session: session, frame: frame)
  }

  func testMalformedQueriesAreRefusedWithStatusTwoNeverNil() {
    XCTAssertEqual(send(nil), ChannelStatusReply.data(2))
    XCTAssertEqual(send(Data([1, 2, 3])), ChannelStatusReply.data(2))
    var wrongCount = query(requests: [1])
    wrongCount[PoseQueryLayout.countOffset] = 2
    XCTAssertEqual(send(wrongCount), ChannelStatusReply.data(2))
    producer.close()
    XCTAssertEqual(send(query()), ChannelStatusReply.data(2), "closed")
    XCTAssertEqual(producer.queriesRefused, 4)
  }

  func testKeyframeRequestCreatesAnAnchorAndReportsCreationAndUpdate() {
    var reply = send(query(requests: [1, 2]))
    XCTAssertEqual(reply.ingestKind, IngestMessageLayout.kindAnchorPoseUpdate)
    XCTAssertEqual(reply.u16(IngestMessageLayout.headerBytes), 0, "nothing created before a frame")
    XCTAssertTrue(producer.tracker.armed)
    frameAt(2_000_000)
    XCTAssertEqual(backend.created.count, 2)
    reply = send(query())
    let p = IngestMessageLayout.headerBytes
    XCTAssertEqual(reply.u16(p), 2, "updates")
    XCTAssertEqual(reply.u16(p + 2), 2, "creations")
    XCTAssertEqual(reply.count, p + AnchorPoseUpdateLayout.payloadBytes(updates: 2, creations: 2))
    XCTAssertEqual(reply.u32(IngestMessageLayout.payloadBytes), UInt32(AnchorPoseUpdateLayout.payloadBytes(updates: 2, creations: 2)))
    let update = p + AnchorPoseUpdateLayout.prefixBytes
    XCTAssertEqual(reply.u32(update), AnchorIds.firstKeyframeId)
    XCTAssertEqual(reply.u8(update + AnchorPoseUpdateLayout.updateKindOffset), AnchorKind.keyframe)
    XCTAssertEqual(reply.f32(update + AnchorPoseUpdateLayout.updateMatrixOffset), 1, "r00")
    XCTAssertEqual(reply.f32(update + AnchorPoseUpdateLayout.updateMatrixOffset + 12), 1, "tx")
    let creation = update + 2 * AnchorPoseUpdateLayout.updateBytes
    XCTAssertEqual(reply.u8(creation + AnchorPoseUpdateLayout.creationStatusOffset), AnchorCreation.created)
    XCTAssertEqual(reply.i64(creation + AnchorPoseUpdateLayout.creationSubjectOffset), 100)
    XCTAssertEqual(reply.i64(creation + AnchorPoseUpdateLayout.creationCaptureNsOffset), 5_000)
    XCTAssertEqual(reply.i64(creation + AnchorPoseUpdateLayout.creationFrameNsOffset), 2_000_000)
    XCTAssertEqual(reply.u32(AnchorPoseUpdateLayout.trackedAnchorsOffset), 2)
    XCTAssertEqual(reply.u16(IngestMessageLayout.groupEpoch), 3)
    XCTAssertEqual(reply.i64(IngestMessageLayout.sampleTimestampNs), 2_000_000)
    XCTAssertEqual(reply.f32(IngestMessageLayout.cameraToWorld + 13 * 4), 1.25)
    XCTAssertEqual(reply.u32(IngestMessageLayout.sequence), 1)

    // Unmoved: no update; moved by 2 mm: one update.
    frameAt(3_000_000)
    XCTAssertEqual(send(query()).u16(p), 0)
    backend.created[1].tq[0] += 0.002
    frameAt(4_000_000)
    reply = send(query())
    XCTAssertEqual(reply.u16(p), 1)
    XCTAssertEqual(reply.u32(update), AnchorIds.firstKeyframeId + 1)
  }

  func testCreationWhileNotTrackingAndRefusedCreationAreReported() {
    _ = send(query(requests: [1]))
    frame.cameraTracking = IngestTrackingState.paused
    frameAt(1)
    var reply = send(query())
    let creation = IngestMessageLayout.headerBytes + AnchorPoseUpdateLayout.prefixBytes
    XCTAssertEqual(reply.u8(creation + AnchorPoseUpdateLayout.creationStatusOffset), AnchorCreation.notTracking)
    frame.cameraTracking = IngestTrackingState.tracking
    backend.refuse = true
    _ = send(query(requests: [1]))
    frameAt(2)
    reply = send(query())
    XCTAssertEqual(reply.u8(creation + AnchorPoseUpdateLayout.creationStatusOffset), AnchorCreation.failed)
  }

  func testTrackingOffDetachesEveryAnchorOnTheNextFrame() {
    _ = send(query(requests: [1, 2]))
    frameAt(1)
    XCTAssertEqual(producer.tracker.trackedAnchors, 2)
    _ = send(query(flags: 0))
    XCTAssertEqual(producer.tracker.pendingDetaches, 2)
    XCTAssertTrue(producer.tracker.armed, "armed until the detaches ran")
    frameAt(2)
    XCTAssertEqual(backend.detached, 2)
    XCTAssertFalse(producer.tracker.armed)
  }

  func testTrackingResetOnTheSameSessionEndsEveryAnchorOfThePreviousEpoch() {
    producer.tracker.onSessionStarted(epoch: 1)
    _ = send(query(requests: [1, 2]))
    frameAt(1_000)
    _ = send(query())
    XCTAssertEqual(producer.tracker.trackedAnchors, 2)
    producer.tracker.onSessionStarted(epoch: 1)
    XCTAssertEqual(producer.tracker.trackedAnchors, 2, "same epoch keeps the anchors")

    _ = send(query(requests: [3]))
    producer.tracker.onSessionStarted(epoch: 2)
    XCTAssertEqual(producer.tracker.trackedAnchors, 0)
    XCTAssertEqual(producer.tracker.pendingDetaches, 2)
    XCTAssertEqual(producer.tracker.sessionReplacements, 1)
    XCTAssertTrue(producer.tracker.armed)
    frameAt(2_000)
    XCTAssertEqual(backend.detached, 2, "detached on the same session object")
    XCTAssertEqual(backend.created.count, 2, "the queued creation is abandoned")

    let reply = send(query())
    let p = IngestMessageLayout.headerBytes
    XCTAssertEqual(reply.u16(p), 2, "one final record per anchor")
    XCTAssertEqual(reply.u16(p + 2), 1, "abandoned creation result")
    let update = p + AnchorPoseUpdateLayout.prefixBytes
    for i in 0..<2 {
      XCTAssertEqual(reply.u8(update + i * AnchorPoseUpdateLayout.updateBytes + AnchorPoseUpdateLayout.updateTrackingOffset),
                     AnchorTracking.stopped)
    }
    let creation = update + 2 * AnchorPoseUpdateLayout.updateBytes
    XCTAssertEqual(reply.u8(creation + AnchorPoseUpdateLayout.creationStatusOffset), AnchorCreation.notTracking)
    XCTAssertEqual(reply.u32(AnchorPoseUpdateLayout.trackedAnchorsOffset), 0)

    // Old anchors stay out of later replies even when ARKit still reports them.
    backend.created[0].tq[0] += 1
    frameAt(3_000)
    XCTAssertEqual(send(query()).u16(p), 0)
  }

  func testInactiveAfterTenSecondsWithoutQuery() {
    _ = send(query(requests: [1]))
    frameAt(1)
    clock.now += AnchorPoseTracker.activeTimeoutNs
    frameAt(2)
    XCTAssertEqual(backend.detached, 1)
    XCTAssertFalse(producer.tracker.isActive)
  }

  func testDurablePhotoQueuesAPhotoAnchorWhileTracking() {
    var event = [UInt8](repeating: 0, count: PhotoSavedLayout.bytes)
    let buffer = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes)
    PhotoSavedEmitter.fillEvent(buffer, record: PhotoSavedFixture.record(), groupEpoch: 3)
    event = [UInt8](UnsafeRawBufferPointer(start: buffer.bytes, count: PhotoSavedLayout.bytes))
    producer.onPhotoDurable(event: event, record: PhotoSavedFixture.record())
    XCTAssertTrue(backend.created.isEmpty, "inactive: ignored")
    _ = send(query())
    producer.onPhotoDurable(event: event, record: PhotoSavedFixture.record())
    frameAt(1)
    XCTAssertEqual(backend.created.count, 1)
    XCTAssertEqual(backend.created[0].tq[2], 0.3, accuracy: 1e-6)
    XCTAssertEqual(backend.created[0].tq[6], 1, accuracy: 1e-6)
    let reply = send(query())
    let creation = IngestMessageLayout.headerBytes + AnchorPoseUpdateLayout.prefixBytes + AnchorPoseUpdateLayout.updateBytes
    XCTAssertEqual(reply.u32(creation), 1, "photo anchors are numbered from 1")
    XCTAssertEqual(reply.u8(creation + AnchorPoseUpdateLayout.creationKindOffset), AnchorKind.photo)
    XCTAssertEqual(reply.i64(creation + AnchorPoseUpdateLayout.creationSubjectOffset), 0x0706_0504_0302_0100)
    XCTAssertEqual(reply.i64(creation + AnchorPoseUpdateLayout.creationCaptureNsOffset), PhotoSavedFixture.exposureNs)
  }

  func testLargeJumpIsReportedOnce() {
    var jumps: [Int] = []
    producer.tracker.jumpListener = { _, mm in jumps.append(mm) }
    _ = send(query(requests: [1]))
    frameAt(1)
    backend.created[0].tq[1] += 0.1
    frameAt(2)
    XCTAssertEqual(jumps, [100])
  }

  func testPoseConversionsRoundTrip() {
    let angle: Float = 0.7
    let q = simd_quatf(angle: angle, axis: simd_normalize(simd_float3(1, 2, 3)))
    var tq: [Float] = [1, 2, 3, q.imag.x, q.imag.y, q.imag.z, q.real]
    var rows = [Float](repeating: 0, count: 12)
    tq.withUnsafeBufferPointer { t in rows.withUnsafeMutableBufferPointer { AnchorPoseTracker.poseMatrix(t.baseAddress!, $0.baseAddress!, rows: true) } }
    var back = [Float](repeating: 0, count: 7)
    let ok = rows.withUnsafeBufferPointer { r in back.withUnsafeMutableBufferPointer { AnchorPoseTracker.rowsToPose(r.baseAddress!, $0.baseAddress!) } }
    XCTAssertTrue(ok)
    for k in 0..<7 { XCTAssertEqual(back[k], tq[k], accuracy: 1e-5) }
    tq = [Float](repeating: 0, count: 12)
    XCTAssertFalse(tq.withUnsafeBufferPointer { r in back.withUnsafeMutableBufferPointer { AnchorPoseTracker.rowsToPose(r.baseAddress!, $0.baseAddress!) } })
  }
}
