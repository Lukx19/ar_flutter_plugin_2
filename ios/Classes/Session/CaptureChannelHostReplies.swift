import Foundation

/// Fixed replies the session host gives on `arcapture_<id>` for methods it
/// does not own but that the Dart capture sequence calls unconditionally.
/// A capture-channel extension sees each call first and may answer it instead.
enum CaptureChannelHostReplies {
  /// `ARCaptureManager` awaits `replayNativeCaptureRecoveryV2` right after the
  /// `initializeCapture` reply. iOS has no native capture recovery owner, so
  /// the replay is an empty list (Dart accepts null or a list of at most two
  /// events; not-implemented would fail capture initialization).
  static let replayNativeCaptureRecoveryV2 = "replayNativeCaptureRecoveryV2"

  /// The reply for [method], or nil when the host has none and the call must
  /// be answered `FlutterMethodNotImplemented`.
  static func reply(for method: String) -> Any? {
    switch method {
    case replayNativeCaptureRecoveryV2:
      return [Any]()
    default:
      return nil
    }
  }
}
