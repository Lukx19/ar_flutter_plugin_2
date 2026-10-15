import ARKit
import Flutter
import Foundation

/// Where the channel gets the current world map from; a fake replaces it in tests.
protocol WorldMapSource: AnyObject {
  /// Asks the running AR session for its world map; false (and no
  /// completion) when no capture view runs.
  func currentWorldMap(_ completion: @escaping (ARWorldMap?, Error?) -> Void) -> Bool
}

/// The frame source of the current capture view (`ARFrameSourceRegistry`):
/// the live session's map, or a recorded session's saved map.
final class RegistryWorldMapSource: WorldMapSource {
  func currentWorldMap(_ completion: @escaping (ARWorldMap?, Error?) -> Void) -> Bool {
    guard let source = ARFrameSourceRegistry.shared.current else { return false }
    source.getCurrentWorldMap(completion: completion)
    return true
  }
}

/// Method channel `ar_flutter_plugin_2/world_map` (iOS only). Every reply is
/// a map with a `status`, never nil; failures carry a stable `code`
/// ([WorldMapFailureCode]) and are recorded as `WORLD_MAP_FAILED` with the
/// code as variant.
///
/// - `save` `{sessionDirectory, path}`: `getCurrentWorldMap`, archived and
///   written durably to `path` (relative to the session folder). Reply
///   `path`, `sha256`, `bytes` (the manifest file reference) and the map
///   summary (`anchors`, `featurePoints`, `center`, `extent`).
/// - `load` `{sessionDirectory, path, sha256?}`: reads, verifies and decodes
///   the file and stages it as the next run's `initialWorldMap`
///   ([WorldMapInitialMapStore]). Reply `staged: true` and the summary.
/// - `clear`: drops a staged map.
/// - `status`: relocalization of the current run
///   ([WorldMapRelocalizationMonitor.statusReply]).
///
/// Archive and file work runs on a utility queue; replies go out on the main
/// thread.
final class WorldMapChannelHandler {
  static let channelName = "ar_flutter_plugin_2/world_map"

  private let source: WorldMapSource
  private let initialMaps: WorldMapInitialMapStore
  private let monitor: WorldMapRelocalizationMonitor
  private let emitter: NativeSessionLogEmitter
  private let workQueue: DispatchQueue
  private let replyQueue: DispatchQueue

  init(
    source: WorldMapSource = RegistryWorldMapSource(),
    initialMaps: WorldMapInitialMapStore = .shared,
    monitor: WorldMapRelocalizationMonitor,
    emitter: NativeSessionLogEmitter = .shared,
    workQueue: DispatchQueue = DispatchQueue(label: "capture3d.world-map", qos: .utility),
    replyQueue: DispatchQueue = .main
  ) {
    self.source = source
    self.initialMaps = initialMaps
    self.monitor = monitor
    self.emitter = emitter
    self.workQueue = workQueue
    self.replyQueue = replyQueue
  }

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    handle(method: call.method, arguments: call.arguments) { result($0) }
  }

  /// Answers one call exactly once through [reply].
  func handle(method: String, arguments: Any?, reply: @escaping ([String: Any]) -> Void) {
    let args = arguments as? [String: Any] ?? [:]
    switch method {
    case "save":
      save(args, reply: reply)
    case "load":
      load(args, reply: reply)
    case "clear":
      initialMaps.clear()
      reply(["status": "ok"])
    case "status":
      var status = monitor.statusReply()
      status["staged"] = initialMaps.hasPending
      reply(status)
    default:
      reply(["status": "notImplemented"])
    }
  }

  private func save(_ args: [String: Any], reply: @escaping ([String: Any]) -> Void) {
    let directory = args["sessionDirectory"] as? String ?? ""
    let path = args["path"] as? String ?? ""
    guard WorldMapFileStore.resolve(sessionDirectory: directory, relativePath: path) != nil else {
      reply(failure(WorldMapError(code: WorldMapFailureCode.invalidPath, message: "invalid world map path"), "save"))
      return
    }
    let requested = source.currentWorldMap { [weak self] map, error in
      guard let self = self else { return }
      self.workQueue.async {
        let response: [String: Any]
        do {
          guard let map = map else {
            throw WorldMapError(
              code: WorldMapFailureCode.notAvailable,
              message: error.map { "\($0)" } ?? "no world map"
            )
          }
          let data = try WorldMapArchive.encode(map)
          let ref = try WorldMapFileStore.write(data, sessionDirectory: directory, relativePath: path)
          var ok = WorldMapArchive.summary(map)
          ok["status"] = "ok"
          ok["path"] = ref.path
          ok["sha256"] = ref.sha256
          ok["bytes"] = ref.bytes
          response = ok
        } catch {
          response = self.failure(error, "save")
        }
        self.replyQueue.async { reply(response) }
      }
    }
    if !requested {
      reply(failure(WorldMapError(code: WorldMapFailureCode.noSession, message: "no AR session"), "save", status: "unavailable"))
    }
  }

  private func load(_ args: [String: Any], reply: @escaping ([String: Any]) -> Void) {
    let directory = args["sessionDirectory"] as? String ?? ""
    let path = args["path"] as? String ?? ""
    let sha = args["sha256"] as? String
    workQueue.async { [weak self] in
      guard let self = self else { return }
      let response: [String: Any]
      do {
        let data = try WorldMapFileStore.read(sessionDirectory: directory, relativePath: path, expectedSha256: sha)
        let map = try WorldMapArchive.decode(data)
        self.initialMaps.stage(map)
        var ok = WorldMapArchive.summary(map)
        ok["status"] = "ok"
        ok["staged"] = true
        response = ok
      } catch {
        response = self.failure(error, "load")
      }
      self.replyQueue.async { reply(response) }
    }
  }

  private func failure(_ error: Error, _ operation: String, status: String = "error") -> [String: Any] {
    let mapError = error as? WorldMapError
      ?? WorldMapError(code: WorldMapFailureCode.writeFailed, message: "\(error)")
    emitter.failure(NativeSessionLogCodes.worldMapFailed, variant: mapError.code, message: "World map \(operation) failed") {
      ["operation": operation, "code": mapError.code, "error": mapError.message]
    }
    return ["status": status, "code": mapError.code, "message": mapError.message]
  }
}
