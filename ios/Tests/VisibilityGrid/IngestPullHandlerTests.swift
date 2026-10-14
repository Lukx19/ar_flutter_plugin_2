import Foundation
import XCTest

@testable import ar_flutter_plugin_2

/// Park, idle after 500 ms on an injected clock, never nil, copy-on-reply,
/// freshest-only replacement and in-order queues of the ingest pull handler.
final class IngestPullHandlerTests: XCTestCase {
  private let clock = FakeIngestClock(1_000)
  private lazy var pull = IngestPullHandler(clock: clock)
  private let capture = ReplyCapture()

  private func message(kind: Int, payload: Int = 4, fill: UInt8 = 0) -> IngestMessageBuffer {
    let b = IngestMessageBuffer(capacity: IngestMessageLayout.headerBytes + payload)
    IngestMessageWriter.begin(b, kind: kind)
    IngestMessageWriter.payloadBytes(b, payload)
    for i in 0..<payload { b.u8(IngestMessageLayout.headerBytes + i, Int(fill)) }
    return b
  }

  private func offer(kind: Int, payload: Int = 4, fill: UInt8 = 0) -> Bool {
    pull.offer(message(kind: kind, payload: payload, fill: fill), length: IngestMessageLayout.headerBytes + payload)
  }

  func testParkedPullIsAnsweredIdleOnlyAfterFiveHundredMilliseconds() {
    var scheduled: [Int64] = []
    pull.scheduleIdleCheck = { scheduled.append($0) }
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.count, 0)
    XCTAssertTrue(pull.isParked)
    XCTAssertEqual(scheduled, [IngestPullHandler.idleAfterNs])
    clock.now += 499_999_999
    XCTAssertEqual(pull.tick(), 1)
    XCTAssertEqual(capture.count, 0)
    clock.now += 1
    XCTAssertEqual(pull.tick(), -1)
    XCTAssertEqual(capture.count, 1)
    XCTAssertEqual(capture.last?.ingestKind, IngestMessageLayout.kindIdle)
    XCTAssertEqual(capture.last?.count, IngestMessageLayout.headerBytes)
    XCTAssertEqual(capture.last?.i64(IngestMessageLayout.sendTimeNs), clock.now)
    XCTAssertEqual(capture.last?.u8(IngestMessageLayout.poseMatch), IngestMessageLayout.poseMatchMissing)
    XCTAssertFalse(pull.isParked)
  }

  func testOneIdleCheckIsArmedAtATime() {
    var scheduled: [Int64] = []
    pull.scheduleIdleCheck = { scheduled.append($0) }
    pull.onPull(pullRequest(), reply: capture.reply)
    clock.now += 100_000_000
    // A new pull answers the parked one idle and parks itself; the armed check covers it.
    pull.onPull(pullRequest(), reply: capture.reply)
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.count, 2)
    XCTAssertEqual(pull.tick(), IngestPullHandler.idleAfterNs, "a tick while armed does not schedule")
    XCTAssertEqual(scheduled, [IngestPullHandler.idleAfterNs])
    // The armed check fires at the first pull's deadline and re-arms for the remainder.
    clock.now += 400_000_000
    pull.idleCheckFired()
    XCTAssertEqual(scheduled, [IngestPullHandler.idleAfterNs, 100_000_000])
    XCTAssertTrue(pull.isParked)
    clock.now += 100_000_000
    pull.idleCheckFired()
    XCTAssertFalse(pull.isParked)
    XCTAssertEqual(capture.count, 3)
    XCTAssertEqual(scheduled.count, 2, "nothing parked: no new check")
    // The next park arms again.
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(scheduled.count, 3)
  }

  func testOfferDeliversToTheParkedPullAtOnce() {
    pull.onPull(pullRequest(), reply: capture.reply)
    clock.now = 7_000
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindDepthImage))
    XCTAssertEqual(capture.count, 1)
    XCTAssertEqual(capture.last?.ingestKind, IngestMessageLayout.kindDepthImage)
    XCTAssertEqual(capture.last?.i64(IngestMessageLayout.sendTimeNs), 7_000)
    XCTAssertEqual(pull.tick(), -1, "nothing parked any more")
  }

  func testNeverRepliesNilAndAnswersMalformedAndClosedPullsIdle() {
    pull.onPull(nil, reply: capture.reply)
    pull.onPull(Data([1, 2, 3]), reply: capture.reply)
    var badMagic = pullRequest()
    badMagic[0] = 0
    pull.onPull(badMagic, reply: capture.reply)
    XCTAssertEqual(capture.replies.map(\.ingestKind), [0, 0, 0])
    pull.onPull(pullRequest(), reply: capture.reply)
    pull.close()
    XCTAssertEqual(capture.count, 4, "close answers the parked pull")
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.count, 5)
    XCTAssertFalse(offer(kind: IngestMessageLayout.kindDepthImage))
  }

  func testANewPullAnswersThePreviouslyParkedPullIdle() {
    pull.onPull(pullRequest(), reply: capture.reply)
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.count, 1)
    XCTAssertEqual(capture.last?.ingestKind, IngestMessageLayout.kindIdle)
    XCTAssertTrue(pull.isParked)
  }

  func testDrainPullIsNeverParked() {
    pull.onPull(pullRequest(mask: IngestPullHandler.drainKindMask), reply: capture.reply)
    XCTAssertEqual(capture.count, 1)
    XCTAssertFalse(pull.isParked)
  }

  func testRepliesAreCopiesThatLaterOffersNeverChange() {
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindDepthImage, fill: 0x11))
    pull.onPull(pullRequest(), reply: capture.reply)
    let first = capture.last!
    let snapshot = Data(first)
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindDepthImage, fill: 0x22))
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindDepthImage, fill: 0x33))
    pull.onPull(pullRequest(), reply: capture.reply)
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(first, snapshot)
    XCTAssertEqual(first.u8(IngestMessageLayout.headerBytes), 0x11)
    XCTAssertEqual(capture.replies[1].u8(IngestMessageLayout.headerBytes), 0x33)
  }

  func testFreshestOnlyKindsCountReplacementsAsDropped() {
    for i in 0..<3 { XCTAssertTrue(offer(kind: IngestMessageLayout.kindPointCloud, fill: UInt8(i))) }
    pull.onPull(pullRequest(), reply: capture.reply)
    let reply = capture.last!
    XCTAssertEqual(reply.u8(IngestMessageLayout.headerBytes), 2)
    XCTAssertEqual(reply.u32(IngestMessageLayout.dropped), 2)
    XCTAssertEqual(reply.u32(IngestMessageLayout.sequence), 2)
    XCTAssertEqual(pull.replacedCount(kind: IngestMessageLayout.kindPointCloud), 2)
  }

  func testQueuedKindsKeepOrderAndComeBeforeFreshestOnlyKinds() {
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindDepthImage))
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindSessionEvent, payload: 16, fill: 1))
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindSessionEvent, payload: 16, fill: 2))
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindPhotoSaved, payload: 128))
    for _ in 0..<4 { pull.onPull(pullRequest(), reply: capture.reply) }
    XCTAssertEqual(capture.replies.map(\.ingestKind), [4, 6, 6, 1])
    XCTAssertEqual(capture.replies[1].u8(IngestMessageLayout.headerBytes), 1)
    XCTAssertEqual(capture.replies[2].u8(IngestMessageLayout.headerBytes), 2)
  }

  func testMaskExcludesKindsAndMalformedOffersAreRefused() {
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindDepthImage))
    pull.onPull(pullRequest(mask: 1 << IngestMessageLayout.kindPointCloud), reply: capture.reply)
    XCTAssertEqual(capture.count, 0, "depth not accepted: parked")
    let b = message(kind: IngestMessageLayout.kindDepthImage)
    XCTAssertFalse(pull.offer(b, length: IngestMessageLayout.headerBytes + 3), "length disagrees with the header")
    XCTAssertFalse(offer(kind: IngestMessageLayout.kindAnchorPoseUpdate), "kind 7 never rides the pull")
  }

  func testFullPhotoQueueOverflowsAndCountsTheDrop() {
    for _ in 0..<IngestPullHandler.photoQueue { XCTAssertTrue(offer(kind: IngestMessageLayout.kindPhotoSaved, payload: 128)) }
    XCTAssertFalse(offer(kind: IngestMessageLayout.kindPhotoSaved, payload: 128))
    XCTAssertEqual(pull.queueOverflowCount, 1)
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.last?.u32(IngestMessageLayout.dropped), 1)
  }

  func testClearQueuedDropsDataButKeepsTheParkedPull() {
    XCTAssertTrue(offer(kind: IngestMessageLayout.kindSessionEvent, payload: 16))
    pull.clearQueued()
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertTrue(pull.isParked)
    pull.clearQueued()
    XCTAssertTrue(pull.isParked)
  }
}
