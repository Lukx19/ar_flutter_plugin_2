import XCTest
@testable import ar_flutter_plugin_2

/// The storage budget ledger on its own: an unfinished reservation write is
/// an orphan that recovery removes with its allocation, a complete but
/// malformed one stays corrupt; the capture quota counts the bytes the
/// ledger already holds as capacity and follows the free space at each
/// check, the free space bounds only new bytes, and settlement is never
/// refused.
final class StorageBudgetLedgerTests: XCTestCase {
  private static let gib: Int64 = 1024 * 1024 * 1024
  private static let mib: Int64 = 1024 * 1024
  private static let owner = "capture:\(DurableStoreEncoding.safeName("session")):\(DurableStoreEncoding.safeName("commit"))"

  private var root: URL!
  private var free: Int64 = 0

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("budget-ledger")
    free = 6 * Self.gib
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  private func ledger(_ policy: StorageBudgetPolicy = StorageBudgetPolicy(
    captureShareMinimumBytes: NativeCaptureReservationBounds.captureCoexistenceBytes, freeSpaceFloorBytes: 0)
  ) throws -> StorageBudgetLedger {
    try StorageBudgetLedger(
      files: RootedDurableFiles(backend: try PosixDurableFileBackend(root: root)), policy: policy, freeBytes: { [unowned self] in self.free })
  }

  private func url(_ relative: String) -> URL { root.appendingPathComponent(relative) }
  private func exists(_ relative: String) -> Bool { FileManager.default.fileExists(atPath: url(relative).path) }
  private func metadata(_ token: String) -> String { "reservations-v2/\(token).reservation" }
  private func allocation(_ token: String) -> String { "reservations-v2/\(token).allocation" }

  /// Committed bytes of earlier photos, without allocating them.
  private func seedCommitted(_ bytes: Int64) throws {
    try Data("\(bytes)\n".utf8).write(to: url("ledger-v2"))
  }

  // MARK: Unfinished reservation records

  func testIncompleteReservationRecordIsAnOrphanRemovedWithItsAllocation() throws {
    let incomplete = ["", Self.owner, "\(Self.owner)\n", "\(Self.owner)\n1048576"]
    for content in incomplete {
      let reservation = try XCTUnwrap(try ledger().reserve(owner: Self.owner, bytes: Self.mib))
      // The reserve was cut while writing its record in place.
      try Data(content.utf8).write(to: url(metadata(reservation.token)))

      let reopened = try ledger()
      XCTAssertEqual(try reopened.reservedBytesTotal(), 0, "\(content.debugDescription)")
      XCTAssertFalse(exists(metadata(reservation.token)), "\(content.debugDescription): record removed")
      XCTAssertFalse(exists(allocation(reservation.token)), "\(content.debugDescription): allocation removed")
      let next = try XCTUnwrap(try reopened.reserve(owner: Self.owner, bytes: Self.mib), "\(content.debugDescription)")
      XCTAssertEqual(try reopened.reservation(token: next.token), next)
      XCTAssertTrue(try reopened.release(next))
    }
  }

  func testCompleteButMalformedReservationRecordStillFailsTheLedger() throws {
    for content in ["\(Self.owner)\nnot-a-size\n", "owner with spaces\n1048576\n", "\(Self.owner)\n1048576\nextra\n"] {
      try? FileManager.default.removeItem(at: root)
      root = CaptureFixture.temporaryDirectory("budget-ledger")
      let reservation = try XCTUnwrap(try ledger().reserve(owner: Self.owner, bytes: Self.mib))
      try Data(content.utf8).write(to: url(metadata(reservation.token)))

      XCTAssertThrowsError(try ledger().reservedBytesTotal(), content.debugDescription) { error in
        XCTAssertTrue("\(error)".contains("Corrupt storage reservation"), "\(error)")
      }
      XCTAssertTrue(exists(metadata(reservation.token)), "\(content.debugDescription): a corrupt record is kept")
      XCTAssertTrue(exists(allocation(reservation.token)), "\(content.debugDescription): its allocation is kept")
    }
  }

  // MARK: Capture quota

  func testCommittedPhotosCountAsCaptureCapacity() throws {
    try seedCommitted(4 * Self.gib)
    // Half of 6 GiB free plus 4 GiB held is 5 GiB; the free space alone
    // would have allowed only 3 GiB, below the photos already kept.
    let ledger = try ledger()
    let reservation = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 64 * Self.mib))
    XCTAssertEqual(try ledger.reservedBytesTotal(), 64 * Self.mib)
    XCTAssertTrue(try ledger.release(reservation))
  }

  func testFreeSpaceBoundsOnlyTheNewBytes() throws {
    free = 4 * Self.mib
    let ledger = try ledger(StorageBudgetPolicy(
      captureShareMinimumBytes: NativeCaptureReservationBounds.captureCoexistenceBytes, freeSpaceFloorBytes: Self.mib))
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: 3 * Self.mib + 1), "above free minus the floor")
    let first = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 3 * Self.mib))
    // The allocation now occupies disk: the free space already excludes it.
    free -= first.bytes
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: 1))
    free += 2 * Self.mib
    XCTAssertNotNil(try ledger.reserve(owner: Self.owner, bytes: 2 * Self.mib), "held reservations are not counted twice")
  }

  func testShrinkingFreeSpaceLowersTheQuotaAtEachCheck() throws {
    try seedCommitted(4 * Self.gib)
    let ledger = try ledger()
    let first = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: Self.mib))
    free = 2 * Self.gib
    // Capacity 2 GiB + 4 GiB + 1 MiB: its half is below the bytes held.
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: Self.mib))
    free = 6 * Self.gib
    XCTAssertNotNil(try ledger.reserve(owner: Self.owner, bytes: Self.mib), "the quota follows the free space again")
    XCTAssertTrue(try ledger.release(first))
  }

  func testSettlementIsNeverRefused() throws {
    try seedCommitted(4 * Self.gib)
    let ledger = try ledger()
    let reservation = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: Self.mib))
    free = 0
    XCTAssertNoThrow(try ledger.settle(reservation, actualBytes: Self.mib))
    XCTAssertEqual(try ledger.committedBytes(), 4 * Self.gib + Self.mib)
    XCTAssertEqual(try ledger.reservedBytesTotal(), 0)
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: 1), "new charges are refused without free space")
  }

  func testCommitChecksTheNewBytesAgainstTheFreeSpace() throws {
    free = 16 * Self.mib
    let ledger = try ledger()
    let first = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 2 * Self.mib))
    let second = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 2 * Self.mib))
    // Both allocations occupy disk; only the committed bytes need free space.
    free = 2 * Self.mib
    XCTAssertNoThrow(try ledger.commit(first, actualBytes: 2 * Self.mib))
    free = Self.mib
    XCTAssertThrowsError(try ledger.commit(second, actualBytes: 2 * Self.mib))
    XCTAssertEqual(try ledger.committedBytes(), 2 * Self.mib)
    XCTAssertEqual(try ledger.reservation(token: second.token), second)
  }
}
