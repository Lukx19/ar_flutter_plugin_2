import XCTest
@testable import ar_flutter_plugin_2

/// The storage budget ledger on its own: an unfinished reservation write is
/// an orphan that recovery removes with its allocation, a complete but
/// malformed one stays corrupt; the capture quota counts the bytes the
/// ledger already holds as capacity and follows the free space at each
/// check, the free space must hold the live reservations' pending photo
/// writes plus the new allocation and its write, and settlement is never
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

  func testFreeSpaceCoversThePendingPhotoWrites() throws {
    free = 4 * Self.mib
    let ledger = try ledger(StorageBudgetPolicy(
      captureShareMinimumBytes: NativeCaptureReservationBounds.captureCoexistenceBytes, freeSpaceFloorBytes: Self.mib))
    // 3 MiB above the floor holds a 1.5 MiB allocation and its 1.5 MiB write.
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: 3 * Self.mib / 2 + 1), "allocation and write above free minus the floor")
    let first = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 3 * Self.mib / 2))
    // The allocation now occupies disk; its photo is still to be written.
    free -= first.bytes
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: 1), "the live reservation's write is pending")
    free += 7 * Self.mib / 2
    XCTAssertNotNil(try ledger.reserve(owner: Self.owner, bytes: 3 * Self.mib / 2),
                    "pending write plus a new allocation and write fit")
  }

  /// The capture share floor (128 MiB) with 60 MiB committed and 70 MiB
  /// free: the quota alone admits a 65.5 MiB photo reservation (125.5 MiB
  /// charged), but its allocation would leave about 4.5 MiB for the photo
  /// write, so it is refused instead of losing the photo after exposure.
  func testTheCaptureShareFloorRefusesAPhotoWhoseWriteWouldNotFit() throws {
    try seedCommitted(60 * Self.mib)
    free = 70 * Self.mib
    let ledger = try ledger()
    XCTAssertNil(try ledger.reserve(owner: Self.owner, bytes: 131 * Self.mib / 2), "65.5 MiB with 70 MiB free is refused")
    XCTAssertEqual(try ledger.reservedBytesTotal(), 0)
    free = 131 * Self.mib + Self.mib
    let admitted = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 131 * Self.mib / 2),
                                 "free space for the allocation and the write admits it")
    XCTAssertTrue(try ledger.release(admitted))
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

  // MARK: Crash-safe settlement

  private func faultyLedger(_ fault: @escaping DurableStoreFaultInjector) throws -> StorageBudgetLedger {
    try StorageBudgetLedger(
      files: RootedDurableFiles(backend: try PosixDurableFileBackend(root: root), fault: fault),
      policy: StorageBudgetPolicy(quotaBytes: 64 * Self.gib, freeSpaceFloorBytes: 0), freeBytes: { [unowned self] in self.free })
  }

  private func settlementMarkers() throws -> [String] {
    try FileManager.default.contentsOfDirectory(atPath: url("reservations-v2").path).filter { $0.contains(".settled") }
  }

  /// Fault points and visit counts of one settlement (or commit).
  private func closeVisits(commit: Bool) throws -> [DurableStoreFaultPoint: Int] {
    var visits: [DurableStoreFaultPoint: Int] = [:]
    var counting = false
    let ledger = try faultyLedger { point in if counting { visits[point, default: 0] += 1 } }
    try seedCommitted(5 * Self.mib)
    let reservation = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 2 * Self.mib))
    counting = true
    if commit { try ledger.commit(reservation, actualBytes: Self.mib) } else { try ledger.settle(reservation, actualBytes: Self.mib) }
    counting = false
    try FileManager.default.removeItem(at: root)
    root = CaptureFixture.temporaryDirectory("budget-ledger")
    return visits
  }

  /// A crash at every durable step of a settlement or commit: the next
  /// open charges the bytes exactly once, closes the reservation (its
  /// files are gone) and leaves no settlement marker.
  func testACrashAtEverySettlementStepChargesTheBytesExactlyOnce() throws {
    for commit in [false, true] {
      let visits = try closeVisits(commit: commit)
      XCTAssertGreaterThanOrEqual(visits[.deleteReclaim] ?? 0, 3, "reservation files and the marker")
      var cuts = 0
      for (point, count) in visits.sorted(by: { $0.key.rawValue < $1.key.rawValue }) {
        for visit in 1...count {
          let name = "\(commit ? "commit" : "settle") \(point.rawValue) visit \(visit)"
          let fault = NthVisitFault(point, nth: visit)
          fault.armed = false
          try seedCommitted(5 * Self.mib)
          let poisoned = try faultyLedger(fault.injector())
          let reservation = try XCTUnwrap(try poisoned.reserve(owner: Self.owner, bytes: 2 * Self.mib), name)
          fault.armed = true
          if commit {
            XCTAssertThrowsError(try poisoned.commit(reservation, actualBytes: Self.mib), name)
          } else {
            XCTAssertThrowsError(try poisoned.settle(reservation, actualBytes: Self.mib), name)
          }
          XCTAssertTrue(fault.injected, name)
          cuts += 1

          let reopened = try ledger()
          let charged = try reopened.committedBytes()
          if charged == 5 * Self.mib {
            // Cut before the settlement marker was durable: the reservation is
            // still live and its owner settles it again.
            XCTAssertEqual(try reopened.reservation(token: reservation.token), reservation, name)
            try reopened.settle(reservation, actualBytes: Self.mib)
          }
          XCTAssertEqual(try reopened.committedBytes(), 6 * Self.mib, "\(name): charged once")
          XCTAssertEqual(try reopened.reservedBytesTotal(), 0, name)
          XCTAssertNil(try reopened.reservation(token: reservation.token), name)
          XCTAssertFalse(exists(metadata(reservation.token)), name)
          XCTAssertFalse(exists(allocation(reservation.token)), name)
          XCTAssertEqual(try settlementMarkers(), [], name)
          // A second open is a no-op.
          XCTAssertEqual(try ledger().committedBytes(), 6 * Self.mib, name)
          try FileManager.default.removeItem(at: root)
          root = CaptureFixture.temporaryDirectory("budget-ledger")
        }
      }
      XCTAssertGreaterThanOrEqual(cuts, 6)
    }
  }

  func testAMarkerFoundBeforeTheLedgerMovedIsFinishedByTheNextOperation() throws {
    try seedCommitted(5 * Self.mib)
    let ledger = try ledger()
    let reservation = try XCTUnwrap(try ledger.reserve(owner: Self.owner, bytes: 2 * Self.mib))
    // The settlement was cut right after its marker became durable.
    try Data("SETTLED\n\(reservation.token)\n\(Self.mib)\n\(5 * Self.mib)\n".utf8)
      .write(to: url("reservations-v2/\(reservation.token).settled"))
    XCTAssertNil(try ledger.reservation(token: reservation.token), "the next operation finishes the settlement")
    XCTAssertEqual(try ledger.committedBytes(), 6 * Self.mib)
    XCTAssertFalse(exists(allocation(reservation.token)))
    XCTAssertEqual(try settlementMarkers(), [])
  }

  func testASettlementMarkerThatMatchesNeitherCountFailsTheLedger() throws {
    try seedCommitted(5 * Self.mib)
    let reservation = try XCTUnwrap(try ledger().reserve(owner: Self.owner, bytes: 2 * Self.mib))
    try Data("SETTLED\n\(reservation.token)\n\(Self.mib)\n\(9 * Self.mib)\n".utf8)
      .write(to: url("reservations-v2/\(reservation.token).settled"))
    XCTAssertThrowsError(try ledger().committedBytes())
    try Data("SETTLED\n\(reservation.token)\nmany\n".utf8).write(to: url("reservations-v2/\(reservation.token).settled"))
    XCTAssertThrowsError(try ledger().committedBytes(), "a malformed marker is corrupt")
  }
}
