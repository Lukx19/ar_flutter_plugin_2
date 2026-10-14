import Flutter
import Foundation
import UIKit

/// Presents exported session log files to the user.
protocol SessionLogSharing: AnyObject {
  /// Shows the share sheet for [files]; false when nothing could present it.
  func share(_ files: [URL]) -> Bool
}

/// The export folders the app writes session log copies into
/// (`session_log_export/<sessionId>/`). Dart uses `getTemporaryDirectory`,
/// which is `Library/Caches` on iOS; `tmp/` is accepted too.
enum SessionLogExportRoots {
  static let folderName = "session_log_export"

  static func current(fileManager: FileManager = .default) -> [URL] {
    var roots: [URL] = []
    if let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first {
      roots.append(caches.appendingPathComponent(folderName, isDirectory: true))
    }
    roots.append(URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
      .appendingPathComponent(folderName, isDirectory: true))
    return roots
  }

  /// Whether [path] names a file inside one of [roots] (a sibling folder
  /// sharing a root's name as a prefix does not count). Both sides are
  /// resolved, so `..` and symbolic links cannot leave the root.
  static func isInside(_ path: String, roots: [URL]) -> Bool {
    let resolved = canonical(URL(fileURLWithPath: path))
    return roots.contains { root in
      let base = canonical(root)
      return resolved.hasPrefix(base + "/") && resolved.count > base.count + 1
    }
  }

  private static func canonical(_ url: URL) -> String {
    url.standardizedFileURL.resolvingSymlinksInPath().path
  }
}

/// Method channel handler of `ar_flutter_plugin_2/session_log`. Every reply
/// is a map with a `status`, never nil:
///
/// - `identity`: `arVersion` (`ARKit iOS <version>`; ARKit has no version of
///   its own, it ships with the OS)
/// - `setActive` `{active}`: opens or closes the native record window
/// - `drain`: `records` since the previous drain
/// - `share` `{paths}`: share sheet for exported files; `unavailable` when a
///   path is outside the export folder, missing, or nothing can present
/// - `debugThrow` (debug builds): forces a handler failure to test the guard
final class SessionLogChannelHandler {
  static let channelName = "ar_flutter_plugin_2/session_log"

  private let emitter: NativeSessionLogEmitter
  private let sharer: SessionLogSharing
  private let exportRoots: () -> [URL]
  private let debugBuild: Bool
  private let osVersion: String

  init(
    emitter: NativeSessionLogEmitter = .shared,
    sharer: SessionLogSharing = ActivitySheetSessionLogSharer(),
    exportRoots: @escaping () -> [URL] = { SessionLogExportRoots.current() },
    debugBuild: Bool = SessionLogChannelHandler.isDebugBuild,
    osVersion: String = UIDevice.current.systemVersion
  ) {
    self.emitter = emitter
    self.sharer = sharer
    self.exportRoots = exportRoots
    self.debugBuild = debugBuild
    self.osVersion = osVersion
  }

  static var isDebugBuild: Bool {
    #if DEBUG
      return true
    #else
      return false
    #endif
  }

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    result(reply(method: call.method, arguments: call.arguments))
  }

  /// The reply map of one call (exposed for tests).
  func reply(method: String, arguments: Any?) -> [String: Any] {
    emitter.guarded(channel: Self.channelName, method: method) {
      let args = arguments as? [String: Any] ?? [:]
      switch method {
      case "identity":
        return ["status": "ok", "arVersion": "ARKit iOS \(osVersion)"]
      case "setActive":
        emitter.setActive(args["active"] as? Bool == true)
        return ["status": "ok"]
      case "drain":
        return ["status": "ok", "records": emitter.buffer.drain()]
      case "debugThrow":
        guard debugBuild else { return ["status": "notImplemented"] }
        throw SessionLogChannelError.forcedHandlerFailure
      case "share":
        let paths = args["paths"] as? [String] ?? []
        return ["status": share(paths) ? "ok" : "unavailable"]
      default:
        return ["status": "notImplemented"]
      }
    }
  }

  private func share(_ paths: [String]) -> Bool {
    let roots = exportRoots()
    var files: [URL] = []
    for path in paths {
      var isDirectory: ObjCBool = false
      guard SessionLogExportRoots.isInside(path, roots: roots),
            FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory),
            !isDirectory.boolValue
      else { return false }
      files.append(URL(fileURLWithPath: path))
    }
    if files.isEmpty { return false }
    return sharer.share(files)
  }
}

enum SessionLogChannelError: Error {
  /// `debugThrow`: tests the handler guard.
  case forcedHandlerFailure
}

/// Presents a `UIActivityViewController` from the top view controller of the
/// foreground key window (on an iPad anchored to the window centre).
final class ActivitySheetSessionLogSharer: SessionLogSharing {
  func share(_ files: [URL]) -> Bool {
    guard Thread.isMainThread, let presenter = Self.topViewController() else { return false }
    let sheet = UIActivityViewController(activityItems: files, applicationActivities: nil)
    if let popover = sheet.popoverPresentationController {
      popover.sourceView = presenter.view
      popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0)
      popover.permittedArrowDirections = []
    }
    presenter.present(sheet, animated: true)
    return true
  }

  private static func topViewController() -> UIViewController? {
    let window = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .filter { $0.activationState == .foregroundActive }
      .flatMap { $0.windows }
      .first { $0.isKeyWindow }
    var top = window?.rootViewController
    while let presented = top?.presentedViewController, !presented.isBeingDismissed {
      top = presented
    }
    return top
  }
}
