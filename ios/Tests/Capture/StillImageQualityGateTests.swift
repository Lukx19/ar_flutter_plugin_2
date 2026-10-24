import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// The production durable boundary rejects soft or unassessed originals before storage.
/// Synthetic pixel buffers exercise actual JPEG encoding and analysis; these are host
/// intent/storage checks, not physical camera sharpness certification.
final class StillImageQualityGateTests: XCTestCase {
  private func componentSet(score: Double? = 321.5, shot: CapturedShot? = nil) -> StillComponentSet {
    var metadata = CaptureFixture.metadata()
    metadata.sharpness = score
    return StillComponentSet(
      qualifier: CaptureAttemptQualifier(identity: CaptureFixture.request().accepted.identity, exposureGeneration: 1),
      components: [(.jpeg, CaptureFixture.jpeg)], metadata: metadata, shot: shot)
  }

  private func reason(_ set: StillComponentSet) -> String? {
    switch StillImageQualityGate.outcome(for: set) {
    case .failure(let qualifier, let reason):
      XCTAssertEqual(qualifier, set.qualifier)
      return reason
    case .components(let result):
      XCTAssertEqual(result.qualifier, set.qualifier)
      XCTAssertEqual(result.components.first?.1, set.components.first?.1)
      XCTAssertEqual(result.metadata.exposureTimestampNs, set.metadata.exposureTimestampNs)
      return nil
    }
  }

  func testProductionThresholdIsInclusiveWithoutChangingJpegOrIdentity() {
    XCTAssertNil(reason(componentSet(score: LaplacianSharpness.productionThreshold)))
    XCTAssertNil(reason(componentSet(score: 321.5)))
    XCTAssertEqual(reason(componentSet(score: 109.999)), "REJECTED_BLUR")
    XCTAssertEqual(reason(componentSet(score: 0)), "REJECTED_BLUR")
  }

  func testMissingNonfiniteOrNegativeAssessmentFailsClosed() {
    let invalidScores: [Double?] = [nil, .nan, .infinity, -.infinity, -1]
    for score in invalidScores {
      XCTAssertEqual(reason(componentSet(score: score)), "QUALITY_ANALYSIS_FAILED")
    }
  }

  func testEveryBurstMemberMustPassAndRejectionNeverRewritesIndices() {
    for rejectedIndex in 0..<3 {
      var shot = ShotFixture.burst()
      for index in shot.images.indices { shot.images[index].metadata.sharpness = 321.5 }
      shot.images[rejectedIndex].metadata.sharpness = 109
      let originalIndices = shot.images.map { $0.index }
      XCTAssertEqual(reason(componentSet(shot: shot)), "REJECTED_BLUR")
      XCTAssertEqual(shot.images.map { $0.index }, originalIndices)
      shot.images[rejectedIndex].metadata.sharpness = nil
      XCTAssertEqual(reason(componentSet(shot: shot)), "QUALITY_ANALYSIS_FAILED")
    }
  }

  func testGoodBurstRetainsItsShotMetadataBytesAndTrackingFlags() {
    var shot = ShotFixture.burst()
    for index in shot.images.indices { shot.images[index].metadata.sharpness = 321.5 }
    let set = componentSet(shot: shot)
    guard case .components(let accepted) = StillImageQualityGate.outcome(for: set) else {
      return XCTFail("all assessed images should pass")
    }
    XCTAssertEqual(accepted.shot?.shotId, shot.shotId)
    XCTAssertEqual(accepted.shot?.images.map { $0.index }, [0, 1, 2])
    XCTAssertEqual(accepted.shot?.images.map { $0.jpeg }, shot.images.map { $0.jpeg })
    XCTAssertEqual(accepted.shot?.images.map { $0.flags }, shot.images.map { $0.flags })
  }

  private struct Result {
    var events: [NativeCaptureEvent]
    var photos: [DurableTerminalPhoto]
    var reservedBytes: Int64
    var committedBytes: Int64
  }

  private func captureDurably(flat: [Bool], mode: StillCaptureMode = .single) throws -> Result {
    let root = CaptureFixture.temporaryDirectory("still-quality-gate")
    defer { try? FileManager.default.removeItem(at: root) }
    let source = StillFrameSource()
    source.frames = flat.enumerated().map { index, isFlat in
      ShotFixture.frame(timestamp: 100 + Double(index), pose: ShotFixture.pose(x: Float(index)), flat: isFlat)
    }
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 95, deviceModel: "test", shutterFeedback: {})
    exposure.mode = mode
    if mode == .burst { exposure.burst = BurstSettings(imageCount: flat.count, intervalMs: 0) }
    let (store, budget) = try CaptureFixture.store(root)
    var events: [NativeCaptureEvent] = []
    var photos: [DurableTerminalPhoto] = []
    let terminal = expectation(description: "one durable terminal")
    let owner = NativeCaptureAdmissionOwner(store: store, exposure: exposure, nowMs: { 1_000 }, events: { event in
      events.append(event)
      if event.isTerminal { terminal.fulfill() }
    }, durableTerminal: { photos.append($0) })
    owner.deliver = { block in DispatchQueue.main.async(execute: block) }
    _ = try owner.admit(CaptureFixture.request())
    wait(for: [terminal], timeout: 10)
    XCTAssertEqual(events.filter { $0.isTerminal }.count, 1)
    return Result(events: events, photos: photos,
                  reservedBytes: try budget.reservedBytesTotal(), committedBytes: try budget.committedBytes())
  }

  func testRealEncodedFlatSingleIsAbandonedBeforeAnyImageIsCommitted() throws {
    let result = try captureDurably(flat: [true])
    XCTAssertEqual(result.events.filter { $0.isTerminal }.map { $0.reason }, ["camera-REJECTED_BLUR"])
    XCTAssertTrue(result.photos.isEmpty)
    XCTAssertEqual(result.reservedBytes, 0)
    XCTAssertEqual(result.committedBytes, 0)
    XCTAssertFalse(result.events.contains { $0.kind == .finalizing })
  }

  func testRealEncodedGoodSingleStillCommitsAtTheSameRequestedQuality() throws {
    let result = try captureDurably(flat: [false])
    XCTAssertEqual(result.events.filter { $0.isTerminal }.map { $0.kind }, [.committed])
    XCTAssertEqual(result.photos.count, 1)
    XCTAssertGreaterThan(result.photos.first?.metadata?.sharpness ?? 0, LaplacianSharpness.productionThreshold)
    XCTAssertEqual(result.reservedBytes, 0)
    XCTAssertGreaterThan(result.committedBytes, 0)
  }

  func testRealEncodedMixedBurstRejectsTheWholeShotWithoutSavingItsGoodMember() throws {
    for images in [[false, true], [true, false]] {
      let result = try captureDurably(flat: images, mode: .burst)
      XCTAssertEqual(result.events.filter { $0.isTerminal }.map { $0.reason }, ["camera-REJECTED_BLUR"])
      XCTAssertTrue(result.photos.isEmpty)
      XCTAssertEqual(result.reservedBytes, 0)
      XCTAssertEqual(result.committedBytes, 0)
    }
  }

  func testRealEncodedQualityShotAlsoRejectsFlatImagesBeforeStorage() throws {
    let result = try captureDurably(flat: [true], mode: .inArQuality)
    XCTAssertEqual(result.events.filter { $0.isTerminal }.map { $0.reason }, ["camera-REJECTED_BLUR"])
    XCTAssertTrue(result.photos.isEmpty)
    XCTAssertEqual(result.committedBytes, 0)
  }
}
