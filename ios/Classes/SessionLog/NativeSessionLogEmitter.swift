import Foundation

/// Native side of the app's per-session audit log
/// (`ar_flutter_plugin_2/session_log`), the Swift twin of the Android
/// `NativeSessionLogEmitter`. Native producers call [record] (or [occur] then
/// [record] with `admitted` on hot paths); the app drains the records every
/// 2 s and on pause and close. Records are kept only while a session log is
/// open ([setActive]).
final class NativeSessionLogEmitter {
  static let shared = NativeSessionLogEmitter()

  /// Recorded when a channel handler body throws.
  static let nativeHandlerErrorCode = "NATIVE_HANDLER_ERROR"

  let buffer: NativeSessionLogBuffer
  private let lock = NSLock()
  private var isActive = false
  private var generation = 0

  init(buffer: NativeSessionLogBuffer = NativeSessionLogBuffer()) {
    self.buffer = buffer
  }

  var active: Bool {
    lock.lock(); defer { lock.unlock() }
    return isActive
  }

  /// Increments each time a record window opens; counters re-baseline on a change.
  var activeGeneration: Int {
    lock.lock(); defer { lock.unlock() }
    return generation
  }

  /// Opens or closes the record window; a change forgets pending records.
  func setActive(_ on: Bool) {
    lock.lock()
    let changed = on != isActive
    if on && !isActive { generation += 1 }
    isActive = on
    lock.unlock()
    if changed { buffer.reset() }
  }

  func occur(_ code: String, variant: String? = nil, message: String = "") -> Bool {
    active && buffer.occur(code, variant: variant, message: message)
  }

  func record(
    _ code: String,
    message: String,
    data: [String: Any] = [:],
    variant: String? = nil,
    level: NativeSessionLogBuffer.Level = .warning,
    admitted: Bool = false
  ) {
    guard active else { return }
    buffer.record(code, message: message, data: data, variant: variant, level: level, admitted: admitted)
  }

  /// One occurrence of a rare native failure ([code], [variant]): a full
  /// record when the flood policy admits it, otherwise counted. [data] is
  /// built only for an admitted record.
  func failure(
    _ code: String,
    variant: String?,
    message: String,
    level: NativeSessionLogBuffer.Level = .warning,
    data: () -> [String: Any]
  ) {
    guard active, buffer.occur(code, variant: variant, message: message) else { return }
    buffer.record(code, message: message, data: data(), variant: variant, level: level, admitted: true)
  }

  /// Runs a channel handler body; a thrown error becomes a
  /// `NATIVE_HANDLER_ERROR` record and a status map reply (`status: error`)
  /// instead of a crash, a nil or an error reply.
  func guarded(channel: String, method: String, _ body: () throws -> [String: Any]) -> [String: Any] {
    do {
      return try body()
    } catch {
      let errorType = String(describing: type(of: error))
      let detail = String(describing: error)
      let variant = "\(channel)/\(method)/\(errorType)"
      failure(Self.nativeHandlerErrorCode, variant: variant, message: "Native handler failed: \(errorType)") {
        ["channel": channel, "method": method, "error": detail]
      }
      return ["status": "error", "code": Self.nativeHandlerErrorCode, "message": detail]
    }
  }
}
