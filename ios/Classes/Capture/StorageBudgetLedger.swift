import Foundation

struct StorageBudgetPolicy {
  enum Quota {
    /// A constant quota.
    case fixed(Int64)
    /// Half of the capture capacity, at least [minimumBytes]: the capacity is
    /// the free space plus the bytes the ledger already holds, since
    /// committed photos and physically backed reservations occupy disk and
    /// are no longer part of the free space.
    case captureShare(minimumBytes: Int64)
  }

  var quota: Quota
  var freeSpaceFloorBytes: Int64

  init(quotaBytes: Int64, freeSpaceFloorBytes: Int64) {
    quota = .fixed(quotaBytes)
    self.freeSpaceFloorBytes = freeSpaceFloorBytes
  }

  init(captureShareMinimumBytes: Int64, freeSpaceFloorBytes: Int64) {
    quota = .captureShare(minimumBytes: captureShareMinimumBytes)
    self.freeSpaceFloorBytes = freeSpaceFloorBytes
  }

  /// The quota at one check, for the current free space and ledger totals.
  func quotaBytes(freeBytes: Int64, committed: Int64, reserved: Int64) -> Int64 {
    switch quota {
    case .fixed(let bytes):
      return bytes
    case .captureShare(let minimumBytes):
      let (held, overflow) = committed.addingReportingOverflow(reserved)
      let (capacity, overflow2) = max(freeBytes, 0).addingReportingOverflow(held)
      return overflow || overflow2 ? Int64.max : max(capacity / 2, minimumBytes)
    }
  }
}

struct StorageBudgetReservation: Hashable {
  var token: String
  var owner: String
  var bytes: Int64
}

/// The plain-reservation part of the Android `StorageBudgetCoordinatorV2`
/// with its on-disk format: `ledger-v2` holds the committed byte count,
/// `reservations-v2/<token>.reservation` names the owner and bytes and
/// `<token>.allocation` physically backs them, and
/// `reclaims-v2/verified-<id>.reclaim` records an identity-bound reclaim of
/// committed bytes (a deleted session). State is reloaded from disk
/// before every operation, so a crash at any step leaves either the old or the
/// new authority. Candidate and pointer reservations (canonical surfaces) are
/// Android-only and not represented here; a reservation file in their format is
/// reported as corrupt.
final class StorageBudgetLedger {
  private let files: RootedDurableFiles
  private let policy: StorageBudgetPolicy
  private let freeBytes: () -> Int64
  private let lock = NSLock()
  private var committed: Int64 = 0
  private var reservations: [String: StorageBudgetReservation] = [:]
  private var tokenCounter: UInt64 = 0

  private static let ledger = ["ledger-v2"]
  private static let reservationsDirectory = ["reservations-v2"]
  private static let reclaimsDirectory = ["reclaims-v2"]

  init(files: RootedDurableFiles, policy: StorageBudgetPolicy, freeBytes: @escaping () -> Int64) throws {
    self.files = files
    self.policy = policy
    self.freeBytes = freeBytes
    try files.ensureDirectory(Self.reservationsDirectory)
    try files.ensureDirectory(Self.reclaimsDirectory)
  }

  func reserve(owner: String, bytes: Int64) throws -> StorageBudgetReservation? {
    try withAuthority {
      guard Self.isOwner(owner) else { throw DurableStoreStateError("Storage reservation owner is non-canonical") }
      guard bytes > 0, canCharge(bytes) else { return nil }
      tokenCounter &+= 1
      let token = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(
        "\(owner):\(bytes):\(reservations.count):\(committed):\(DispatchTime.now().uptimeNanoseconds):\(tokenCounter)"))
      let reservation = StorageBudgetReservation(token: token, owner: owner, bytes: bytes)
      let allocation = Self.allocationFile(token)
      let metadata = Self.metadataFile(token)
      do {
        try files.allocateExclusive(allocation, bytes: bytes)
        try files.writeExclusive(metadata, Array("\(owner)\n\(bytes)\n".utf8), .acceptedRecord)
        reservations[token] = reservation
        try persistLedger()
        return reservation
      } catch {
        if files.isFile(metadata) { try? files.delete(metadata, .deleteReclaim) }
        if files.isFile(allocation) { try? files.delete(allocation, .deleteReclaim) }
        throw error
      }
    }
  }

  func commit(_ reservation: StorageBudgetReservation, actualBytes: Int64) throws {
    try withAuthority {
      let current = try requireReservation(reservation)
      guard actualBytes >= 0, actualBytes <= current.bytes else { throw DurableStoreStateError("Commit exceeds reservation") }
      let free = freeBytes()
      let quota = policy.quotaBytes(freeBytes: free, committed: committed, reserved: reservedBytes())
      // Reservations are physically backed, so the free space already
      // excludes every other reservation.
      let violatesFreeFloor = actualBytes > free - policy.freeSpaceFloorBytes
      if actualBytes > quota - committed || violatesFreeFloor {
        throw DurableStoreStateError("Committed bytes would violate the global storage budget")
      }
      committed += actualBytes
      try deleteReservation(current)
      try persistLedger()
    }
  }

  /// Closes [reservation] into committed bytes after its data is durable.
  /// The reservation already counted against the quota when it was made, so
  /// no budget check applies: a quota that shrank since (it follows free
  /// space) never strands a durable commit. Throws for a stale token.
  func settle(_ reservation: StorageBudgetReservation, actualBytes: Int64) throws {
    try withAuthority {
      let current = try requireReservation(reservation)
      guard actualBytes >= 0, actualBytes <= current.bytes else { throw DurableStoreStateError("Commit exceeds reservation") }
      committed += actualBytes
      try deleteReservation(current)
      try persistLedger()
    }
  }

  /// Applies one identity-bound reclaim of committed bytes exactly once
  /// (Android `reclaimVerifiedOnce`, same `reclaims-v2/verified-<id>.reclaim`
  /// record): a PENDING marker holds the previous count before the ledger
  /// moves, DONE after; recovery finishes a PENDING one. A repeated call with
  /// a DONE marker is a no-op.
  func reclaimVerifiedOnce(reclaimId: String, bytes: Int64) throws {
    try withAuthority {
      guard DurableStoreEncoding.isHash(reclaimId), bytes > 0 else { throw DurableStoreStateError("Invalid verified reclaim") }
      let marker = Self.verifiedReclaimFile(reclaimId)
      if files.isFile(marker) {
        let lines = try Self.lines(files.readBytes(marker))
        guard lines.count == 4, lines[0] == "DONE", lines[1] == reclaimId, Int64(lines[2]) == bytes else {
          throw DurableStoreStateError("Verified reclaim conflicts with its marker")
        }
        return
      }
      guard bytes <= committed else { throw DurableStoreStateError("Verified reclaim exceeds committed bytes") }
      let previous = committed
      try files.atomicReplace(marker, Array("PENDING\n\(reclaimId)\n\(bytes)\n\(previous)\n".utf8), replacePoint: .pointerSlotReplace)
      committed = previous - bytes
      try persistLedger()
      try files.atomicReplace(marker, Array("DONE\n\(reclaimId)\n\(bytes)\n\(previous)\n".utf8), replacePoint: .pointerSlotReplace)
    }
  }

  /// True when a reclaim marker (PENDING or DONE) exists for [reclaimId];
  /// recovery has already finished a PENDING one when this returns.
  func hasVerifiedReclaim(_ reclaimId: String) throws -> Bool {
    try withAuthority { DurableStoreEncoding.isHash(reclaimId) && files.isFile(Self.verifiedReclaimFile(reclaimId)) }
  }

  @discardableResult
  func release(_ reservation: StorageBudgetReservation) throws -> Bool {
    try withAuthority {
      guard let current = reservations[reservation.token] else { return false }
      guard current == reservation else { throw DurableStoreStateError("Storage reservation token conflict") }
      try deleteReservation(current)
      try persistLedger()
      return true
    }
  }

  func reservation(token: String) throws -> StorageBudgetReservation? {
    try withAuthority { reservations[token] }
  }

  /// Every live reservation, for reconciliation against accepted records.
  func liveReservations() throws -> [StorageBudgetReservation] {
    try withAuthority { reservations.values.sorted { $0.token < $1.token } }
  }

  func committedBytes() throws -> Int64 { try withAuthority { committed } }
  func reservedBytesTotal() throws -> Int64 { try withAuthority { reservedBytes() } }

  // MARK: Private

  private func withAuthority<T>(_ body: () throws -> T) throws -> T {
    lock.lock(); defer { lock.unlock() }
    try recover()
    return try body()
  }

  /// Reloads the ledger and reservations; removes allocations without metadata
  /// and abandoned temporary files.
  private func recover() throws {
    if files.isFile(Self.ledger) {
      let text = String(decoding: try files.readBytes(Self.ledger), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
      guard let value = Int64(text), value >= 0 else { throw DurableStoreStateError("Corrupt storage budget ledger") }
      committed = value
    } else {
      committed = 0
    }
    try recoverVerifiedReclaims()
    reservations.removeAll()
    let names = try files.list(Self.reservationsDirectory)
    for name in names where name.hasSuffix(".reservation") && name.utf8.count == 64 + 12 {
      let token = String(name.dropLast(12))
      guard DurableStoreEncoding.isHash(token) else { continue }
      let metadata = try files.readBytes(Self.reservationsDirectory + [name])
      let lines = Self.lines(metadata)
      // The metadata is written in place after its allocation: an empty or
      // unterminated file, or one with fewer than two lines, is a reserve
      // that never returned. It is an orphan; it and its allocation go.
      guard metadata.last == UInt8(ascii: "\n"), lines.count >= 2 else {
        try files.delete(Self.reservationsDirectory + [name], .deleteReclaim)
        try files.delete(Self.allocationFile(token), .deleteReclaim)
        continue
      }
      guard lines.count == 2, Self.isOwner(lines[0]), let bytes = Int64(lines[1]), bytes > 0 else {
        throw DurableStoreStateError("Corrupt storage reservation")
      }
      let allocation = Self.allocationFile(token)
      guard files.isFile(allocation), try files.length(allocation) == bytes else {
        throw DurableStoreStateError("Reservation is not physically backed")
      }
      reservations[token] = StorageBudgetReservation(token: token, owner: lines[0], bytes: bytes)
    }
    for name in names {
      if name.hasSuffix(".allocation"), !reservations.keys.contains(String(name.dropLast(11))) {
        try files.delete(Self.reservationsDirectory + [name], .deleteReclaim)
      } else if name.hasPrefix("."), name.hasSuffix(".reservation.part") {
        try files.delete(Self.reservationsDirectory + [name], .deleteReclaim)
      }
    }
    // Committed bytes above the quota (the quota follows free space) or a
    // free space below the floor do not block opening: recovery, queries,
    // settlement and reclaims go on, and canCharge refuses new charges.
  }

  /// Finishes PENDING verified reclaims (Android `recoverLocked`) and drops
  /// their abandoned temporary files.
  private func recoverVerifiedReclaims() throws {
    let names = try files.list(Self.reclaimsDirectory)
    for name in names.sorted() where name.hasPrefix("verified-") && name.hasSuffix(".reclaim") {
      let id = String(name.dropFirst(9).dropLast(8))
      guard DurableStoreEncoding.isHash(id) else { continue }
      let lines = try Self.lines(files.readBytes(Self.reclaimsDirectory + [name]))
      guard lines.count == 4, lines[0] == "PENDING" || lines[0] == "DONE", lines[1] == id,
            let bytes = Int64(lines[2]), let previous = Int64(lines[3]), bytes > 0, previous >= bytes else {
        throw DurableStoreStateError("Corrupt verified reclaim")
      }
      guard lines[0] == "PENDING" else { continue }
      guard committed == previous || committed == previous - bytes else {
        throw DurableStoreStateError("Verified reclaim does not match the ledger")
      }
      if committed == previous {
        committed = previous - bytes
        try persistLedger()
      }
      try files.atomicReplace(Self.reclaimsDirectory + [name], Array("DONE\n\(id)\n\(bytes)\n\(previous)\n".utf8), replacePoint: .pointerSlotReplace)
    }
    for name in names where name.hasPrefix(".verified-") && name.hasSuffix(".reclaim.part") {
      try files.delete(Self.reclaimsDirectory + [name], .deleteReclaim)
    }
  }

  private static func lines(_ bytes: [UInt8]) -> [String] {
    String(decoding: bytes, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: false).map(String.init).filter { !$0.isEmpty }
  }

  private func canCharge(_ bytes: Int64) -> Bool {
    let reserved = reservedBytes()
    let (sum, overflow) = committed.addingReportingOverflow(reserved)
    guard !overflow else { return false }
    let (charged, overflow2) = sum.addingReportingOverflow(bytes)
    guard !overflow2 else { return false }
    // Committed photos and physically backed reservations already occupy
    // disk: the free space bounds only the new bytes.
    let free = freeBytes()
    return bytes <= free - policy.freeSpaceFloorBytes
      && charged <= policy.quotaBytes(freeBytes: free, committed: committed, reserved: reserved)
  }

  private func reservedBytes() -> Int64 { reservations.values.reduce(0) { $0 + $1.bytes } }

  private func requireReservation(_ value: StorageBudgetReservation) throws -> StorageBudgetReservation {
    guard let current = reservations[value.token] else { throw DurableStoreStateError("Storage reservation is stale or already closed") }
    guard current == value else { throw DurableStoreStateError("Storage reservation token conflict") }
    return current
  }

  private func deleteReservation(_ value: StorageBudgetReservation) throws {
    try files.delete(Self.metadataFile(value.token), .deleteReclaim)
    try files.delete(Self.allocationFile(value.token), .deleteReclaim)
    reservations[value.token] = nil
  }

  private func persistLedger() throws {
    try files.atomicReplace(Self.ledger, Array("\(committed)\n".utf8), replacePoint: .pointerSlotReplace)
  }

  private static func metadataFile(_ token: String) -> [String] { reservationsDirectory + ["\(token).reservation"] }
  private static func allocationFile(_ token: String) -> [String] { reservationsDirectory + ["\(token).allocation"] }
  private static func verifiedReclaimFile(_ id: String) -> [String] { reclaimsDirectory + ["verified-\(id).reclaim"] }

  /// `[A-Za-z0-9._:-]{1,160}`
  static func isOwner(_ owner: String) -> Bool {
    (1...160).contains(owner.utf8.count) && owner.utf8.allSatisfy {
      ($0 >= 0x41 && $0 <= 0x5a) || ($0 >= 0x61 && $0 <= 0x7a) || ($0 >= 0x30 && $0 <= 0x39)
        || $0 == 0x2e || $0 == 0x5f || $0 == 0x3a || $0 == 0x2d
    }
  }
}
