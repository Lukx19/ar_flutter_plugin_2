import Foundation

enum NativeCaptureEventKind: String, CaseIterable {
  case accepted, finalizing, recovering, recoveryFailed, ready, committed, abandoned, health
}

/// Bounded scalar observability of the native owner.
struct CaptureResourceSnapshot: Hashable {
  var exposures: Int64 = 0
  var lateCallbacks: Int64 = 0
  var closedComponents: Int64 = 0
  var committed: Int64 = 0
  var abandoned: Int64 = 0
  var unknownQueries: Int64 = 0
  var running: Int = 0
  var fundedWaiting: Int = 0
}

/// Strict bounded event: scalar identities and counters only, never paths or bytes.
struct NativeCaptureEvent: Hashable {
  var kind: NativeCaptureEventKind
  var attemptId: String?
  var captureId: String?
  var captureRevision: Int64?
  var manifestId: String?
  var jpegSizeBytes: Int64?
  var dngSizeBytes: Int64?
  var heicSizeBytes: Int64? = nil
  var reason: String?
  var resources: CaptureResourceSnapshot?
  var recoveryContext: NativeCaptureRecoveryContext?
  /// The images after the first one of a committed burst shot.
  var shot: CommittedShot? = nil

  /// Fields that must not change between a live terminal and its replay.
  var terminalIdentity: [AnyHashable?] {
    [kind, captureId, captureRevision, manifestId, jpegSizeBytes, dngSizeBytes, heicSizeBytes, recoveryContext,
     kind == .abandoned ? reason : nil, shot]
  }

  var isTerminal: Bool { kind == .committed || kind == .abandoned }
}

/// The admission request failed validation (`NATIVE_CAPTURE_V2_INVALID`) or
/// was not even a well-typed value (`NATIVE_CAPTURE_V2_FAILED`), matching the
/// Android split between `require` and `error`.
enum NativeCaptureWireError: Error, CustomStringConvertible {
  case invalid(String)
  case malformed(String)

  var description: String {
    switch self {
    case .invalid(let message), .malformed(let message): return message
    }
  }
}

/// The `native_capture_v2` platform-channel codec of `arcapture_<id>`
/// (Android `NativeCaptureWireV2`): strict exact-key admission decoding and
/// event maps whose keys and value types equal the Android encoder, so the
/// Dart `ARNativeCaptureEventV2.fromMap` accepts them unchanged.
enum NativeCaptureWire {
  static let wireVersion = "native_capture_v2"

  // MARK: Admission

  static func decodeAdmission(_ value: Any?) throws -> CaptureCommitRequest {
    let root = try map(value, "admission")
    try exactKeys(root, ["wireVersion", "accepted", "poseRecordHash", "cameraModelHash", "validationRecordHash", "ledgerRecordHash", "recoveryContext"])
    guard try string(root, "wireVersion") == wireVersion else { throw NativeCaptureWireError.invalid("Unsupported wire version") }
    let accepted = try decodeAccepted(root["accepted"] as Any?)
    return CaptureCommitRequest(
      accepted: accepted, components: [], exposureTimestampNanoseconds: 0,
      poseRecordHash: try digest(root, "poseRecordHash"), cameraModelHash: try digest(root, "cameraModelHash"),
      validationRecordHash: try digest(root, "validationRecordHash"), ledgerRecordHash: try digest(root, "ledgerRecordHash"),
      recoveryContext: try decodeRecoveryContext(root["recoveryContext"] as Any?))
  }

  private static func decodeRecoveryContext(_ value: Any?) throws -> NativeCaptureRecoveryContext {
    let map = try self.map(value, "recoveryContext")
    try exactKeys(map, ["sessionId", "groupId", "groupIndex", "groupGeneration", "trigger", "requestedAtMs", "coverageRevision", "timestampMs", "position", "rotation", "viewMatrix", "projectionMatrix", "groupFromWorld", "worldFromGroup"])
    let context = NativeCaptureRecoveryContext(
      sessionId: try string(map, "sessionId"), groupId: try string(map, "groupId"), groupIndex: try long(map, "groupIndex"),
      groupGeneration: try long(map, "groupGeneration"), trigger: try string(map, "trigger"),
      requestedAtMs: try long(map, "requestedAtMs"), coverageRevision: try long(map, "coverageRevision"),
      timestampMs: try long(map, "timestampMs"), position: try doubles(map, "position"), rotation: try doubles(map, "rotation"),
      viewMatrix: try doubles(map, "viewMatrix"), projectionMatrix: try doubles(map, "projectionMatrix"),
      groupFromWorld: try doubles(map, "groupFromWorld"), worldFromGroup: try doubles(map, "worldFromGroup"))
    guard context.isValid else { throw NativeCaptureWireError.invalid("Malformed recovery context") }
    return context
  }

  private static func decodeAccepted(_ value: Any?) throws -> CaptureAcceptedAttempt {
    let map = try self.map(value, "accepted")
    try exactKeys(map, ["identity", "lane", "profile", "reservation", "canonicalIntentHash", "acceptedReceiptHash"])
    let laneName = try string(map, "lane")
    guard let lane = CaptureLane.allCases.first(where: { $0.storedName == laneName.uppercased() }) else {
      throw NativeCaptureWireError.invalid("Unknown lane \(laneName)")
    }
    let accepted = CaptureAcceptedAttempt(
      identity: try decodeIdentity(map["identity"] as Any?), lane: lane, profile: try decodeProfile(map["profile"] as Any?),
      reservation: try decodeReservation(map["reservation"] as Any?),
      canonicalIntentHash: try digest(map, "canonicalIntentHash"), acceptedReceiptHash: try digest(map, "acceptedReceiptHash"))
    let reservation = accepted.reservation
    let profile = accepted.profile
    guard reservation.physicallyBacked,
          reservation.memoryBytes >= profile.maximumWorkingBytes,
          reservation.physicalStoreBytes >= NativeCaptureReservationBounds.physicalBytes(
            componentBytes: profile.maximumComponentBytes, componentCount: profile.requiredComponents.count),
          reservation.rollbackBytes >= NativeCaptureReservationBounds.rollbackBytes(componentBytes: profile.maximumComponentBytes),
          reservation.componentEntries == Int64(profile.requiredComponents.count),
          reservation.terminalEntries == 1 else {
      throw NativeCaptureWireError.invalid("Admission liabilities do not fund the profile")
    }
    return accepted
  }

  private static func decodeIdentity(_ value: Any?) throws -> CaptureAttemptIdentity {
    let map = try self.map(value, "identity")
    try exactKeys(map, ["attemptId", "commitId", "attemptOrdinal", "lifecycleCut"])
    let identity = CaptureAttemptIdentity(
      attemptId: try string(map, "attemptId"), commitId: try string(map, "commitId"),
      attemptOrdinal: try long(map, "attemptOrdinal"), lifecycleCut: try decodeCut(map["lifecycleCut"] as Any?))
    guard !identity.attemptId.isEmpty, !identity.commitId.isEmpty, identity.attemptOrdinal > 0 else {
      throw NativeCaptureWireError.invalid("Malformed attempt identity")
    }
    return identity
  }

  private static func decodeCut(_ value: Any?) throws -> CaptureLifecycleCut {
    let map = try self.map(value, "lifecycleCut")
    try exactKeys(map, ["sessionId", "sessionGeneration", "groupId", "groupGeneration", "arSessionId", "viewId", "viewGeneration", "bindingToken", "lifecycleSequence", "operationGeneration"])
    let cut = CaptureLifecycleCut(
      sessionId: try string(map, "sessionId"), sessionGeneration: try long(map, "sessionGeneration"),
      groupId: try string(map, "groupId"), groupGeneration: try long(map, "groupGeneration"),
      arSessionId: try string(map, "arSessionId"), viewId: try string(map, "viewId"),
      viewGeneration: try long(map, "viewGeneration"), bindingToken: try string(map, "bindingToken"),
      lifecycleSequence: try long(map, "lifecycleSequence"), operationGeneration: try long(map, "operationGeneration"))
    guard cut.isValid else { throw NativeCaptureWireError.invalid("Malformed lifecycle cut") }
    return cut
  }

  private static func decodeProfile(_ value: Any?) throws -> CaptureComponentProfile {
    let map = try self.map(value, "profile")
    try exactKeys(map, ["profileId", "requiredComponents", "maximumComponentBytes", "maximumWorkingBytes"])
    guard let names = map["requiredComponents"] as? [Any] else { throw NativeCaptureWireError.malformed("requiredComponents must be a list") }
    var components = Set<CaptureComponentKind>()
    for name in names {
      guard let text = name as? String else { throw NativeCaptureWireError.malformed("component must be a string") }
      guard let kind = CaptureComponentKind(storedName: text.uppercased()) else { throw NativeCaptureWireError.invalid("Unknown component \(text)") }
      components.insert(kind)
    }
    guard components == [.heic] || components == [.jpeg] || components == [.jpeg, .dng] else {
      throw NativeCaptureWireError.invalid("Profile must be JPEG or JPEG+DNG")
    }
    let profile = CaptureComponentProfile(
      profileId: try string(map, "profileId"), requiredComponents: components,
      maximumComponentBytes: try long(map, "maximumComponentBytes"), maximumWorkingBytes: try long(map, "maximumWorkingBytes"))
    guard !profile.profileId.isEmpty, profile.maximumComponentBytes > 0, profile.maximumWorkingBytes > 0 else {
      throw NativeCaptureWireError.invalid("Malformed component profile")
    }
    return profile
  }

  private static func decodeReservation(_ value: Any?) throws -> CaptureReservationLiability {
    let map = try self.map(value, "reservation")
    try exactKeys(map, ["memoryBytes", "physicalStoreBytes", "componentEntries", "terminalEntries", "rollbackBytes", "physicallyBacked"])
    guard let backed = map["physicallyBacked"] as? NSNumber, CFGetTypeID(backed) == CFBooleanGetTypeID() else {
      throw NativeCaptureWireError.malformed("physicallyBacked must be a bool")
    }
    let reservation = CaptureReservationLiability(
      memoryBytes: try long(map, "memoryBytes"), physicalStoreBytes: try long(map, "physicalStoreBytes"),
      componentEntries: try long(map, "componentEntries"), terminalEntries: try long(map, "terminalEntries"),
      rollbackBytes: try long(map, "rollbackBytes"), physicallyBacked: backed.boolValue)
    guard reservation.memoryBytes >= 0, reservation.physicalStoreBytes >= 0, reservation.rollbackBytes >= 0,
          (0...Int64(Int32.max)).contains(reservation.componentEntries), (0...Int64(Int32.max)).contains(reservation.terminalEntries),
          reservation.physicallyBacked else {
      throw NativeCaptureWireError.invalid("Malformed reservation")
    }
    return reservation
  }

  // MARK: Events

  /// Kotlin `NativeCaptureWireV2.event`: keys only for present values.
  static func event(_ value: NativeCaptureEvent) -> [String: Any] {
    var map: [String: Any] = ["wireVersion": wireVersion, "kind": value.kind.rawValue]
    if let v = value.attemptId { map["attemptId"] = v }
    if let v = value.captureId { map["captureId"] = v }
    if let v = value.captureRevision { map["captureRevision"] = Int(v) }
    if let v = value.manifestId { map["manifestId"] = v }
    if let v = value.heicSizeBytes { map["heicSizeBytes"] = Int(v) }
    if let v = value.jpegSizeBytes { map["jpegSizeBytes"] = Int(v) }
    if let v = value.dngSizeBytes { map["dngSizeBytes"] = Int(v) }
    if let v = value.reason { map["reason"] = v }
    if let v = value.recoveryContext { map["recoveryContext"] = recoveryContextMap(v) }
    if let v = value.shot { map["shot"] = shotMap(v) }
    if let r = value.resources {
      map["health"] = [
        "exposures": Int(r.exposures), "lateCallbacks": Int(r.lateCallbacks), "closedComponents": Int(r.closedComponents),
        "committed": Int(r.committed), "abandoned": Int(r.abandoned), "unknownQueries": Int(r.unknownQueries),
        "running": r.running, "fundedWaiting": r.fundedWaiting, "retainedImageBytes": 0, "readyPictureSets": 0,
      ] as [String: Any]
    }
    return map
  }

  static func recoveryContextMap(_ value: NativeCaptureRecoveryContext) -> [String: Any] {
    [
      "sessionId": value.sessionId, "groupId": value.groupId, "groupIndex": Int(value.groupIndex),
      "groupGeneration": Int(value.groupGeneration), "trigger": value.trigger, "requestedAtMs": Int(value.requestedAtMs),
      "coverageRevision": Int(value.coverageRevision), "timestampMs": Int(value.timestampMs), "position": value.position,
      "rotation": value.rotation, "viewMatrix": value.viewMatrix, "projectionMatrix": value.projectionMatrix,
      "groupFromWorld": value.groupFromWorld, "worldFromGroup": value.worldFromGroup,
    ]
  }

  /// The `shot` map of a committed burst terminal (iOS only; Android never
  /// sends it): shot id, mode, image count including the first image, the
  /// first image's exposure time, and
  /// per image after the first one its index, capture id, JPEG size,
  /// exposure time, size, tracking, flag bits and, when known, its
  /// column-major `T_world_cam` (OpenCV camera axes, tagged
  /// `cameraToWorldConvention: opencv_c2w_v1`) and fx, fy, cx, cy (tagged
  /// `intrinsicsConvention: opencv_pinhole_v1` when the shot record names
  /// it; untagged intrinsics are of unrecorded convention).
  static func shotMap(_ value: CommittedShot) -> [String: Any] {
    [
      "shotId": value.shotId, "mode": value.mode, "imageCount": value.imageCount,
      "firstExposureTimestampNs": Int(value.firstExposureTimestampNs),
      "images": value.images.map { image -> [String: Any] in
        var map: [String: Any] = [
          "index": image.index, "captureId": image.captureId, "jpegSizeBytes": Int(image.jpegSizeBytes),
          "exposureTimestampNs": Int(image.exposureTimestampNs), "width": image.width, "height": image.height,
          "trackingNormal": image.trackingNormal, "flags": image.flags,
        ]
        if let pose = image.cameraToWorld {
          map["cameraToWorld"] = pose
          map["cameraToWorldConvention"] = ShotRecord.cameraConvention
        }
        if let k = image.intrinsics {
          map["intrinsics"] = k
          if let convention = image.intrinsicsConvention { map["intrinsicsConvention"] = convention }
        }
        return map
      },
    ]
  }

  /// Terminal event of a durable receipt (committed or abandoned).
  static func terminalEvent(_ terminal: CaptureTerminal, recoveryContext: NativeCaptureRecoveryContext?) -> NativeCaptureEvent {
    NativeCaptureEvent(
      kind: terminal.kind == .committedPicture ? .committed : .abandoned, attemptId: terminal.identity.attemptId,
      captureId: terminal.captureId, captureRevision: terminal.captureRevision, manifestId: terminal.manifestId,
      jpegSizeBytes: terminal.jpegSizeBytes, dngSizeBytes: terminal.dngSizeBytes, heicSizeBytes: terminal.heicSizeBytes, reason: terminal.reason,
      recoveryContext: recoveryContext, shot: terminal.shot)
  }

  /// Reply of `admitNativeCaptureV2`; `terminal` is present as null when the
  /// admission is not yet terminal.
  static func admissionReply(_ receipt: CaptureReceipt, recoveryContext: NativeCaptureRecoveryContext?) -> [String: Any] {
    [
      "wireVersion": wireVersion,
      "attemptId": receipt.identity.attemptId,
      "phase": receipt.phase.wireName,
      "durable": receipt.durable,
      "terminal": receipt.terminal.map { event(terminalEvent($0, recoveryContext: recoveryContext)) as Any } ?? NSNull(),
    ]
  }

  // MARK: Scalars

  private static func map(_ value: Any?, _ name: String) throws -> [String: Any] {
    guard let dictionary = value as? [AnyHashable: Any] else { throw NativeCaptureWireError.malformed("\(name) must be a map") }
    var out: [String: Any] = [:]
    for (key, entry) in dictionary {
      guard let text = key as? String else { throw NativeCaptureWireError.malformed("\(name) keys must be strings") }
      out[text] = entry
    }
    return out
  }

  private static func exactKeys(_ map: [String: Any], _ expected: Set<String>) throws {
    guard Set(map.keys) == expected else {
      throw NativeCaptureWireError.invalid("Unexpected wire keys: expected=\(expected.sorted()) actual=\(map.keys.sorted())")
    }
  }

  private static func string(_ map: [String: Any], _ name: String) throws -> String {
    guard let value = map[name] as? String else { throw NativeCaptureWireError.malformed("\(name) must be a string") }
    return value
  }

  /// Integers only: booleans and floating point numbers are refused.
  static func integer(_ value: Any?) -> Int64? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
          !CFNumberIsFloatType(number) else { return nil }
    return number.int64Value
  }

  private static func long(_ map: [String: Any], _ name: String) throws -> Int64 {
    guard let value = integer(map[name]) else { throw NativeCaptureWireError.malformed("\(name) must be an integer") }
    return value
  }

  private static func digest(_ map: [String: Any], _ name: String) throws -> [UInt8] {
    guard let list = map[name] as? [Any] else { throw NativeCaptureWireError.malformed("\(name) must be a list") }
    var out: [UInt8] = []
    for entry in list {
      guard let value = integer(entry) else { throw NativeCaptureWireError.malformed("\(name) must contain integers") }
      guard (0...255).contains(value) else { throw NativeCaptureWireError.invalid("\(name) must contain bytes") }
      out.append(UInt8(value))
    }
    guard out.count == 32 else { throw NativeCaptureWireError.invalid("\(name) must be exactly 32 unsigned bytes") }
    return out
  }

  private static func doubles(_ map: [String: Any], _ name: String) throws -> [Double] {
    guard let list = map[name] as? [Any] else { throw NativeCaptureWireError.malformed("\(name) must be a list") }
    return try list.map { entry in
      guard let number = entry as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else {
        throw NativeCaptureWireError.malformed("\(name) must contain numbers")
      }
      return number.doubleValue
    }
  }
}
