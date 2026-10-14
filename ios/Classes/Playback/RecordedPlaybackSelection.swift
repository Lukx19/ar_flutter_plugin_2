import Foundation

/// Every line playback logs starts with this prefix, as on Android
/// (`[ARPlayback]`), so a device log can be filtered for the replay.
enum RecordedPlaybackLog {
  static let prefix = "[ARPlayback]"

  static func write(_ message: String) {
    NSLog("%@ %@", prefix, message)
  }
}

/// How the replay continues after the recording ends (Dart `ARPlaybackRestart`).
enum RecordedPlaybackRestart: String {
  /// Flutter rebuilds the capture view; the new view replays from the start.
  case view
  /// The same source replays from the start in a new epoch.
  case session
  /// The source stays at the end of the recording.
  case off

  /// Unknown values select [view].
  static func parse(_ value: Any?) -> RecordedPlaybackRestart {
    (value as? String).flatMap { RecordedPlaybackRestart(rawValue: $0.trimmingCharacters(in: .whitespaces)) } ?? .view
  }
}

struct RecordedPlaybackConfig: Equatable {
  /// The recording folder.
  let directory: URL
  let restart: RecordedPlaybackRestart
  /// Earlier view restarts; 0 for the first view.
  let restartGeneration: Int
}

enum RecordedPlaybackSelection: Equatable {
  /// No recording was requested: the camera runs.
  case disabled
  case enabled(RecordedPlaybackConfig)
  /// A recording was requested but cannot be used; the camera runs.
  case refused(path: String, reason: String)
}

/// Debug and profile test mode: the capture view replays a recording of the
/// ARKit session recorder instead of the camera. Selected by the same
/// creation parameters as Android's ARCore playback (Dart
/// `ARPlaybackConfig.toCreationParams`, from
/// `CAPTURE3D_ARCORE_PLAYBACK_DATASET`).
enum RecordedPlaybackSelector {
  static let datasetParameter = "arcorePlaybackDataset"
  static let restartParameter = "arcorePlaybackRestart"
  static let generationParameter = "arcorePlaybackRestartGeneration"
  /// Recordings live in `Library/Application Support/recordings/<name>`;
  /// `scripts/ios/ios_device.py push-recording` copies them there.
  static let recordingsFolder = "recordings"

  /// Release builds never replay. The pod defines
  /// `CAPTURE3D_DEVELOPER_BUILD` in its Debug and Profile configurations
  /// (`capture3d/ios/Podfile`).
  static var buildAllowsPlayback: Bool {
    #if DEBUG || CAPTURE3D_DEVELOPER_BUILD
    return true
    #else
    return false
    #endif
  }

  static func defaultRecordingsDirectory() -> URL? {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
      .appendingPathComponent(recordingsFolder, isDirectory: true)
  }

  /// Resolves the creation parameters. A relative dataset path names a
  /// folder under [recordingsDirectory] (`room2`, or `recordings/room2` as
  /// on Android); an absolute path is used as given. The folder must hold a
  /// `meta.json`.
  static func select(
    creationParams: Any?,
    allowsPlayback: Bool = buildAllowsPlayback,
    recordingsDirectory: URL? = defaultRecordingsDirectory(),
    isRecording: (URL) -> Bool = { FileManager.default.fileExists(atPath: $0.appendingPathComponent("meta.json").path) }
  ) -> RecordedPlaybackSelection {
    let map = creationParams as? [String: Any]
    let requested = (map?[datasetParameter] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
    if requested.isEmpty { return .disabled }
    if !allowsPlayback { return .refused(path: requested, reason: "release-build") }
    let segments = requested.split(separator: "/", omittingEmptySubsequences: true)
    if segments.contains("..") { return .refused(path: requested, reason: "parent-segment") }
    let directory: URL
    if requested.hasPrefix("/") {
      directory = URL(fileURLWithPath: requested, isDirectory: true)
    } else {
      guard let base = recordingsDirectory else { return .refused(path: requested, reason: "no-recordings-directory") }
      let relative = segments.first == Substring(recordingsFolder) ? segments.dropFirst() : segments[...]
      guard !relative.isEmpty else { return .refused(path: requested, reason: "dataset-missing") }
      directory = relative.reduce(base) { $0.appendingPathComponent(String($1), isDirectory: true) }
    }
    guard isRecording(directory) else { return .refused(path: directory.path, reason: "dataset-missing") }
    let generation = (map?[generationParameter] as? Int) ?? 0
    return .enabled(RecordedPlaybackConfig(
      directory: directory,
      restart: RecordedPlaybackRestart.parse(map?[restartParameter]),
      restartGeneration: max(0, generation)))
  }

  /// The log line of a selection; nil when playback was not requested.
  static func describe(_ selection: RecordedPlaybackSelection) -> String? {
    switch selection {
    case .disabled:
      return nil
    case .enabled(let config):
      return "configured path=\(config.directory.path) restart=\(config.restart.rawValue) "
        + "generation=\(config.restartGeneration)"
    case .refused(let path, let reason):
      return "refused path=\(path) reason=\(reason) (live camera)"
    }
  }
}
