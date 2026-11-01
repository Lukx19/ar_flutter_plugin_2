import Foundation
import XCTest

@testable import ar_flutter_plugin_2

final class StillExposureAssessmentTests: XCTestCase {
  func testSlowWriterCoalescesLatestSnapshotAndRetainsOnly64MetadataRecords() throws {
    let first = expectation(description: "first write")
    let latest = expectation(description: "latest bounded snapshot")
    let lock = NSLock()
    var writes = 0
    var release: (() -> Void)?
    var finalData: Data?
    let store = StillExposureAssessments(url: URL(fileURLWithPath: "/unused-diagnostic-test")) {
      bytes, _, done in
      lock.lock()
      writes += 1
      let count = writes
      lock.unlock()
      if count == 1 {
        release = done
        first.fulfill()
      } else {
        finalData = bytes
        done()
        latest.fulfill()
      }
    }
    store.append(record(0))
    wait(for: [first], timeout: 2)
    for index in 1...100 { store.append(record(index)) }
    lock.lock()
    XCTAssertEqual(writes, 1)
    lock.unlock()
    try XCTUnwrap(release)()
    wait(for: [latest], timeout: 2)
    let root = try XCTUnwrap(
      JSONSerialization.jsonObject(with: XCTUnwrap(finalData)) as? [String: Any])
    let rows = try XCTUnwrap(root["assessments"] as? [[String: Any]])
    XCTAssertEqual(rows.count, 64)
    XCTAssertEqual(rows.first?["attemptOrdinal"] as? Int, 37)
    XCTAssertEqual(rows.last?["attemptOrdinal"] as? Int, 100)
    XCTAssertEqual(rows.last?["minimumSharpness"] as? Double, 110)
    XCTAssertNil(rows.last?["cameraToWorld"])
    XCTAssertNil(rows.last?["image"])
    lock.lock()
    XCTAssertEqual(writes, 2)
    lock.unlock()
  }

  func testNonfiniteMeasurementsAreOmittedWithoutChangingQualityThreshold() throws {
    let done = expectation(description: "sanitized diagnostic")
    var data: Data?
    let store = StillExposureAssessments(url: URL(fileURLWithPath: "/unused-diagnostic-test")) {
      bytes, _, completed in
      data = bytes
      completed()
      done.fulfill()
    }
    var value = record(1)
    value.iso = .nan
    value.sharpness = .infinity
    value.targetISO = .nan
    value.attemptId = String(repeating: "a", count: 1000)
    store.append(value)
    wait(for: [done], timeout: 2)
    let root = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(data)) as? [String: Any])
    let row = try XCTUnwrap((root["assessments"] as? [[String: Any]])?.first)
    XCTAssertNil(row["iso"])
    XCTAssertNil(row["sharpness"])
    XCTAssertNil(row["targetISO"])
    XCTAssertEqual((row["attemptId"] as? String)?.count, 128)
    XCTAssertEqual(row["minimumSharpness"] as? Double, 110)
  }

  func testFailedDiagnosticWriteDoesNotBlockLaterAssessments() {
    let first = expectation(description: "failed write completes")
    let second = expectation(description: "next assessment still written")
    let lock = NSLock()
    var attempts = 0
    let store = StillExposureAssessments(
      url: URL(fileURLWithPath: "/dev/null/not-a-directory/assessment.json")
    ) { bytes, url, completed in
      defer { completed() }
      do {
        try bytes.write(to: url, options: .atomic)
        XCTFail("the invalid diagnostic destination must fail")
      } catch {
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
      }
      lock.lock()
      attempts += 1
      let attempt = attempts
      lock.unlock()
      if attempt == 1 { first.fulfill() } else { second.fulfill() }
    }
    store.append(record(1))
    wait(for: [first], timeout: 2)
    store.append(record(2))
    wait(for: [second], timeout: 2)
    lock.lock()
    XCTAssertEqual(attempts, 2)
    lock.unlock()
  }

  private func record(_ ordinal: Int) -> StillExposureAssessment {
    StillExposureAssessment(
      attemptOrdinal: Int64(ordinal), lane: "automatic", mode: "single", format: "heic",
      exposureSeconds: 0.01, iso: 600, targetExposureSeconds: 0.01, targetISO: 600,
      sharpness: 109, assessment: "REJECTED_BLUR")
  }
}
