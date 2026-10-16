import XCTest
@testable import ar_flutter_plugin_2

/// Capture view frame timing for the performance snapshot: draw intervals,
/// gaps over 100 ms (upload-caused when the previous or the current frame
/// uploaded grid deltas),
/// encode and GPU time quantiles, reset.
final class CaptureViewFrameStatsTests: XCTestCase {
  func testIntervalsGapsAndUploadGaps() {
    let stats = CaptureViewFrameStats()
    var t = 10.0
    for _ in 0..<100 {
      stats.recordDraw(nowS: t, encodeMs: 2, uploadedDeltas: false, overlayDrawn: true)
      t += 1.0 / 60
    }
    t += 0.150 - 1.0 / 60
    stats.recordDraw(nowS: t, encodeMs: 9, uploadedDeltas: true, overlayDrawn: true)
    t += 0.120
    stats.recordDraw(nowS: t, encodeMs: 3, uploadedDeltas: false, overlayDrawn: false)
    stats.recordGpu(ms: 4.5)
    stats.recordGpu(ms: .nan)

    let snapshot = stats.snapshot(nowS: t)
    XCTAssertEqual(snapshot["frames"] as? Int, 102)
    XCTAssertEqual(snapshot["uploadFrames"] as? Int, 1)
    XCTAssertEqual(snapshot["overlayFrames"] as? Int, 101)
    XCTAssertEqual(snapshot["gaps"] as? Int, 2)
    XCTAssertEqual(snapshot["uploadGaps"] as? Int, 2,
                   "the 150 ms gap ends at the upload frame, the 120 ms gap follows it")
    XCTAssertEqual(try XCTUnwrap(snapshot["longestGapMs"] as? Double), 150, accuracy: 0.01)
    XCTAssertEqual(try XCTUnwrap(snapshot["windowMs"] as? Double), (t - 10) * 1000, accuracy: 0.01)
    let interval = try? XCTUnwrap(snapshot["intervalMs"] as? [String: Any])
    XCTAssertEqual(interval?["count"] as? Int, 101)
    XCTAssertEqual(interval?["p50Ms"] as? Double, 17, "16.7 ms lands in the 16 ms bucket")
    // 120 ms after a sum of doubles may land in the 119 or 120 ms bucket.
    XCTAssertEqual(try XCTUnwrap(interval?["p99Ms"] as? Double), 120.5, accuracy: 0.5)
    XCTAssertEqual(try XCTUnwrap(interval?["maxMs"] as? Double), 150, accuracy: 0.01)
    let encode = snapshot["encodeMs"] as? [String: Any]
    XCTAssertEqual(encode?["maxMs"] as? Double, 9)
    let gpu = snapshot["gpuMs"] as? [String: Any]
    XCTAssertEqual(gpu?["count"] as? Int, 1, "a non-finite GPU time is ignored")

    stats.reset()
    let empty = stats.snapshot(nowS: t)
    XCTAssertEqual(empty["frames"] as? Int, 0)
    XCTAssertEqual(empty["gaps"] as? Int, 0)
    XCTAssertEqual((empty["intervalMs"] as? [String: Any])?["p95Ms"] as? Double, 0)
    stats.recordDraw(nowS: t + 1, encodeMs: 1, uploadedDeltas: false, overlayDrawn: false)
    XCTAssertEqual((stats.snapshot(nowS: t + 1)["intervalMs"] as? [String: Any])?["count"] as? Int, 0,
                   "the first draw after a reset has no interval")
  }

  func testAGapAfterAnUploadFrameIsUploadCausedEvenWhenItEndsAtAFrameWithoutUploads() {
    let stats = CaptureViewFrameStats()
    stats.recordDraw(nowS: 0, encodeMs: 2, uploadedDeltas: false, overlayDrawn: true)
    stats.recordDraw(nowS: 0.016, encodeMs: 40, uploadedDeltas: true, overlayDrawn: true)
    stats.recordDraw(nowS: 0.196, encodeMs: 2, uploadedDeltas: false, overlayDrawn: true)
    stats.recordDraw(nowS: 0.212, encodeMs: 2, uploadedDeltas: false, overlayDrawn: true)
    stats.recordDraw(nowS: 0.362, encodeMs: 2, uploadedDeltas: false, overlayDrawn: true)
    let snapshot = stats.snapshot(nowS: 0.362)
    XCTAssertEqual(snapshot["gaps"] as? Int, 2)
    XCTAssertEqual(snapshot["uploadGaps"] as? Int, 1,
                   "only the 180 ms gap follows an upload frame; the 150 ms gap has none on either side")
  }

  func testIntervalsBeyondTheHistogramLandInTheOverflowBucket() {
    let stats = CaptureViewFrameStats()
    stats.recordDraw(nowS: 0, encodeMs: 0, uploadedDeltas: false, overlayDrawn: false)
    stats.recordDraw(nowS: 2, encodeMs: 0, uploadedDeltas: false, overlayDrawn: false)
    let interval = stats.snapshot(nowS: 2)["intervalMs"] as? [String: Any]
    XCTAssertEqual(interval?["p50Ms"] as? Double, Double(CaptureViewFrameStats.bucketCount))
    XCTAssertEqual(interval?["maxMs"] as? Double, 2000)
  }
}
