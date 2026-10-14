import Foundation

/// The native durable photo store, byte compatible with the Android
/// `DurableSessionStoreV2` (layout `capture-v2-native/store`, root schema 5).
///
/// Layout under the store root:
/// - `sessions/<sha(sessionId)>/attempts/<sha(commitId)>/accepted.properties`
///   written before the exposure, `receipt.properties` at the terminal and a
///   transient `staging/` folder;
/// - `sessions/<s>/assets/<sha(attemptId)>/jpeg.<sha(jpeg)>.blob`;
/// - `sessions/<s>/objects/<rootHash>.root`, one immutable root per commit
///   chained by `previous`/`secondPrevious`;
/// - `sessions/<s>/root-A.ptr`, `root-B.ptr`: the alternating selected pointer;
/// - `recovery-index.properties`: the last three accepted attempts for the
///   bounded startup recovery.
///
/// Every record is written once, synced and its directory synced; the only
/// mutable authority is the pointer slot written by rename. The receipt is
/// durable before the pointer moves and independently validates it. Not
/// thread safe: one serial owner calls it.
final class DurableCaptureStore {
  struct RecoveryProjection: Hashable {
    var attemptId: String
    var kind: CaptureTerminalKind?
    var captureId: String?
    var captureRevision: Int64?
    var manifestId: String?
    var reason: String
    var recoveryContext: NativeCaptureRecoveryContext?
    var jpegSizeBytes: Int64?
    var dngSizeBytes: Int64?
  }

  static let bindingFullRequest = "full-request-v1"
  static let bindingMetadataOnly = "metadata-only-v1"
  static let bindingRecovered = "recovered-proven-absent-v1"
  static let bindingTemplate = "post-output-template-v2"
  static let recoveryIndexCapacity = 3

  private let files: RootedDurableFiles
  private let budget: StorageBudgetLedger
  private let sessionRoot = ["sessions"]
  private let recoveryIndex = ["recovery-index.properties"]
  private var uncertainRootHashes = Set<String>()

  init(files: RootedDurableFiles, budget: StorageBudgetLedger) throws {
    self.files = files
    self.budget = budget
    try files.ensureDirectory(sessionRoot)
  }

  // MARK: Acceptance before exposure

  /// Native admission: replays an exact terminal, otherwise records the
  /// accepted attempt and its physically backed reservation.
  func acceptCaptureBeforeExposure(_ request: CaptureCommitRequest) throws -> CaptureReceipt {
    let location = try self.location(request.accepted.identity)
    if tombstone(location) { throw DurableStoreConflict("Session is tombstoned") }
    if let prior = try replayReceiptBeforeExposure(location, request) { return prior }
    return try acceptBeforeExposure(request.accepted, recoveryContext: request.recoveryContext)
  }

  func acceptBeforeExposure(_ attempt: CaptureAcceptedAttempt, recoveryContext: NativeCaptureRecoveryContext? = nil) throws -> CaptureReceipt {
    let location = try self.location(attempt.identity)
    if tombstone(location) { throw DurableStoreConflict("Session is tombstoned") }
    let acceptedPath = try acceptedFile(location, attempt.identity)
    if let prior = try receipt(location, attempt.identity) {
      let accepted = files.isFile(acceptedPath) ? try readProperties(acceptedPath) : nil
      if accepted?["acceptedHash"] == DurableStoreEncoding.acceptedHash(attempt) { return prior }
      throw DurableStoreConflict("Changed replay conflicts with durable identity")
    }
    if files.isFile(acceptedPath) {
      if try readProperties(acceptedPath)["acceptedHash"] == DurableStoreEncoding.acceptedHash(attempt) {
        return acceptedReceipt(attempt)
      }
      throw DurableStoreConflict("Changed accepted attempt conflicts")
    }
    guard let reservation = try budget.reserve(
      owner: DurableStoreEncoding.reservationOwner(attempt.identity), bytes: attempt.reservation.totalStoreLiability)
    else { throw DurableStoreStateError("Storage reservation refused before exposure") }
    do {
      var data: [String: String] = [
        "acceptedHash": DurableStoreEncoding.acceptedHash(attempt),
        "reservationToken": reservation.token,
        "reservationBytes": String(reservation.bytes),
        "attemptId": attempt.identity.attemptId,
        "commitId": attempt.identity.commitId,
        "session": attempt.identity.lifecycleCut.sessionId,
        "acceptedIdentityVersion": "1",
        "acceptedIdentity": DurableStoreEncoding.exactIdentityBase64(attempt.identity),
      ]
      if let context = recoveryContext { Self.putRecoveryContext(context, into: &data) }
      guard Int64(DurableStoreEncoding.propertiesBytes(data).count) <= NativeCaptureReservationBounds.acceptedRecordBytes else {
        throw DurableStoreStateError("Accepted metadata exceeds reserved record bound")
      }
      try files.at(.acceptedRecord)
      try recordRecoveryCandidate(session: location.last!, attempt: acceptedPath[acceptedPath.count - 2])
      try writePropertiesExclusive(acceptedPath, data)
      return acceptedReceipt(attempt)
    } catch {
      _ = try? budget.release(reservation)
      throw error
    }
  }

  /// Pre-exposure fence of a template admission: the durable terminal or
  /// accepted receipt of the same identity, nil when none exists.
  func replayTemplateFenceBeforeExposure(_ template: CapturePostOutputTemplate) throws -> CaptureReceipt? {
    let identity = template.accepted.identity
    let location = try self.location(identity)
    if tombstone(location) { throw DurableStoreConflict("Session is tombstoned") }
    let receiptPath = try receiptFile(location, identity)
    if files.isFile(receiptPath) {
      if try readProperties(receiptPath)["templateHash"] != DurableStoreEncoding.templateHash(template) {
        throw DurableStoreConflict("Changed accepted template conflicts with durable identity")
      }
      guard let prior = try receipt(location, identity) else {
        throw DurableStoreConflict("Template receipt is not selected-root authoritative")
      }
      return prior
    }
    let acceptedPath = try acceptedFile(location, identity)
    if files.isFile(acceptedPath) {
      if try readProperties(acceptedPath)["acceptedHash"] == DurableStoreEncoding.acceptedHash(template.accepted) {
        return acceptedReceipt(template.accepted)
      }
      throw DurableStoreConflict("Changed template conflicts with durable accepted identity")
    }
    return nil
  }

  // MARK: Commit

  /// The direct exposure path: the accepted identity and reservation exist;
  /// each component is streamed once into staging while its descriptor is
  /// computed, then moved, rooted, receipted and selected.
  func commitStreamedFinalized(
    _ template: CapturePostOutputTemplate, exposureTimestampNanoseconds: Int64,
    components: [(CaptureComponentKind, [UInt8])]
  ) throws -> CaptureReceipt {
    let identity = template.accepted.identity
    let location = try self.location(identity)
    if tombstone(location) { throw DurableStoreConflict("Session is tombstoned") }
    let priorReceipt = try receipt(location, identity)
    if let prior = priorReceipt { return prior }
    let accepted = try readProperties(try acceptedFile(location, identity))
    guard accepted["acceptedHash"] == DurableStoreEncoding.acceptedHash(template.accepted) else {
      throw DurableStoreStateError("Attempt was not durably accepted")
    }
    let kinds = components.map { $0.0 }
    guard Set(kinds) == template.accepted.profile.requiredComponents, kinds.count == Set(kinds).count else {
      throw DurableStoreStateError("Stream component set is incomplete or duplicated")
    }
    let reservation = try accepted["reservationToken"].flatMap { try budget.reservation(token: $0) }
    guard let reservation = reservation else { throw DurableStoreStateError("Accepted attempt has no live reservation") }
    let staging = try attemptDirectory(location, identity) + ["staging"]
    try files.ensureDirectory(staging)
    let receiptPath = try receiptFile(location, identity)
    var actualBytes: Int64 = 0
    do {
      var staged: [(CaptureComponentKind, [String], DurableComponentDescriptor)] = []
      for (kind, bytes) in components.sorted(by: { $0.0 < $1.0 }) {
        let file = staging + ["\(kind.storedName.lowercased()).part"]
        try files.at(.componentOpen)
        let (length, digest) = try files.streamExclusive(file, bytes)
        try files.at(.lengthCheck)
        actualBytes += length
        let hex = DurableStoreEncoding.hex(digest)
        staged.append((kind, file, DurableComponentDescriptor(kind: kind, byteLength: length, sha256: digest, durableObjectId: hex)))
      }
      guard actualBytes <= reservation.bytes, actualBytes <= template.accepted.profile.maximumComponentBytes else {
        throw DurableStoreStateError("Component bytes exceed accepted reservation")
      }
      let request = CaptureCommitRequest(
        accepted: template.accepted, components: staged.map { $0.2 },
        exposureTimestampNanoseconds: exposureTimestampNanoseconds, poseRecordHash: template.poseRecordHash,
        cameraModelHash: template.cameraModelHash, validationRecordHash: template.validationRecordHash,
        ledgerRecordHash: template.ledgerRecordHash, recoveryContext: nil)
      let requestHash = DurableStoreEncoding.requestHash(request)
      let captureId = DurableStoreEncoding.safeName(identity.attemptId)
      let assets = location + ["assets", captureId]
      try files.ensureDirectory(assets)
      for (kind, file, descriptor) in staged {
        try moveImmutable(file, assets + ["\(kind.storedName.lowercased()).\(descriptor.durableObjectId).blob"], descriptor)
      }
      let prior = try selectedRoot(location)
      let revision = (prior?.revision ?? 0) + 1
      let rootBytes = Self.rootRecord(requestHash: requestHash, revision: revision, components: staged.map { $0.2 }, prior: prior)
      let rootHash = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(rootBytes))
      try files.at(.rootWrite)
      try files.writeImmutable(location + ["objects", "\(rootHash).root"], rootBytes, .rootWrite)
      try files.at(.rootFileSync)
      let jpegSize = request.components.first { $0.kind == .jpeg }?.byteLength
      let dngSize = request.components.first { $0.kind == .dng }?.byteLength
      let terminal = CaptureTerminal(
        kind: .committedPicture, identity: identity, canonicalTerminalHash: requestHash, reason: "committed",
        captureId: captureId, captureRevision: revision, manifestId: rootHash, jpegSizeBytes: jpegSize, dngSizeBytes: dngSize)
      let committed = CaptureReceipt(
        identity: identity, phase: .committedPicture, requestHash: requestHash,
        receiptHash: DurableStoreEncoding.receiptHash(requestHash: requestHash, rootHash: rootHash), durable: true, terminal: terminal)
      try files.at(.receiptWrite)
      var properties = Self.receiptProperties(committed, rootHash: rootHash)
      properties["templateHash"] = DurableStoreEncoding.templateHash(template)
      try writePropertiesExclusive(receiptPath, properties)
      try files.at(.receiptFileSync)
      try publishPointer(location, CaptureRootPointer(revision: revision, hash: rootHash, commit: identity.commitId))
      try budget.commit(reservation, actualBytes: actualBytes)
      try files.deleteTree(staging, .deleteReclaim)
      return committed
    } catch {
      // Once receipt persistence began, the pointer cut is unknown: keep the
      // staging and the liability for restart or query.
      if !files.isFile(receiptPath) { try? files.deleteTree(staging, .deleteReclaim) }
      throw error
    }
  }

  /// Re-publishes an already received attempt after an unknown pointer cut.
  func rebaseSameAttempt(_ request: CaptureCommitRequest) throws -> CaptureReceipt {
    let identity = request.accepted.identity
    let location = try self.location(identity)
    let file = try receiptFile(location, identity)
    guard files.isFile(file) else { throw DurableStoreStateError("No durable receipt to rebase") }
    let values = try readProperties(file)
    guard values["kind"] == CaptureTerminalKind.committedPicture.storedName,
          values["requestHash"] == DurableStoreEncoding.requestHash(request) else {
      throw DurableStoreStateError("Changed attempt cannot rebase")
    }
    guard let rootHash = values["rootHash"], let revision = values["revision"].flatMap(Int64.init),
          try validRootHash(location, rootHash, revision, 0) else {
      throw DurableStoreStateError("Receipt root is incomplete")
    }
    let selected = try selectedRoot(location)
    if !(try retainedRootHashes(location)).contains(rootHash) {
      guard revision == (selected?.revision ?? 0) + 1, try rootPrevious(location, rootHash) == selected?.hash else {
        throw DurableStoreStateError("Unknown receipt cannot rebase over a different root cut")
      }
      try publishPointer(location, CaptureRootPointer(revision: revision, hash: rootHash, commit: identity.commitId))
    }
    let accepted = try readProperties(try acceptedFile(location, identity))
    if let token = accepted["reservationToken"], let reservation = try budget.reservation(token: token) {
      try budget.commit(reservation, actualBytes: request.components.reduce(0) { $0 + $1.byteLength })
    }
    try files.deleteTree(try attemptDirectory(location, identity) + ["staging"], .deleteReclaim)
    guard let result = try receipt(location, identity) else { throw DurableStoreStateError("Rebased receipt is missing") }
    return result
  }

  func queryReceipt(_ identity: CaptureAttemptIdentity) throws -> CaptureReceipt? {
    let location = try self.location(identity)
    return tombstone(location) ? nil : try receipt(location, identity)
  }

  // MARK: Abandonment

  func abandonTemplate(_ template: CapturePostOutputTemplate, terminal: CaptureTerminal) throws -> CaptureReceipt {
    guard template.accepted.identity == terminal.identity else { throw DurableStoreStateError("Abandonment identity mismatch") }
    return try abandon(terminal, fullRequestHash: nil, binding: Self.bindingTemplate,
                       templateHash: DurableStoreEncoding.templateHash(template))
  }

  /// Metadata-only abandonment of an attempt identity.
  func abandon(_ terminal: CaptureTerminal) throws -> CaptureReceipt {
    try abandon(terminal, fullRequestHash: nil, binding: Self.bindingMetadataOnly, templateHash: nil)
  }

  private func abandon(_ terminal: CaptureTerminal, fullRequestHash: String?, binding: String, templateHash: String?) throws -> CaptureReceipt {
    guard terminal.kind == .abandonedAttempt else { throw DurableStoreStateError("Abandonment requires an abandoned terminal") }
    let location = try self.location(terminal.identity)
    if let prior = try receipt(location, terminal.identity) { return prior }
    let acceptedPath = try acceptedFile(location, terminal.identity)
    let accepted = files.isFile(acceptedPath) ? try readProperties(acceptedPath) : nil
    try files.at(.abandonmentCleanup)
    try files.deleteTree(try attemptDirectory(location, terminal.identity) + ["staging"], .abandonmentCleanup)
    try files.deleteTree(assetDirectory(location, terminal.identity), .abandonmentCleanup)
    let boundHash = fullRequestHash ?? terminal.canonicalTerminalHash
    let value = CaptureReceipt(
      identity: terminal.identity, phase: .abandonedAttempt, requestHash: boundHash,
      receiptHash: DurableStoreEncoding.receiptHash(requestHash: boundHash, rootHash: "abandoned"), durable: true, terminal: terminal)
    try files.at(.abandonmentRecord)
    var properties = Self.receiptProperties(value, rootHash: "abandoned")
    properties["requestBinding"] = binding
    if let hash = fullRequestHash { properties["fullRequestHash"] = hash }
    if let hash = templateHash { properties["templateHash"] = hash }
    try writePropertiesExclusive(try receiptFile(location, terminal.identity), properties)
    if let token = accepted?["reservationToken"], let reservation = try budget.reservation(token: token) {
      try budget.release(reservation)
    }
    return value
  }

  /// Tombstone-first whole-session deletion fence; it wins over later callbacks.
  func tombstoneSession(_ sessionId: String) throws {
    let directory = sessionRoot + [DurableStoreEncoding.safeName(sessionId)]
    try files.ensureDirectory(directory)
    try files.at(.tombstoneRecord)
    try files.writeExclusive(directory + ["tombstone"], Array("deleted\n".utf8), .tombstoneRecord)
    try files.at(.tombstoneDirectorySync)
    for path in try files.walk(directory) where path.last == "accepted.properties" {
      if let token = try readProperties(path)["reservationToken"], let reservation = try budget.reservation(token: token) {
        try budget.release(reservation)
      }
    }
  }

  // MARK: Recovery

  /// Reconciles every accepted attempt: an attempt without a receipt is
  /// proven absent (abandoned, staging and assets removed, reservation
  /// released); a committed receipt in the retained root graph releases its
  /// remaining staging and charges its bytes.
  func recover() throws {
    try files.at(.recovery)
    for session in try files.list(sessionRoot) {
      let sessionPath = sessionRoot + [session]
      guard files.isDirectory(sessionPath) else { continue }
      for path in try files.walk(sessionPath) where path.last == "accepted.properties" {
        try repairAcceptedCandidate(session: sessionPath, accepted: path)
      }
    }
  }

  /// Bounded startup projection after repair: at most [limit] (1 or 2) of the
  /// indexed newest accepted attempts, each as its durable outcome.
  func recoverAndProject(limit: Int = 2, shouldContinue: () -> Bool = { true }) throws -> [RecoveryProjection] {
    precondition((1...2).contains(limit))
    guard files.isFile(recoveryIndex), shouldContinue() else { return [] }
    let index = try readProperties(recoveryIndex)
    let count = min(max(Int(index["count"] ?? "") ?? 0, 0), Self.recoveryIndexCapacity)
    var result: [RecoveryProjection] = []
    var validEntries: [(String, String)] = []
    var examined = 0
    for slot in 0..<count {
      if !shouldContinue() || result.count == limit { break }
      examined += 1
      guard let sessionHash = index["entry.\(slot).session"], let attemptHash = index["entry.\(slot).attempt"],
            DurableStoreEncoding.isHash(sessionHash), DurableStoreEncoding.isHash(attemptHash) else { continue }
      let session = sessionRoot + [sessionHash]
      let accepted = session + ["attempts", attemptHash, "accepted.properties"]
      guard files.isFile(accepted) else { continue }
      validEntries.append((sessionHash, attemptHash))
      try repairAcceptedCandidate(session: session, accepted: accepted)
      let acceptedValues = try readProperties(accepted)
      guard let attemptId = acceptedValues["attemptId"] else {
        throw DurableStoreConflict("Recovered acceptance has no attempt identity")
      }
      let context = try Self.recoveryContext(acceptedValues)
      let receiptPath = Array(accepted.dropLast()) + ["receipt.properties"]
      guard files.isFile(receiptPath) else {
        result.append(RecoveryProjection(attemptId: attemptId, kind: nil, reason: "recovered-accepted-unknown", recoveryContext: context))
        continue
      }
      let value = try readProperties(receiptPath)
      if value["kind"] == CaptureTerminalKind.committedPicture.storedName,
         let rootHash = value["rootHash"], try retainedRootHashes(session).contains(rootHash) {
        if let identity = try Self.exactIdentity(acceptedValues),
           DurableStoreEncoding.safeName(identity.lifecycleCut.sessionId) != sessionHash
            || DurableStoreEncoding.safeName(identity.commitId) != attemptHash {
          throw DurableStoreConflict("Recovered exact identity conflicts with durable location")
        }
        let verified = try validateCommittedReceipt(session, value)
        result.append(RecoveryProjection(
          attemptId: attemptId, kind: .committedPicture, captureId: verified.captureId, captureRevision: verified.revision,
          manifestId: rootHash, reason: "recovered-committed", recoveryContext: context,
          jpegSizeBytes: verified.jpegSizeBytes, dngSizeBytes: verified.dngSizeBytes))
      } else if value["kind"] == CaptureTerminalKind.abandonedAttempt.storedName {
        result.append(RecoveryProjection(
          attemptId: attemptId, kind: .abandonedAttempt, reason: value["reason"] ?? "recovered-abandoned", recoveryContext: context))
      } else {
        result.append(RecoveryProjection(attemptId: attemptId, kind: nil, reason: "recovered-receipt-unknown", recoveryContext: context))
      }
    }
    if examined == count && validEntries.count != count && shouldContinue() {
      try writeRecoveryIndex(validEntries)
    }
    return result
  }

  private func repairAcceptedCandidate(session: [String], accepted: [String]) throws {
    let attempt = Array(accepted.dropLast())
    let receipt = attempt + ["receipt.properties"]
    let acceptedValues = try readProperties(accepted)
    if !files.isFile(receipt) {
      let acceptedHash = acceptedValues["acceptedHash"] ?? ""
      let abandoned: [String: String] = [
        "kind": CaptureTerminalKind.abandonedAttempt.storedName,
        "requestHash": acceptedHash,
        "receiptHash": DurableStoreEncoding.receiptHash(requestHash: acceptedHash, rootHash: "abandoned"),
        "rootHash": "abandoned",
        "reason": "recovered-proven-absent",
        "requestBinding": Self.bindingRecovered,
      ]
      try files.writeExclusive(receipt, DurableStoreEncoding.propertiesBytes(abandoned), .abandonmentRecord)
      try files.deleteTree(attempt + ["staging"], .abandonmentCleanup)
      if let attemptId = acceptedValues["attemptId"] {
        try files.deleteTree(session + ["assets", DurableStoreEncoding.safeName(attemptId)], .abandonmentCleanup)
      }
      if let token = acceptedValues["reservationToken"], let reservation = try budget.reservation(token: token) {
        try budget.release(reservation)
      }
      return
    }
    let receiptValues = try readProperties(receipt)
    if receiptValues["kind"] == CaptureTerminalKind.committedPicture.storedName,
       let rootHash = receiptValues["rootHash"], try retainedRootHashes(session).contains(rootHash) {
      if let token = acceptedValues["reservationToken"], let reservation = try budget.reservation(token: token) {
        try budget.commit(reservation, actualBytes: try committedComponentBytes(session, rootHash))
      }
      try files.deleteTree(attempt + ["staging"], .deleteReclaim)
    }
    // A committed receipt outside the retained graph stays unknown: staging
    // and liability remain for an exact query or rebase.
  }

  private func recordRecoveryCandidate(session: String, attempt: String) throws {
    var entries: [(String, String)] = [(session, attempt)]
    if files.isFile(recoveryIndex) {
      let existing = try readProperties(recoveryIndex)
      let count = min(max(Int(existing["count"] ?? "") ?? 0, 0), Self.recoveryIndexCapacity)
      for slot in 0..<count {
        guard let s = existing["entry.\(slot).session"], let a = existing["entry.\(slot).attempt"] else { continue }
        if !(s == session && a == attempt) { entries.append((s, a)) }
      }
    }
    try writeRecoveryIndex(Array(entries.prefix(Self.recoveryIndexCapacity)))
  }

  private func writeRecoveryIndex(_ entries: [(String, String)]) throws {
    var values: [String: String] = ["schema": "bounded-recovery-index-v1", "count": String(entries.count)]
    for (slot, entry) in entries.enumerated() {
      values["entry.\(slot).session"] = entry.0
      values["entry.\(slot).attempt"] = entry.1
    }
    try files.atomicReplace(recoveryIndex, DurableStoreEncoding.propertiesBytes(values), replacePoint: nil, directorySyncPoint: nil)
  }

  // MARK: Receipts and roots

  private struct VerifiedCommittedReceipt {
    var captureId: String?
    var revision: Int64?
    var jpegSizeBytes: Int64?
    var dngSizeBytes: Int64?
  }

  private func receipt(_ location: [String], _ identity: CaptureAttemptIdentity) throws -> CaptureReceipt? {
    let file = try receiptFile(location, identity)
    guard files.isFile(file) else { return nil }
    let value = try readProperties(file)
    guard let kind = value["kind"] else { return nil }
    let terminal: CaptureTerminal
    if kind == CaptureTerminalKind.committedPicture.storedName {
      guard let rootHash = value["rootHash"], try retainedRootHashes(location).contains(rootHash) else { return nil }
      let verified = try validateCommittedReceipt(location, value)
      terminal = CaptureTerminal(
        kind: .committedPicture, identity: identity, canonicalTerminalHash: value["requestHash"] ?? "", reason: "committed",
        captureId: verified.captureId, captureRevision: verified.revision, manifestId: rootHash,
        jpegSizeBytes: verified.jpegSizeBytes, dngSizeBytes: verified.dngSizeBytes)
    } else {
      terminal = CaptureTerminal(
        kind: .abandonedAttempt, identity: identity, canonicalTerminalHash: value["requestHash"] ?? "",
        reason: value["reason"] ?? "abandoned")
    }
    return CaptureReceipt(
      identity: identity, phase: terminal.kind == .committedPicture ? .committedPicture : .abandonedAttempt,
      requestHash: value["requestHash"] ?? "", receiptHash: value["receiptHash"] ?? "", durable: true, terminal: terminal)
  }

  /// Schema-5 receipt fields agree with the hash-verified root.
  private func validateCommittedReceipt(_ location: [String], _ receipt: [String: String]) throws -> VerifiedCommittedReceipt {
    guard let rootHash = receipt["rootHash"] else { throw DurableStoreConflict("Committed receipt has no root") }
    guard let metadata = try readRootMetadata(location, rootHash) else { throw DurableStoreConflict("Committed root is invalid") }
    if receipt.keys.contains(where: { $0.hasPrefix("quality") }) {
      throw DurableStoreConflict("Legacy receipt cannot carry assessed quality")
    }
    func size(_ property: String, _ kind: CaptureComponentKind) throws -> Int64? {
      if let text = receipt[property] {
        guard let size = Int64(text), size > 0 else { throw DurableStoreConflict("Committed receipt has malformed \(property)") }
        return size
      }
      return metadata.components.first { $0.kind == kind.storedName }?.length
    }
    return VerifiedCommittedReceipt(
      captureId: receipt["captureId"], revision: receipt["revision"].flatMap(Int64.init),
      jpegSizeBytes: try size("jpegSizeBytes", .jpeg), dngSizeBytes: try size("dngSizeBytes", .dng))
  }

  private struct SelectedPointer {
    var revision: Int64
    var hash: String
    var slot: String
    var previousHash: String?
  }

  private func selectedRoot(_ location: [String]) throws -> SelectedPointer? {
    var selected: SelectedPointer?
    for slot in ["A", "B"] {
      let file = location + ["root-\(slot).ptr"]
      guard files.isFile(file), let pointer = CaptureRootRecords.parsePointer(try files.readBytes(file)) else { continue }
      if uncertainRootHashes.contains(pointer.hash) { continue }
      let candidate = SelectedPointer(revision: pointer.revision, hash: pointer.hash, slot: slot,
                                      previousHash: try rootPrevious(location, pointer.hash))
      guard try validRootHash(location, candidate.hash, candidate.revision, 0) else { continue }
      if let prior = selected, prior.revision == candidate.revision, prior.hash != candidate.hash {
        throw DurableStoreConflict("Schema-5 root fork")
      }
      if selected == nil || candidate.revision > selected!.revision { selected = candidate }
    }
    return selected
  }

  private func readRootMetadata(_ location: [String], _ hash: String) throws -> CaptureRootMetadata? {
    guard DurableStoreEncoding.isHash(hash) else { return nil }
    let file = location + ["objects", "\(hash).root"]
    guard files.isFile(file) else { return nil }
    return CaptureRootRecords.parseRoot(expectedHash: hash, bytes: try files.readBytes(file))
  }

  private func rootPrevious(_ location: [String], _ hash: String) throws -> String? {
    guard let previous = try readRootMetadata(location, hash)?.previous, previous != "-" else { return nil }
    return previous
  }

  private func validRootHash(_ location: [String], _ hash: String, _ revision: Int64, _ depth: Int) throws -> Bool {
    if depth > 2 { return false }
    guard let values = try readRootMetadata(location, hash), values.revision == revision,
          DurableStoreEncoding.isHash(values.request) else { return false }
    if depth == 2 { return true }
    if revision == 1 { return values.previous == "-" && values.secondPrevious == "-" }
    guard let previous = values.previous, previous != "-", try validRootHash(location, previous, revision - 1, depth + 1) else {
      return false
    }
    if depth == 0 && revision >= 3 {
      let expected = try rootPrevious(location, previous)
      if values.secondPrevious != expected { return false }
    }
    return true
  }

  private func publishPointer(_ location: [String], _ pointer: CaptureRootPointer) throws {
    let old = try selectedRoot(location)
    let target = location + [old?.slot == "A" ? "root-B.ptr" : "root-A.ptr"]
    do {
      try files.atomicReplace(target, Array("\(pointer.revision)\n\(pointer.hash)\n\(pointer.commit)\n".utf8), replacePoint: .pointerSlotReplace)
      uncertainRootHashes.remove(pointer.hash)
    } catch let error as PointerDirectorySyncUnknown {
      uncertainRootHashes.insert(pointer.hash)
      throw error
    }
  }

  private func retainedRootHashes(_ location: [String]) throws -> Set<String> {
    guard let selected = try selectedRoot(location) else { return [] }
    var out = Set<String>()
    var hash: String? = selected.hash
    for _ in 0..<3 {
      guard let current = hash else { break }
      out.insert(current)
      hash = try rootPrevious(location, current)
    }
    return out
  }

  private func committedComponentBytes(_ location: [String], _ rootHash: String) throws -> Int64 {
    guard let metadata = try readRootMetadata(location, rootHash) else { throw DurableStoreConflict("Committed root is invalid") }
    return metadata.components.reduce(0) { $0 + $1.length }
  }

  private func moveImmutable(_ from: [String], _ target: [String], _ descriptor: DurableComponentDescriptor) throws {
    if files.isFile(target) {
      let (length, digest) = try files.digestAndLength(target)
      guard length == descriptor.byteLength, digest == descriptor.sha256 else { throw DurableStoreStateError("Immutable asset conflict") }
      try files.delete(from, .deleteReclaim)
      return
    }
    try files.moveAtomic(from, target)
  }

  private func replayReceiptBeforeExposure(_ location: [String], _ request: CaptureCommitRequest) throws -> CaptureReceipt? {
    guard let prior = try receipt(location, request.accepted.identity) else { return nil }
    let properties = try readProperties(try receiptFile(location, request.accepted.identity))
    if prior.terminal?.kind == .abandonedAttempt {
      guard properties["requestBinding"] == Self.bindingFullRequest else {
        throw DurableStoreConflict("Metadata-only abandonment cannot prove exact request replay")
      }
      if properties["fullRequestHash"] == DurableStoreEncoding.requestHash(request) { return prior }
      throw DurableStoreConflict("Changed replay conflicts with durable abandoned identity")
    }
    if prior.requestHash == DurableStoreEncoding.requestHash(request) { return prior }
    throw DurableStoreConflict("Changed replay conflicts with durable terminal identity")
  }

  // MARK: Paths and records

  private func location(_ identity: CaptureAttemptIdentity) throws -> [String] {
    let path = sessionRoot + [DurableStoreEncoding.safeName(identity.lifecycleCut.sessionId)]
    try files.ensureDirectory(path)
    return path
  }

  private func attemptDirectory(_ location: [String], _ identity: CaptureAttemptIdentity) throws -> [String] {
    let path = location + ["attempts", DurableStoreEncoding.safeName(identity.commitId)]
    try files.ensureDirectory(path)
    return path
  }

  private func tombstone(_ location: [String]) -> Bool { files.isFile(location + ["tombstone"]) }
  private func acceptedFile(_ location: [String], _ identity: CaptureAttemptIdentity) throws -> [String] {
    try attemptDirectory(location, identity) + ["accepted.properties"]
  }
  private func receiptFile(_ location: [String], _ identity: CaptureAttemptIdentity) throws -> [String] {
    try attemptDirectory(location, identity) + ["receipt.properties"]
  }
  private func assetDirectory(_ location: [String], _ identity: CaptureAttemptIdentity) -> [String] {
    location + ["assets", DurableStoreEncoding.safeName(identity.attemptId)]
  }

  private func acceptedReceipt(_ attempt: CaptureAcceptedAttempt) -> CaptureReceipt {
    CaptureReceipt(
      identity: attempt.identity, phase: .reservedAccepted, requestHash: DurableStoreEncoding.acceptedHash(attempt),
      receiptHash: DurableStoreEncoding.hex(attempt.acceptedReceiptHash), durable: true, terminal: nil)
  }

  private func readProperties(_ path: [String]) throws -> [String: String] {
    DurableStoreEncoding.parseProperties(try files.readBytes(path))
  }

  /// Receipts and accepted records are written with the accepted-record fault
  /// point, as on Android.
  private func writePropertiesExclusive(_ path: [String], _ values: [String: String], _ point: DurableStoreFaultPoint = .acceptedRecord) throws {
    try files.writeExclusive(path, DurableStoreEncoding.propertiesBytes(values), point)
  }

  static func rootRecord(requestHash: String, revision: Int64, components: [DurableComponentDescriptor], prior: CaptureRootPointer?, priorPrevious: String?) -> [UInt8] {
    var text = "schema=\(NativeStoreFormatVersions.photoRootSchemaPlain)\nrevision=\(revision)\nrequest=\(requestHash)\nprevious=\(prior?.hash ?? "-")\nsecondPrevious=\(priorPrevious ?? "-")\n"
    for component in components.sorted(by: { $0.kind < $1.kind }) {
      text += "component=\(component.kind.storedName):\(component.byteLength):\(DurableStoreEncoding.hex(component.sha256))\n"
    }
    return Array(text.utf8)
  }

  private static func rootRecord(requestHash: String, revision: Int64, components: [DurableComponentDescriptor], prior: SelectedPointer?) -> [UInt8] {
    rootRecord(
      requestHash: requestHash, revision: revision, components: components,
      prior: prior.map { CaptureRootPointer(revision: $0.revision, hash: $0.hash, commit: "") }, priorPrevious: prior?.previousHash)
  }

  static func receiptProperties(_ receipt: CaptureReceipt, rootHash: String) -> [String: String] {
    let terminal = receipt.terminal!
    var values: [String: String] = [
      "kind": terminal.kind.storedName, "requestHash": receipt.requestHash, "receiptHash": receipt.receiptHash,
      "rootHash": rootHash, "reason": terminal.reason,
    ]
    if let value = terminal.captureId { values["captureId"] = value }
    if let value = terminal.captureRevision { values["revision"] = String(value) }
    if let value = terminal.jpegSizeBytes { values["jpegSizeBytes"] = String(value) }
    if let value = terminal.dngSizeBytes { values["dngSizeBytes"] = String(value) }
    return values
  }

  static func putRecoveryContext(_ value: NativeCaptureRecoveryContext, into data: inout [String: String]) {
    data["recovery.sessionId"] = value.sessionId
    data["recovery.groupId"] = value.groupId
    data["recovery.groupIndex"] = String(value.groupIndex)
    data["recovery.groupGeneration"] = String(value.groupGeneration)
    data["recovery.trigger"] = value.trigger
    data["recovery.requestedAtMs"] = String(value.requestedAtMs)
    data["recovery.coverageRevision"] = String(value.coverageRevision)
    data["recovery.timestampMs"] = String(value.timestampMs)
    data["recovery.position"] = DurableStoreEncoding.javaDoubles(value.position)
    data["recovery.rotation"] = DurableStoreEncoding.javaDoubles(value.rotation)
    data["recovery.viewMatrix"] = DurableStoreEncoding.javaDoubles(value.viewMatrix)
    data["recovery.projectionMatrix"] = DurableStoreEncoding.javaDoubles(value.projectionMatrix)
    data["recovery.groupFromWorld"] = DurableStoreEncoding.javaDoubles(value.groupFromWorld)
    data["recovery.worldFromGroup"] = DurableStoreEncoding.javaDoubles(value.worldFromGroup)
  }

  static func recoveryContext(_ values: [String: String]) throws -> NativeCaptureRecoveryContext? {
    guard let sessionId = values["recovery.sessionId"] else { return nil }
    func long(_ name: String) throws -> Int64 {
      guard let value = values["recovery.\(name)"].flatMap(Int64.init) else {
        throw DurableStoreConflict("Recovered context has invalid \(name)")
      }
      return value
    }
    func doubles(_ name: String, _ count: Int) throws -> [Double] {
      let parsed = values["recovery.\(name)"]?.split(separator: ",", omittingEmptySubsequences: false).map { Double($0) }
      guard let list = parsed, list.count == count, list.allSatisfy({ $0 != nil }) else {
        throw DurableStoreConflict("Recovered context has invalid \(name)")
      }
      return list.map { $0! }
    }
    guard let groupId = values["recovery.groupId"] else { throw DurableStoreConflict("Recovered context has no group") }
    guard let trigger = values["recovery.trigger"] else { throw DurableStoreConflict("Recovered context has no trigger") }
    return NativeCaptureRecoveryContext(
      sessionId: sessionId, groupId: groupId, groupIndex: try long("groupIndex"), groupGeneration: try long("groupGeneration"),
      trigger: trigger, requestedAtMs: try long("requestedAtMs"), coverageRevision: try long("coverageRevision"),
      timestampMs: try long("timestampMs"), position: try doubles("position", 3), rotation: try doubles("rotation", 4),
      viewMatrix: try doubles("viewMatrix", 16), projectionMatrix: try doubles("projectionMatrix", 16),
      groupFromWorld: try doubles("groupFromWorld", 16), worldFromGroup: try doubles("worldFromGroup", 16))
  }

  /// Version-1 exact identity, cross-checked with the plain fields; nil for a
  /// legacy record without one.
  static func exactIdentity(_ values: [String: String]) throws -> CaptureAttemptIdentity? {
    let version = values["acceptedIdentityVersion"]
    let encoded = values["acceptedIdentity"]
    if version == nil && encoded == nil { return nil }
    guard version == "1", let text = encoded, Int64(text.utf8.count) <= NativeCaptureReservationBounds.acceptedRecordBytes,
          let identity = DurableStoreEncoding.exactIdentity(base64: text),
          identity.attemptId == values["attemptId"], identity.commitId == values["commitId"],
          identity.lifecycleCut.sessionId == values["session"] else {
      throw DurableStoreConflict("Accepted exact identity is missing, malformed or conflicting")
    }
    return identity
  }
}
