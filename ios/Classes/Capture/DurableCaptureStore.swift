import Foundation

/// A receipt exists but does not resolve to a terminal: its pointer cut is
/// unknown, or the record is partial or unreadable. Nothing of the attempt
/// may be deleted or re-receipted; restart recovery owns it.
struct DurableReceiptUnknown: Error, CustomStringConvertible {
  let message: String
  init(_ message: String) { self.message = message }
  var description: String { "Unknown pointer cut: \(message)" }
}

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
    var heicSizeBytes: Int64? = nil
    var shot: CommittedShot? = nil
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
    else { throw NativeCaptureOwnerError.storageFull }
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

  /// Bytes the accepted attempt of [template] may commit: the smaller of its
  /// live reservation and its profile's component maximum (the commit guard
  /// of [commitStreamedFinalized]); nil when the attempt has no accepted
  /// record or live reservation.
  func committableBytes(_ template: CapturePostOutputTemplate) throws -> Int64? {
    let identity = template.accepted.identity
    let location = try self.location(identity)
    let acceptedPath = try acceptedFile(location, identity)
    guard files.isFile(acceptedPath) else { return nil }
    let accepted = try readProperties(acceptedPath)
    guard accepted["acceptedHash"] == DurableStoreEncoding.acceptedHash(template.accepted),
          let reservation = try accepted["reservationToken"].flatMap({ try budget.reservation(token: $0) })
    else { return nil }
    return min(reservation.bytes, template.accepted.profile.maximumComponentBytes)
  }

  /// The direct exposure path: the accepted identity and reservation exist;
  /// each component is streamed once into staging while its descriptor is
  /// computed, then moved, rooted, receipted and selected.
  ///
  /// A burst [shot] streams its other images and its record into the
  /// same staging and asset folder and writes a shot root (schema 7) that
  /// lists them: the single pointer swap commits every image of the shot or
  /// none, and recovery of an unreceipted attempt removes them with the
  /// assets.
  func commitStreamedFinalized(
    _ template: CapturePostOutputTemplate, exposureTimestampNanoseconds: Int64,
    components: [(CaptureComponentKind, [UInt8])], shot: DurableShotPayload? = nil
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
    // Never chain a new root while a slot holds an uncertain one.
    try resolveUncertainPointers(location)
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
      var stagedShot: (images: [(DurableShotPayload.Image, [String], DurableComponentDescriptor)], record: ([String], DurableComponentDescriptor))?
      if let shot = shot {
        guard shot.images.allSatisfy({ $0.index > 0 && ShotRecord.roles.contains($0.role) }),
              Set(shot.images.map { $0.index }).count == shot.images.count else {
          throw DurableStoreStateError("Shot images are malformed")
        }
        var images: [(DurableShotPayload.Image, [String], DurableComponentDescriptor)] = []
        for image in shot.images.sorted(by: { $0.index < $1.index }) {
          let file = staging + ["shot-image-\(image.index).part"]
          try files.at(.componentOpen)
          let (length, digest) = try files.streamExclusive(file, image.bytes)
          try files.at(.lengthCheck)
          actualBytes += length
          images.append((image, file, DurableComponentDescriptor(kind: .jpeg, byteLength: length, sha256: digest,
                                                                 durableObjectId: DurableStoreEncoding.hex(digest))))
        }
        let recordFile = staging + ["shot-record.part"]
        try files.at(.componentOpen)
        let (length, digest) = try files.streamExclusive(recordFile, shot.record)
        try files.at(.lengthCheck)
        actualBytes += length
        stagedShot = (images, (recordFile, DurableComponentDescriptor(kind: .sidecar, byteLength: length, sha256: digest,
                                                                      durableObjectId: DurableStoreEncoding.hex(digest))))
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
      var shotLines: CaptureRootShot?
      if let shot = shot, let stagedShot = stagedShot {
        for (image, file, descriptor) in stagedShot.images {
          try moveImmutable(file, assets + [Self.shotImageBlobName(index: image.index, hash: descriptor.durableObjectId)], descriptor)
        }
        let (recordFile, recordDescriptor) = stagedShot.record
        try moveImmutable(recordFile, assets + [Self.shotRecordBlobName(hash: recordDescriptor.durableObjectId)], recordDescriptor)
        shotLines = CaptureRootShot(
          shotId: shot.shotId.uuidString.lowercased(),
          images: stagedShot.images.map {
            CaptureRootShotImage(index: $0.0.index, role: $0.0.role, length: $0.2.byteLength, hash: $0.2.durableObjectId)
          },
          record: CaptureRootComponent(kind: "RECORD", length: recordDescriptor.byteLength, hash: recordDescriptor.durableObjectId))
      }
      let prior = try selectedRoot(location)
      let revision = (prior?.revision ?? 0) + 1
      let rootBytes = Self.rootRecord(requestHash: requestHash, revision: revision, components: staged.map { $0.2 }, prior: prior, shot: shotLines)
      let rootHash = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(rootBytes))
      try files.at(.rootWrite)
      try files.writeImmutable(location + ["objects", "\(rootHash).root"], rootBytes, .rootWrite)
      try files.at(.rootFileSync)
      let jpegSize = request.components.first { $0.kind == .jpeg }?.byteLength
      let dngSize = request.components.first { $0.kind == .dng }?.byteLength
      let terminal = CaptureTerminal(
        kind: .committedPicture, identity: identity, canonicalTerminalHash: requestHash, reason: "committed",
        captureId: captureId, captureRevision: revision, manifestId: rootHash, jpegSizeBytes: jpegSize, dngSizeBytes: dngSize, heicSizeBytes: request.components.first { $0.kind == .heic }?.byteLength,
        shot: committedShot(location, captureId: captureId, rootHash: rootHash))
      let committed = CaptureReceipt(
        identity: identity, phase: .committedPicture, requestHash: requestHash,
        receiptHash: DurableStoreEncoding.receiptHash(requestHash: requestHash, rootHash: rootHash), durable: true, terminal: terminal)
      try files.at(.receiptWrite)
      var properties = Self.receiptProperties(committed, rootHash: rootHash)
      properties["templateHash"] = DurableStoreEncoding.templateHash(template)
      try writePropertiesExclusive(receiptPath, properties)
      try files.at(.receiptFileSync)
      try publishPointer(location, CaptureRootPointer(revision: revision, hash: rootHash, commit: identity.commitId))
      try budget.settle(reservation, actualBytes: actualBytes)
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
  /// [request] is the exact finalized request or the admission template of
  /// the same attempt: the receipt binds both (`requestHash`, `templateHash`).
  /// The selected cut must be the receipt root's predecessor and every blob
  /// of the root must verify; the reservation is then charged with the
  /// root's bytes exactly once.
  func rebaseSameAttempt(_ request: CaptureCommitRequest) throws -> CaptureReceipt {
    let identity = request.accepted.identity
    let location = try self.location(identity)
    if tombstone(location) { throw DurableStoreConflict("Session is tombstoned") }
    let file = try receiptFile(location, identity)
    guard files.isFile(file) else { throw DurableStoreStateError("No durable receipt to rebase") }
    guard let values = try completeCommittedReceipt(file) else {
      throw DurableReceiptUnknown("Receipt is not a complete committed record")
    }
    guard values["requestHash"] == DurableStoreEncoding.requestHash(request)
            || values["templateHash"] == DurableStoreEncoding.templateHash(request.template) else {
      throw DurableStoreStateError("Changed attempt cannot rebase")
    }
    let accepted = try readProperties(try acceptedFile(location, identity))
    guard try rebaseCommittedReceipt(location, commitId: identity.commitId, values) else {
      throw DurableStoreStateError("Unknown receipt cannot rebase over a different root cut")
    }
    try settleCommitted(location, attempt: try attemptDirectory(location, identity), accepted: accepted, rootHash: values["rootHash"]!)
    guard let result = try receipt(location, identity) else { throw DurableStoreStateError("Rebased receipt is missing") }
    return result
  }

  /// Makes the root of a durable committed receipt selected when it is not
  /// retained yet: only when its revision and previous root continue the
  /// selected cut and its blobs verify. True when the root is retained.
  private func rebaseCommittedReceipt(_ location: [String], commitId: String, _ values: [String: String]) throws -> Bool {
    guard let rootHash = values["rootHash"], let revision = values["revision"].flatMap(Int64.init),
          try validRootHash(location, rootHash, revision, 0) else { return false }
    if try retainedRootHashes(location).contains(rootHash) { return true }
    try resolveUncertainPointers(location)
    if try retainedRootHashes(location).contains(rootHash) { return true }
    let selected = try selectedRoot(location)
    guard revision == (selected?.revision ?? 0) + 1, try rootPrevious(location, rootHash) == selected?.hash,
          let captureId = values["captureId"], try verifiedAssets(location, captureId: captureId, rootHash: rootHash) else {
      return false
    }
    try publishPointer(location, CaptureRootPointer(revision: revision, hash: rootHash, commit: commitId))
    return true
  }

  /// The values of a fully written committed receipt of this writer: the
  /// record ends with its line terminator, its receipt hash binds its request
  /// and root, and it carries the template binding (its last key). Nil for a
  /// partial, foreign or abandoned record.
  private func completeCommittedReceipt(_ path: [String]) throws -> [String: String]? {
    let bytes = try files.readBytes(path)
    guard bytes.last == UInt8(ascii: "\n") else { return nil }
    let values = DurableStoreEncoding.parseProperties(bytes)
    guard values["kind"] == CaptureTerminalKind.committedPicture.storedName,
          let requestHash = values["requestHash"], DurableStoreEncoding.isHash(requestHash),
          let rootHash = values["rootHash"], DurableStoreEncoding.isHash(rootHash),
          values["receiptHash"] == DurableStoreEncoding.receiptHash(requestHash: requestHash, rootHash: rootHash),
          let templateHash = values["templateHash"], DurableStoreEncoding.isHash(templateHash),
          values["captureId"] != nil, values["revision"].flatMap(Int64.init) != nil else { return nil }
    return values
  }

  /// Every blob of [rootHash] exists with its rooted length and digest: the
  /// components and, for a shot root, every shot image and the shot record.
  private func verifiedAssets(_ location: [String], captureId: String, rootHash: String) throws -> Bool {
    guard RootedDurableFiles.isValidSegment(captureId), let metadata = try readRootMetadata(location, rootHash),
          !metadata.components.isEmpty else { return false }
    for (name, expectedLength, expectedHash) in Self.rootBlobs(metadata) {
      let blob = location + ["assets", captureId, name]
      guard files.isFile(blob) else { return false }
      let (length, digest) = try files.digestAndLength(blob)
      guard length == expectedLength, DurableStoreEncoding.hex(digest) == expectedHash else { return false }
    }
    return true
  }

  /// Asset file name, length and digest of every blob a root commits.
  static func rootBlobs(_ metadata: CaptureRootMetadata) -> [(name: String, length: Int64, hash: String)] {
    var blobs = metadata.components.map { ("\($0.kind.lowercased()).\($0.hash).blob", $0.length, $0.hash) }
    if let shot = metadata.shot {
      blobs += shot.images.map { (shotImageBlobName(index: $0.index, hash: $0.hash), $0.length, $0.hash) }
      blobs.append((shotRecordBlobName(hash: shot.record.hash), shot.record.length, shot.record.hash))
    }
    return blobs
  }

  /// The charge of a committed root: its components and, for a shot root,
  /// every shot image and the shot record, so a shot is charged once whole.
  static func rootChargeBytes(_ metadata: CaptureRootMetadata) -> Int64 {
    rootBlobs(metadata).reduce(0) { $0 + $1.length }
  }

  /// Charges a retained committed root's bytes to the attempt's live
  /// reservation (once: the reservation closes) and removes its staging.
  private func settleCommitted(_ location: [String], attempt: [String], accepted: [String: String], rootHash: String) throws {
    if let token = accepted["reservationToken"], let reservation = try budget.reservation(token: token) {
      try budget.settle(reservation, actualBytes: try committedComponentBytes(location, rootHash))
    }
    try files.deleteTree(attempt + ["staging"], .deleteReclaim)
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
    if files.isFile(try receiptFile(location, terminal.identity)) {
      // Receipt persistence began: the pointer cut is unknown. Staging,
      // assets and the reservation stay for restart recovery.
      if let prior = try? receipt(location, terminal.identity) { return prior }
      throw DurableReceiptUnknown("Receipt exists without a resolved terminal")
    }
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

  /// Tombstone-first whole-session deletion fence; it wins over later
  /// callbacks. Every live reservation of the session then closes: an
  /// attempt whose committed root is published (reachable from the selected
  /// pointer) charges that root's bytes, so the session reclaim covers it;
  /// any other reservation is released (its data was never charged).
  /// Idempotent: an existing tombstone is kept. The session directory is
  /// synced first, so a pointer switch of unknown durability becomes known.
  func tombstoneSession(_ sessionId: String) throws {
    try tombstoneDirectory(sessionRoot + [DurableStoreEncoding.safeName(sessionId)])
  }

  private func tombstoneDirectory(_ directory: [String]) throws {
    try files.ensureDirectory(directory)
    try files.at(.tombstoneRecord)
    if !files.isFile(directory + ["tombstone"]) {
      try files.writeExclusive(directory + ["tombstone"], Array("deleted\n".utf8), .tombstoneRecord)
    }
    try files.at(.tombstoneDirectorySync)
    try files.backend.syncDirectory(directory)
    for slot in ["A", "B"] {
      let pointer = directory + ["root-\(slot).ptr"]
      if files.isFile(pointer), let value = CaptureRootRecords.parsePointer(try files.readBytes(pointer)) {
        uncertainRootHashes.remove(value.hash)
      }
    }
    let published = try deletionPublishedRoots(directory)
    let prefix = "capture:\(directory.last!):"
    for reservation in try budget.liveReservations() where reservation.owner.hasPrefix(prefix) {
      let attemptHash = String(reservation.owner.dropFirst(prefix.count))
      guard DurableStoreEncoding.isHash(attemptHash) else { continue }
      if let bytes = try deletionRecordBytes(directory, attempt: directory + ["attempts", attemptHash], published: published) {
        try budget.settle(reservation, actualBytes: min(bytes, reservation.bytes))
      } else {
        try budget.release(reservation)
      }
    }
  }

  /// Deletes one session's photos after the app deleted the session: the
  /// tombstone fence, then the bytes of the session's published commits
  /// leave the ledger exactly once (verified reclaim `verified-<id>.reclaim`,
  /// id bound to the session), then its attempts, roots, pointers and blobs
  /// are removed. The tombstone and the reclaim marker stay, so a repeated
  /// call after a crash at any step finishes the same deletion and never
  /// reclaims twice. Returns the bytes reclaimed by this call.
  @discardableResult
  func deleteSession(_ sessionId: String) throws -> Int64 {
    let directory = sessionRoot + [DurableStoreEncoding.safeName(sessionId)]
    return try deleteSessionDirectory(directory, reclaimId: Self.sessionReclaimId(sessionId))
  }

  private func deleteSessionDirectory(_ directory: [String], reclaimId: String) throws -> Int64 {
    try tombstoneDirectory(directory)
    var reclaimed: Int64 = 0
    let reclaimedSession = try budget.hasVerifiedReclaim(reclaimId)
    let reclaimedOrphan = try budget.hasVerifiedReclaim(orphanReclaimId(directory.last!))
    if !reclaimedSession && !reclaimedOrphan {
      let published = try deletionPublishedRoots(directory)
      var total: Int64 = 0
      var countedRoots = Set<String>()
      let attempts = directory + ["attempts"]
      if files.isDirectory(attempts) {
        for name in try files.list(attempts) {
          let attempt = attempts + [name]
          guard files.isDirectory(attempt) else { continue }
          let accepted = attempt + ["accepted.properties"]
          if files.isFile(accepted) {
            let bytes = try files.readBytes(accepted)
            if !Self.isFinishedAcceptedRecord(bytes, DurableStoreEncoding.parseProperties(bytes)) {
              try quarantineDeletionMetadata(directory, records: [accepted])
            }
          }
          if let bytes = try deletionRecordBytes(directory, attempt: attempt, published: published),
             let hash = try readProperties(attempt + ["receipt.properties"])["rootHash"], countedRoots.insert(hash).inserted {
            total += bytes
          }
        }
      }
      reclaimed = min(total, try budget.committedBytes())
      if reclaimed > 0 { try budget.reclaimVerifiedOnce(reclaimId: reclaimId, bytes: reclaimed) }
    }
    for name in try files.list(directory) where name != "tombstone" {
      try files.deleteTree(directory + [name], .deleteReclaim)
    }
    return reclaimed
  }

  /// The authoritative app index is the only authority for deleting orphan sessions.
  /// Hash comparisons preserve live sessions without trusting their native history.
  @discardableResult
  func reconcileSessions(liveSessionIds: [String]) throws -> Int64 {
    let live = Set(liveSessionIds.map(DurableStoreEncoding.safeName))
    var reclaimed: Int64 = 0
    for hash in try files.list(sessionRoot) where DurableStoreEncoding.isHash(hash) && !live.contains(hash) {
      let directory = sessionRoot + [hash]
      guard files.isDirectory(directory) else { continue }
      let identities = try files.walk(directory).filter { $0.last == "accepted.properties" }
        .compactMap { (try? readProperties($0))?["session"] }
        .filter { DurableStoreEncoding.safeName($0) == hash }
      if let sessionId = identities.first {
        reclaimed += try deleteSession(sessionId)
      } else if !tombstone(directory) {
        // The tombstone is written before any reclaim. Its absence proves
        // that a legacy orphan cannot already have a session deletion debit.
        reclaimed += try deleteSessionDirectory(directory, reclaimId: orphanReclaimId(hash))
      } else if tombstone(directory) {
        // The fence does not prove quarantine/reclaim completed. Preserve all
        // surviving metadata before removal; never invent a second budget debit.
        try quarantineDeletionMetadata(directory, records: files.walk(directory).filter {
          let name = $0.last ?? ""
          return name.hasSuffix(".properties") || name.hasSuffix(".root") || name.hasSuffix(".ptr")
        })
        for name in try files.list(directory) where name != "tombstone" {
          try files.deleteTree(directory + [name], .deleteReclaim)
        }
      }
    }
    return reclaimed
  }

  private func orphanReclaimId(_ sessionHash: String) -> String {
    DurableStoreEncoding.hex(DurableStoreEncoding.sha256("native-capture-orphan-reclaim|\(sessionHash)"))
  }

  /// Reclaim identity of one deleted session.
  static func sessionReclaimId(_ sessionId: String) -> String {
    DurableStoreEncoding.hex(DurableStoreEncoding.sha256("native-capture-session-reclaim|\(sessionId)"))
  }

  /// Preserve damaged metadata outside the deleted session, verified before removal.
  private func quarantineDeletionMetadata(_ directory: [String], records: [[String]]) throws {
    for record in records where files.isFile(record) {
      let bytes = try files.readBytes(record)
      let hash = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes))
      let target = ["quarantine", directory.last!, "\(hash).record"]
      if !files.isFile(target) {
        try files.atomicReplace(target, bytes, replacePoint: nil)
      }
      guard try files.readBytes(target) == bytes else { throw DurableStoreConflict("Quarantine verification failed") }
    }
  }

  private func deletionPublishedRoots(_ directory: [String]) throws -> Set<String> {
    do {
      for slot in ["A", "B"] {
        let file = directory + ["root-\(slot).ptr"]
        guard files.isFile(file) else { continue }
        if let pointer = CaptureRootRecords.parsePointer(try files.readBytes(file)) {
          if try !validRootHash(directory, pointer.hash, pointer.revision, 0) {
            try quarantineDeletionMetadata(directory, records: [file])
          }
        } else {
          try quarantineDeletionMetadata(directory, records: [file])
        }
      }
      let objects = directory + ["objects"]
      if files.isDirectory(objects) {
        for name in try files.list(objects) where name.hasSuffix(".root") {
          if try readRootMetadata(directory, String(name.dropLast(5))) == nil {
            try quarantineDeletionMetadata(directory, records: [objects + [name]])
          }
        }
      }
      return try publishedRootHashes(directory)
    } catch {
      try quarantineDeletionMetadata(directory, records: files.walk(directory).filter {
        $0.last?.hasSuffix(".root") == true || $0.last?.hasSuffix(".ptr") == true
      })
      return []
    }
  }

  private func deletionRecordBytes(_ directory: [String], attempt: [String], published: Set<String>) throws -> Int64? {
    do { return try publishedRecordBytes(directory, attempt: attempt, published: published) }
    catch {
      try quarantineDeletionMetadata(directory, records: [attempt + ["receipt.properties"]])
      return nil
    }
  }

  /// Every root reachable from the selected pointer through `previous`.
  private func publishedRootHashes(_ location: [String]) throws -> Set<String> {
    var out = Set<String>()
    var hash = try selectedRoot(location)?.hash
    while let current = hash, !out.contains(current) {
      out.insert(current)
      hash = try rootPrevious(location, current)
    }
    return out
  }

  /// Bytes of the published root an attempt's committed record names (the
  /// charge of a committed attempt); nil without one.
  private func publishedRecordBytes(_ location: [String], attempt: [String], published: Set<String>) throws -> Int64? {
    let receipt = attempt + ["receipt.properties"]
    guard files.isFile(receipt) else { return nil }
    let receiptBytes = try files.readBytes(receipt)
    guard receiptBytes.last == UInt8(ascii: "\n") else { throw DurableStoreConflict("Truncated deletion receipt") }
    let values = try readProperties(receipt)
    if values["kind"] == CaptureTerminalKind.abandonedAttempt.storedName {
      guard let request = values["requestHash"], DurableStoreEncoding.isHash(request), values["rootHash"] == "abandoned",
            values["receiptHash"] == DurableStoreEncoding.receiptHash(requestHash: request, rootHash: "abandoned") else {
        throw DurableStoreConflict("Malformed deletion abandonment receipt")
      }
      return nil
    }
    guard values["kind"] == CaptureTerminalKind.committedPicture.storedName,
          let rootHash = values["rootHash"], DurableStoreEncoding.isHash(rootHash),
          let request = values["requestHash"], DurableStoreEncoding.isHash(request),
          let captureId = values["captureId"], DurableStoreEncoding.isHash(captureId),
          let revision = values["revision"].flatMap(Int64.init), revision > 0 else {
      throw DurableStoreConflict("Malformed deletion committed receipt")
    }
    guard let metadata = try readRootMetadata(location, rootHash) else { throw DurableStoreConflict("Missing deletion receipt root") }
    guard request == metadata.request,
          values["receiptHash"] == DurableStoreEncoding.receiptHash(requestHash: request, rootHash: rootHash),
          values["revision"].flatMap(Int64.init) == metadata.revision else {
      throw DurableStoreConflict("Deletion receipt does not bind its published root")
    }
    guard published.contains(rootHash) else { return nil }
    return Self.rootChargeBytes(metadata)
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
      // Deletion wins before reading any partially removed accepted record or asset.
      guard !tombstone(session) else { continue }
      let accepted = session + ["attempts", attemptHash, "accepted.properties"]
      guard files.isFile(accepted) else { continue }
      try repairAcceptedCandidate(session: session, accepted: accepted)
      let acceptedBytes = try files.readBytes(accepted)
      let acceptedValues = DurableStoreEncoding.parseProperties(acceptedBytes)
      // The accepted record is created under its final name and then
      // written, after its index entry: a record cut short is an acceptance
      // that never finished (no exposure can precede it). Repair proved it
      // absent; it is neither projected nor kept in the index.
      guard Self.isFinishedAcceptedRecord(acceptedBytes, acceptedValues),
            let attemptId = acceptedValues["attemptId"] else { continue }
      validEntries.append((sessionHash, attemptHash))
      let context = try Self.recoveryContext(acceptedValues)
      let receiptPath = Array(accepted.dropLast()) + ["receipt.properties"]
      guard files.isFile(receiptPath) else {
        result.append(RecoveryProjection(attemptId: attemptId, kind: nil, reason: "recovered-accepted-unknown", recoveryContext: context))
        continue
      }
      guard let value = try? readProperties(receiptPath) else {
        result.append(RecoveryProjection(attemptId: attemptId, kind: nil, reason: "recovered-receipt-unknown", recoveryContext: context))
        continue
      }
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
          jpegSizeBytes: verified.jpegSizeBytes, dngSizeBytes: verified.dngSizeBytes, heicSizeBytes: verified.heicSizeBytes,
          shot: verified.captureId.flatMap { committedShot(session, captureId: $0, rootHash: rootHash) }))
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

  /// Reservation-driven reconciliation at binding open, after the bounded
  /// startup recovery and before the first admission: every live
  /// reservation names its attempt (`capture:<session>:<commit>`). Without
  /// an accepted record, or when the record names another reservation, the
  /// reservation is released; otherwise the attempt is repaired by the
  /// recovery rules (no receipt: abandoned and released; committed receipt
  /// in the retained graph: settled; partial receipt or root outside the
  /// retained graph: the liability stays). Committed roots, receipts and
  /// assets are never deleted. A failure on one reservation is logged and the
  /// next one goes on. Returns the number of reservations closed.
  @discardableResult
  func reconcileReservations(log: (String) -> Void = { NSLog("capture3d store: %@", $0) }) -> Int {
    let live: [StorageBudgetReservation]
    do {
      live = try budget.liveReservations()
    } catch {
      log("reservation reconciliation skipped: \(error)")
      return 0
    }
    var closed = 0
    for reservation in live {
      do {
        if try reconcile(reservation) { closed += 1 }
      } catch {
        log("reservation \(reservation.owner) not reconciled: \(error)")
      }
    }
    return closed
  }

  /// True when [reservation] is closed after reconciliation.
  private func reconcile(_ reservation: StorageBudgetReservation) throws -> Bool {
    let parts = reservation.owner.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 3, parts[0] == "capture", DurableStoreEncoding.isHash(parts[1]), DurableStoreEncoding.isHash(parts[2]) else {
      throw DurableStoreStateError("Reservation owner is not a capture attempt")
    }
    let session = sessionRoot + [parts[1]]
    let accepted = session + ["attempts", parts[2], "accepted.properties"]
    guard files.isFile(accepted), try readProperties(accepted)["reservationToken"] == reservation.token else {
      return try budget.release(reservation)
    }
    try repairAcceptedCandidate(session: session, accepted: accepted)
    return try budget.reservation(token: reservation.token) == nil
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
    // A partial, unreadable or foreign receipt is unknown: every file and
    // the liability stay, and recovery goes on with the next attempt.
    guard let receiptValues = try? completeCommittedReceipt(receipt), let rootHash = receiptValues["rootHash"] else {
      if let values = try? readProperties(receipt), values["kind"] == CaptureTerminalKind.committedPicture.storedName,
         let rootHash = values["rootHash"], (try? retainedRootHashes(session).contains(rootHash)) == true {
        try settleCommitted(session, attempt: attempt, accepted: acceptedValues, rootHash: rootHash)
      }
      return
    }
    // A committed receipt outside the retained graph is rebased when the
    // selected cut is its predecessor (the pointer switch was lost);
    // otherwise it stays unknown with its staging and liability.
    guard !tombstone(session), let commitId = acceptedValues["commitId"],
          (try? rebaseCommittedReceipt(session, commitId: commitId, receiptValues)) == true else { return }
    try settleCommitted(session, attempt: attempt, accepted: acceptedValues, rootHash: rootHash)
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
    var heicSizeBytes: Int64? = nil
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
        jpegSizeBytes: verified.jpegSizeBytes, dngSizeBytes: verified.dngSizeBytes, heicSizeBytes: verified.heicSizeBytes,
        shot: verified.captureId.flatMap { committedShot(location, captureId: $0, rootHash: rootHash) })
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
        if kind == .heic && metadata.components.first(where: { $0.kind == kind.storedName })?.length != size { throw DurableStoreConflict("HEIC receipt differs from rooted component") }
        return size
      }
      return metadata.components.first { $0.kind == kind.storedName }?.length
    }
    return VerifiedCommittedReceipt(
      captureId: receipt["captureId"], revision: receipt["revision"].flatMap(Int64.init),
      jpegSizeBytes: try size("jpegSizeBytes", .jpeg), dngSizeBytes: try size("dngSizeBytes", .dng), heicSizeBytes: try size("heicSizeBytes", .heic))
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

  /// A pointer slot whose rename happened but whose directory sync failed
  /// ([publishPointer]) holds an uncertain root: [selectedRoot] skips it, so
  /// a new commit or rebase would compute the same revision and overwrite
  /// that slot, losing a root whose receipt is durable. Before either, the
  /// session directory is synced again: on success the uncertain roots are
  /// durable and selected again (their attempts' reservations are settled);
  /// on failure the commit or rebase is refused and the slot stays.
  private func resolveUncertainPointers(_ location: [String]) throws {
    var uncertain: [CaptureRootPointer] = []
    for slot in ["A", "B"] {
      let file = location + ["root-\(slot).ptr"]
      guard files.isFile(file), let pointer = CaptureRootRecords.parsePointer(try files.readBytes(file)),
            uncertainRootHashes.contains(pointer.hash) else { continue }
      uncertain.append(pointer)
    }
    guard !uncertain.isEmpty else { return }
    do {
      try files.at(.pointerDirectorySync)
      try files.backend.syncDirectory(location)
    } catch {
      throw DurableStoreStateError("Session has an uncertain root pointer; directory sync failed again: \(error)")
    }
    for pointer in uncertain {
      uncertainRootHashes.remove(pointer.hash)
      // The attempt that published this root lost its settlement with the
      // failed sync; charge its reservation now. A failure here leaves the
      // reservation to the reconciliation at the next binding open.
      let attempt = location + ["attempts", DurableStoreEncoding.safeName(pointer.commit)]
      let accepted = attempt + ["accepted.properties"], receipt = attempt + ["receipt.properties"]
      guard files.isFile(accepted), files.isFile(receipt),
            (try? readProperties(receipt))?["rootHash"] == pointer.hash,
            let values = try? readProperties(accepted) else { continue }
      do {
        try settleCommitted(location, attempt: attempt, accepted: values, rootHash: pointer.hash)
      } catch {
        NSLog("capture3d store: settlement of resolved root %@ deferred: %@", pointer.hash, "\(error)")
      }
    }
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
    return Self.rootChargeBytes(metadata)
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

  /// The images after the first one of the shot root [rootHash], from its
  /// shot record: the record blob must have the rooted length and digest,
  /// name the root's shot id, and list every rooted image with the rooted
  /// length and digest. Nil for a plain root; nil (logged) for a record that
  /// does not verify: its images stay committed in the store but are not
  /// reported. Live terminals, receipt queries and restart recovery read
  /// this same projection, so a replay equals the live terminal.
  private func committedShot(_ location: [String], captureId: String, rootHash: String) -> CommittedShot? {
    guard let metadata = try? readRootMetadata(location, rootHash), let shot = metadata.shot else { return nil }
    func refused(_ reason: String) -> CommittedShot? {
      NSLog("capture3d store: shot root %@ not reported: %@", rootHash, reason)
      return nil
    }
    guard RootedDurableFiles.isValidSegment(captureId) else { return refused("invalid capture id") }
    let recordPath = location + ["assets", captureId, Self.shotRecordBlobName(hash: shot.record.hash)]
    guard files.isFile(recordPath), let bytes = try? files.readBytes(recordPath) else { return refused("record missing") }
    guard Int64(bytes.count) == shot.record.length,
          DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes)) == shot.record.hash else {
      return refused("record digest")
    }
    guard let decoded = ShotRecord.decode(bytes), decoded.shotId == shot.shotId,
          decoded.images.count == shot.images.count + 1 else { return refused("record content") }
    var images: [CommittedShotImage] = []
    for rooted in shot.images {
      guard rooted.index < decoded.images.count else { return refused("image \(rooted.index) not recorded") }
      let image = decoded.images[rooted.index]
      guard image.byteLength == rooted.length, image.sha256 == rooted.hash else {
        return refused("image \(rooted.index) differs from its root line")
      }
      images.append(CommittedShotImage(
        index: rooted.index, captureId: ShotRecord.imageCaptureId(captureId, index: rooted.index),
        jpegSizeBytes: rooted.length, exposureTimestampNs: image.exposureTimestampNs, width: image.width,
        height: image.height, trackingNormal: image.trackingNormal, flags: image.flags.rawValue,
        cameraToWorld: image.cameraToWorld, intrinsics: image.intrinsics,
        intrinsicsConvention: image.intrinsicsConvention))
    }
    return CommittedShot(
      shotId: shot.shotId, mode: decoded.mode, imageCount: decoded.images.count,
      firstExposureTimestampNs: decoded.images[0].exposureTimestampNs, images: images)
  }

  /// The committed JPEG blob of [terminal] as a file URL, for read-only
  /// consumers after the durable terminal (marker tie points); nil for
  /// another terminal kind, a backend without file URLs or a missing blob.
  func committedImageURL(_ terminal: CaptureTerminal) -> URL? {
    guard terminal.kind == .committedPicture, let captureId = terminal.captureId,
          let root = (files.backend as? PosixDurableFileBackend)?.root else { return nil }
    let assets = sessionRoot + [DurableStoreEncoding.safeName(terminal.identity.lifecycleCut.sessionId), "assets", captureId]
    guard let name = (try? files.list(assets))?.sorted().first(where: { ($0.hasPrefix("jpeg.") || $0.hasPrefix("heic.")) && $0.hasSuffix(".blob") }),
          files.isFile(assets + [name]) else { return nil }
    return (assets + [name]).reduce(root) { $0.appendingPathComponent($1, isDirectory: false) }
  }

  /// The committed files of a burst shot: image index (1...) to
  /// its JPEG and the shot record, read from the verified shot root; nil
  /// for a single-image commit or an unverifiable root.
  func committedShotFiles(_ terminal: CaptureTerminal) -> (images: [Int: URL], record: URL)? {
    guard terminal.kind == .committedPicture, let captureId = terminal.captureId, let rootHash = terminal.manifestId,
          let root = (files.backend as? PosixDurableFileBackend)?.root else { return nil }
    let location = sessionRoot + [DurableStoreEncoding.safeName(terminal.identity.lifecycleCut.sessionId)]
    guard let metadata = try? readRootMetadata(location, rootHash), let shot = metadata.shot else { return nil }
    let assets = location + ["assets", captureId]
    func url(_ name: String) -> URL? {
      guard files.isFile(assets + [name]) else { return nil }
      return (assets + [name]).reduce(root) { $0.appendingPathComponent($1, isDirectory: false) }
    }
    var images: [Int: URL] = [:]
    for image in shot.images {
      guard let file = url(Self.shotImageBlobName(index: image.index, hash: image.hash)) else { return nil }
      images[image.index] = file
    }
    guard let record = url(Self.shotRecordBlobName(hash: shot.record.hash)) else { return nil }
    return (images, record)
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

  /// A plain root (schema 5), or with [shot] a shot root (schema 7): the
  /// same lines plus `shot=<id>`, one `shotImage=<index>:<ROLE>:<length>:<sha>`
  /// per image after the first one and `shotRecord=<length>:<sha>`.
  static func rootRecord(
    requestHash: String, revision: Int64, components: [DurableComponentDescriptor], prior: CaptureRootPointer?,
    priorPrevious: String?, shot: CaptureRootShot? = nil
  ) -> [UInt8] {
    let schema = shot == nil ? (components.contains { $0.kind == .heic } ? NativeStoreFormatVersions.photoRootSchemaHeic : NativeStoreFormatVersions.photoRootSchemaPlain) : NativeStoreFormatVersions.photoRootSchemaShot
    var text = "schema=\(schema)\nrevision=\(revision)\nrequest=\(requestHash)\nprevious=\(prior?.hash ?? "-")\nsecondPrevious=\(priorPrevious ?? "-")\n"
    for component in components.sorted(by: { $0.kind < $1.kind }) {
      text += "component=\(component.kind.storedName):\(component.byteLength):\(DurableStoreEncoding.hex(component.sha256))\n"
    }
    if let shot = shot {
      text += "shot=\(shot.shotId)\n"
      for image in shot.images.sorted(by: { $0.index < $1.index }) {
        text += "shotImage=\(image.index):\(image.role):\(image.length):\(image.hash)\n"
      }
      text += "shotRecord=\(shot.record.length):\(shot.record.hash)\n"
    }
    return Array(text.utf8)
  }

  private static func rootRecord(
    requestHash: String, revision: Int64, components: [DurableComponentDescriptor], prior: SelectedPointer?, shot: CaptureRootShot?
  ) -> [UInt8] {
    rootRecord(
      requestHash: requestHash, revision: revision, components: components,
      prior: prior.map { CaptureRootPointer(revision: $0.revision, hash: $0.hash, commit: "") }, priorPrevious: prior?.previousHash,
      shot: shot)
  }

  static func shotImageBlobName(index: Int, hash: String) -> String { "shot-image-\(index).\(hash).blob" }
  static func shotRecordBlobName(hash: String) -> String { "shot-record.\(hash).blob" }

  static func receiptProperties(_ receipt: CaptureReceipt, rootHash: String) -> [String: String] {
    let terminal = receipt.terminal!
    var values: [String: String] = [
      "kind": terminal.kind.storedName, "requestHash": receipt.requestHash, "receiptHash": receipt.receiptHash,
      "rootHash": rootHash, "reason": terminal.reason,
    ]
    if let value = terminal.captureId { values["captureId"] = value }
    if let value = terminal.captureRevision { values["revision"] = String(value) }
    if let value = terminal.heicSizeBytes { values["heicSizeBytes"] = String(value) }
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

  /// An accepted record is written whole as sorted `key=value\n` lines, so
  /// a finished one ends with a newline and carries `session`, its last key;
  /// anything else is a write cut short.
  static func isFinishedAcceptedRecord(_ bytes: [UInt8], _ values: [String: String]) -> Bool {
    bytes.last == UInt8(ascii: "\n") && values["session"] != nil
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
