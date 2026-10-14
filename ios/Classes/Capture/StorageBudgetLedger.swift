import Foundation

struct StorageBudgetPolicy {
  var quotaBytes: Int64
  var freeSpaceFloorBytes: Int64
}

struct StorageBudgetReservation: Hashable {
  var token: String
  var owner: String
  var bytes: Int64
}

/// The plain-reservation part of the Android `StorageBudgetCoordinatorV2`
/// with its on-disk format: `ledger-v2` holds the committed byte count,
/// `reservations-v2/<token>.reservation` names the owner and bytes and
/// `<token>.allocation` physically backs them. State is reloaded from disk
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
      let other = reservedBytes() - current.bytes
      let violatesFreeFloor = actualBytes > freeBytes() - policy.freeSpaceFloorBytes - other
      if actualBytes > policy.quotaBytes - committed || violatesFreeFloor {
        throw DurableStoreStateError("Committed bytes would violate the global storage budget")
      }
      committed += actualBytes
      try deleteReservation(current)
      try persistLedger()
    }
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
    reservations.removeAll()
    let names = try files.list(Self.reservationsDirectory)
    for name in names where name.hasSuffix(".reservation") && name.utf8.count == 64 + 12 {
      let token = String(name.dropLast(12))
      guard DurableStoreEncoding.isHash(token) else { continue }
      let lines = String(decoding: try files.readBytes(Self.reservationsDirectory + [name]), as: UTF8.self)
        .split(separator: "\n", omittingEmptySubsequences: false).map(String.init).filter { !$0.isEmpty }
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
    if committed > policy.quotaBytes || policy.freeSpaceFloorBytes > freeBytes() {
      throw DurableStoreStateError("Storage budget cannot open within its physical quota/floor")
    }
  }

  private func canCharge(_ bytes: Int64) -> Bool {
    let reserved = reservedBytes()
    let (sum, overflow) = committed.addingReportingOverflow(reserved)
    guard !overflow else { return false }
    let (charged, overflow2) = sum.addingReportingOverflow(bytes)
    guard !overflow2 else { return false }
    return charged <= policy.quotaBytes && charged <= freeBytes() - policy.freeSpaceFloorBytes
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

  /// `[A-Za-z0-9._:-]{1,160}`
  static func isOwner(_ owner: String) -> Bool {
    (1...160).contains(owner.utf8.count) && owner.utf8.allSatisfy {
      ($0 >= 0x41 && $0 <= 0x5a) || ($0 >= 0x61 && $0 <= 0x7a) || ($0 >= 0x30 && $0 <= 0x39)
        || $0 == 0x2e || $0 == 0x5f || $0 == 0x3a || $0 == 0x2d
    }
  }
}
