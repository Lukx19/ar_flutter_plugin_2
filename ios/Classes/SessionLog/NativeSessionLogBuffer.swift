import Foundation

/// Bounded buffer of native session log records, drained by the app's
/// session log (`ar_flutter_plugin_2/session_log`, method `drain`). The Swift
/// twin of the Android `NativeSessionLogBuffer`.
///
/// Applies the app's flood policy before anything is queued: the first
/// occurrence of a code and of each new variant (at most [maxVariants]) is a
/// full record; after that a code repeating faster than its interval is only
/// counted: `minFullIntervalNs` (5 per minute) for anomalies and
/// `userActionIntervalNs` (30 per minute) for the user-action codes
/// `CAPTURE_REFUSED_*`, matching the catalogue budgets. Codes with the
/// catalogue budget `always` ([alwaysCodes]) are full on every occurrence.
/// Counted occurrences leave as one `["C", code, count, lastMessage]` record
/// per drain.
///
/// Records are arrays the Dart side decodes with `SessionLogRecordCodec`:
/// `["F", code, level, message, data, variant]` (a missing variant is
/// `NSNull`, which the standard codec sends as `null`).
final class NativeSessionLogBuffer {
  static let maxVariants = 8
  /// Codes caused by a user action (catalogue budget `userAction`).
  static let userActionPrefix = "CAPTURE_REFUSED_"
  /// Native codes with the catalogue budget `always`: every occurrence is a
  /// full record (each one carries its own data, such as `heldMs`).
  static let alwaysCodes: Set<String> = [
    NativeSessionLogCodes.backgroundTaskEnded, NativeSessionLogCodes.worldMapRelocalized,
    NativeSessionLogCodes.worldMapApplied,
  ]
  static let eventsDroppedCode = "LOG_EVENTS_DROPPED"

  enum Level: String {
    case info, warning, error
  }

  private final class CodeState {
    let intervalNs: UInt64
    var lastFullNs: UInt64?
    var counted = 0
    var lastMessage = ""
    var variants: [String] = []
    init(intervalNs: UInt64) { self.intervalNs = intervalNs }
  }

  let capacity: Int
  private let minFullIntervalNs: UInt64
  private let userActionIntervalNs: UInt64
  private let nowNs: () -> UInt64
  private let lock = NSLock()
  private var records: [[Any]] = []
  /// Insertion order of codes, so counted records drain in a stable order.
  private var codeOrder: [String] = []
  private var codes: [String: CodeState] = [:]
  private var droppedFull = 0

  init(
    capacity: Int = 256,
    minFullIntervalNs: UInt64 = 12_000_000_000,
    userActionIntervalNs: UInt64 = 2_000_000_000,
    nowNs: @escaping () -> UInt64 = { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }
  ) {
    self.capacity = capacity
    self.minFullIntervalNs = minFullIntervalNs
    self.userActionIntervalNs = userActionIntervalNs
    self.nowNs = nowNs
    records.reserveCapacity(capacity)
  }

  /// Whether this occurrence should be recorded in full ([record] with `admitted`).
  func occur(_ code: String, variant: String? = nil, message: String = "") -> Bool {
    lock.lock(); defer { lock.unlock() }
    let state = stateLocked(code)
    let now = nowNs()
    var newVariant = false
    if let variant = variant, state.variants.count < Self.maxVariants, !state.variants.contains(variant) {
      state.variants.append(variant)
      newVariant = true
    }
    if let last = state.lastFullNs, !newVariant, !Self.alwaysCodes.contains(code), now &- last < state.intervalNs {
      state.counted += 1
      state.lastMessage = message
      return false
    }
    state.lastFullNs = now
    return true
  }

  /// Records one occurrence of [code]: a full record when the flood policy
  /// admits it, otherwise a count. [admitted] skips the policy (the caller
  /// already called [occur]).
  func record(
    _ code: String,
    message: String,
    data: [String: Any] = [:],
    variant: String? = nil,
    level: Level = .warning,
    admitted: Bool = false
  ) {
    if !admitted && !occur(code, variant: variant, message: message) { return }
    lock.lock(); defer { lock.unlock() }
    if records.count >= capacity {
      droppedFull += 1
      let state = stateLocked(code)
      state.counted += 1
      state.lastMessage = message
      return
    }
    records.append(["F", code, level.rawValue, message, data, variant ?? NSNull()])
  }

  /// Full records and counts since the previous drain.
  func drain() -> [[Any]] {
    lock.lock(); defer { lock.unlock() }
    var out = records
    records.removeAll(keepingCapacity: true)
    for code in codeOrder {
      guard let state = codes[code], state.counted > 0 else { continue }
      out.append(["C", code, state.counted, state.lastMessage])
      state.counted = 0
    }
    if droppedFull > 0 {
      out.append([
        "F", Self.eventsDroppedCode, Level.warning.rawValue,
        "Native session log buffer was full; records counted instead",
        ["countedInsteadOfWritten": droppedFull], NSNull(),
      ])
      droppedFull = 0
    }
    return out
  }

  /// Forgets everything (a new session).
  func reset() {
    lock.lock(); defer { lock.unlock() }
    records.removeAll(keepingCapacity: true)
    codes.removeAll()
    codeOrder.removeAll()
    droppedFull = 0
  }

  private func stateLocked(_ code: String) -> CodeState {
    if let state = codes[code] { return state }
    let interval = code.hasPrefix(Self.userActionPrefix) ? userActionIntervalNs : minFullIntervalNs
    let state = CodeState(intervalNs: interval)
    codes[code] = state
    codeOrder.append(code)
    return state
  }
}
