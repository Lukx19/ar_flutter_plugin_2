import ARKit
import XCTest
@testable import ar_flutter_plugin_2

/// World map archive, durable file store, initial map staging, relocalization
/// status and the `ar_flutter_plugin_2/world_map` channel.
///
/// The archive round trip uses the world map retained with the iPhone 12 Pro
/// recording `room2-iphone12pro-20261014` (Git LFS, 7 MB, in the app
/// repository next to this submodule). The Runner project bundles that file
/// into RunnerTests as `worldmap.arworldmap`, so a device run reads it from
/// the test bundle. `ARWorldMap` has no public initializer, so the tests that
/// need a real map skip on the simulator, whose ARKit stub refuses to code
/// `ARWorldMap`; on a device a missing fixture or an unpulled LFS pointer
/// fails them (`git lfs pull --include` the file before building).
final class WorldMapTests: XCTestCase {
  private var sessionDirectory: URL!

  override func setUpWithError() throws {
    sessionDirectory = FileManager.default.temporaryDirectory
      .appendingPathComponent("world-map-tests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: sessionDirectory, withIntermediateDirectories: true)
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: sessionDirectory)
  }

  static let recordedWorldMapResource = "worldmap.arworldmap"

  /// The bundled fixture, then (simulator builds only) the source tree.
  static var recordedWorldMapURL: URL? {
    for bundle in Bundle.allBundles + [Bundle.main] {
      if let url = bundle.url(forResource: recordedWorldMapResource, withExtension: nil) {
        return url
      }
    }
    #if targetEnvironment(simulator)
    var root = URL(fileURLWithPath: #filePath)
    for _ in 0..<6 { root.deleteLastPathComponent() }
    return root.appendingPathComponent(
      "benchmarks/visibility_grid/recordings/ios/room2-iphone12pro-20261014/\(recordedWorldMapResource)")
    #else
    return nil
    #endif
  }

  private struct MissingFixture: LocalizedError {
    let errorDescription: String?
  }

  private func recordedWorldMapData() throws -> Data {
    // The simulator's ARKit is a stub whose ARWorldMap refuses secure
    // coding; these tests run on a device (`ios_device.py xctest --device`),
    // where a missing fixture is a failure, never a skip.
    guard ARWorldTrackingConfiguration.isSupported, ARWorldMap.supportsSecureCoding else {
      throw XCTSkip("ARWorldMap coding needs ARKit world tracking (a device, not the simulator)")
    }
    guard let url = Self.recordedWorldMapURL, let data = try? Data(contentsOf: url) else {
      throw MissingFixture(errorDescription:
        "recorded world map \(Self.recordedWorldMapResource) is not in the RunnerTests bundle")
    }
    if data.count < 1024, String(decoding: data, as: UTF8.self).hasPrefix("version https://git-lfs") {
      throw MissingFixture(errorDescription:
        "recorded world map is a Git LFS pointer; run git lfs pull and rebuild the tests")
    }
    return data
  }

  // MARK: Archive and file store

  func testRecordedWorldMapRoundTripsThroughTheArchiveAndTheStore() throws {
    let recorded = try recordedWorldMapData()
    let map = try WorldMapArchive.decode(recorded)
    XCTAssertGreaterThan(map.rawFeaturePoints.points.count, 0)

    let encoded = try WorldMapArchive.encode(map)
    let ref = try WorldMapFileStore.write(
      encoded, sessionDirectory: sessionDirectory.path, relativePath: "groups/g1/\(WorldMapFileStore.fileName)")
    XCTAssertEqual(ref.path, "groups/g1/world_map.arworldmap")
    XCTAssertEqual(ref.bytes, encoded.count)
    XCTAssertEqual(ref.sha256, WorldMapArchive.sha256Hex(encoded))
    XCTAssertEqual(ref.sha256.count, 64)

    let read = try WorldMapFileStore.read(
      sessionDirectory: sessionDirectory.path, relativePath: ref.path, expectedSha256: ref.sha256.uppercased())
    let decoded = try WorldMapArchive.decode(read)
    XCTAssertEqual(decoded.anchors.count, map.anchors.count)
    XCTAssertEqual(decoded.rawFeaturePoints.points.count, map.rawFeaturePoints.points.count)
    XCTAssertEqual(decoded.center, map.center)
    XCTAssertEqual(decoded.extent, map.extent)
    let summary = WorldMapArchive.summary(decoded)
    XCTAssertEqual(summary["featurePoints"] as? Int, map.rawFeaturePoints.points.count)

    let leftovers = try FileManager.default.contentsOfDirectory(
      atPath: sessionDirectory.appendingPathComponent("groups/g1").path)
    XCTAssertEqual(leftovers, ["world_map.arworldmap"], "no temporary file remains")
  }

  func testStoreRejectsPathsOutsideTheSessionFolderAndBadChecksums() throws {
    for path in ["", "/abs/world_map", "../world_map", "groups/../../x", "groups//x", "./x", "groups/."] {
      XCTAssertNil(WorldMapFileStore.resolve(sessionDirectory: sessionDirectory.path, relativePath: path), path)
      XCTAssertThrowsError(try WorldMapFileStore.write(Data([1]), sessionDirectory: sessionDirectory.path, relativePath: path)) {
        XCTAssertEqual(($0 as? WorldMapError)?.code, WorldMapFailureCode.invalidPath)
      }
    }
    XCTAssertNil(WorldMapFileStore.resolve(sessionDirectory: "relative", relativePath: "x"))

    let ref = try WorldMapFileStore.write(Data("not a map".utf8), sessionDirectory: sessionDirectory.path, relativePath: "groups/g/w")
    let replaced = try WorldMapFileStore.write(Data("second".utf8), sessionDirectory: sessionDirectory.path, relativePath: "groups/g/w")
    XCTAssertNotEqual(ref.sha256, replaced.sha256, "rename replaces the earlier file")
    XCTAssertThrowsError(try WorldMapFileStore.read(sessionDirectory: sessionDirectory.path, relativePath: "groups/g/w", expectedSha256: ref.sha256)) {
      XCTAssertEqual(($0 as? WorldMapError)?.code, WorldMapFailureCode.checksumMismatch)
    }
    let data = try WorldMapFileStore.read(sessionDirectory: sessionDirectory.path, relativePath: "groups/g/w", expectedSha256: nil)
    XCTAssertThrowsError(try WorldMapArchive.decode(data)) {
      XCTAssertEqual(($0 as? WorldMapError)?.code, WorldMapFailureCode.decodeFailed)
    }
    XCTAssertThrowsError(try WorldMapFileStore.read(sessionDirectory: sessionDirectory.path, relativePath: "missing", expectedSha256: nil)) {
      XCTAssertEqual(($0 as? WorldMapError)?.code, WorldMapFailureCode.readFailed)
    }
  }

  // MARK: Initial map and relocalization

  func testStagedMapIsAppliedToOneWorldTrackingRun() throws {
    let map = try WorldMapArchive.decode(try recordedWorldMapData())
    let store = WorldMapInitialMapStore()
    XCTAssertFalse(store.apply(to: ARWorldTrackingConfiguration()), "nothing staged")
    store.stage(map)
    XCTAssertFalse(store.apply(to: AROrientationTrackingConfiguration()), "world tracking only")
    let configuration = ARWorldTrackingConfiguration()
    XCTAssertTrue(store.apply(to: configuration))
    XCTAssertTrue(configuration.initialWorldMap === map)
    XCTAssertFalse(store.hasPending, "a run consumes the map")
    store.stage(map)
    store.clear()
    XCTAssertFalse(store.apply(to: ARWorldTrackingConfiguration()))
  }

  func testRelocalizationReportsTheWorldMapHintOnceTrackingIsNormal() {
    var now: UInt64 = 0
    let emitter = NativeSessionLogEmitter()
    emitter.setActive(true)
    let monitor = WorldMapRelocalizationMonitor(emitter: emitter, nowNs: { now })
    XCTAssertEqual(monitor.statusReply()["relocalization"] as? String, "none")

    monitor.runStarted(withInitialWorldMap: true, epoch: 3)
    monitor.trackingChanged(.limited(.relocalizing))
    XCTAssertEqual(monitor.currentState, .relocalizing)
    XCTAssertNil(monitor.statusReply()["transformSource"])
    now = 2_400_000_000
    monitor.trackingChanged(.normal)
    let status = monitor.statusReply()
    XCTAssertEqual(status["status"] as? String, "ok")
    XCTAssertEqual(status["relocalization"] as? String, "relocalized")
    XCTAssertEqual(status["transformSource"] as? String, "worldMapHint")
    XCTAssertEqual(status["relocalizedMs"] as? Int, 2400)
    XCTAssertEqual(status["epoch"] as? Int, 3)
    monitor.trackingChanged(.normal)
    let records = emitter.buffer.drain()
    XCTAssertEqual(records.map { $0[1] as? String }, ["WORLD_MAP_RELOCALIZED"])
    XCTAssertEqual(records.first?[2] as? String, "info")

    monitor.runStarted(withInitialWorldMap: false, epoch: 4)
    monitor.trackingChanged(.normal)
    XCTAssertEqual(monitor.statusReply()["relocalization"] as? String, "none", "a run without a map is no relocalization")
  }

  // MARK: Channel

  func testChannelRepliesAreNeverNil() {
    let emitter = NativeSessionLogEmitter()
    emitter.setActive(true)
    let source = FakeWorldMapSource()
    let handler = WorldMapChannelHandler(
      source: source, initialMaps: WorldMapInitialMapStore(),
      monitor: WorldMapRelocalizationMonitor(emitter: emitter), emitter: emitter,
      workQueue: DispatchQueue(label: "test.world-map"), replyQueue: DispatchQueue(label: "test.reply")
    )
    func call(_ method: String, _ args: Any?) -> [String: Any] {
      let done = expectation(description: method)
      var reply: [String: Any] = [:]
      handler.handle(method: method, arguments: args) { reply = $0; done.fulfill() }
      wait(for: [done], timeout: 10)
      return reply
    }
    let dir = sessionDirectory.path

    XCTAssertEqual(call("save", ["sessionDirectory": dir, "path": "groups/g/w"])["code"] as? String, WorldMapFailureCode.noSession)
    XCTAssertEqual(call("save", ["sessionDirectory": dir, "path": "groups/g/w"])["status"] as? String, "unavailable")
    XCTAssertEqual(call("save", ["sessionDirectory": dir, "path": "../w"])["code"] as? String, WorldMapFailureCode.invalidPath)
    XCTAssertEqual(call("save", nil)["status"] as? String, "error")
    source.running = true
    source.error = NSError(domain: ARErrorDomain, code: ARError.Code.insufficientFeatures.rawValue)
    let notReady = call("save", ["sessionDirectory": dir, "path": "groups/g/w"])
    XCTAssertEqual(notReady["code"] as? String, WorldMapFailureCode.notAvailable)
    XCTAssertEqual(notReady["status"] as? String, "error")
    XCTAssertEqual(call("load", ["sessionDirectory": dir, "path": "missing"])["code"] as? String, WorldMapFailureCode.readFailed)
    XCTAssertEqual(call("clear", nil)["status"] as? String, "ok")
    let status = call("status", nil)
    XCTAssertEqual(status["relocalization"] as? String, "none")
    XCTAssertEqual(status["staged"] as? Bool, false)
    XCTAssertEqual(call("unknown", nil)["status"] as? String, "notImplemented")

    let failures = emitter.buffer.drain().filter { $0[1] as? String == "WORLD_MAP_FAILED" && $0[0] as? String == "F" }
    XCTAssertEqual(
      Set(failures.compactMap { $0[5] as? String }),
      [WorldMapFailureCode.noSession, WorldMapFailureCode.invalidPath, WorldMapFailureCode.notAvailable, WorldMapFailureCode.readFailed]
    )
  }

  func testChannelSavesAndStagesTheRecordedMap() throws {
    let map = try WorldMapArchive.decode(try recordedWorldMapData())
    let source = FakeWorldMapSource()
    source.running = true
    source.map = map
    let staging = WorldMapInitialMapStore()
    let handler = WorldMapChannelHandler(
      source: source, initialMaps: staging, monitor: WorldMapRelocalizationMonitor(emitter: NativeSessionLogEmitter()),
      emitter: NativeSessionLogEmitter(),
      workQueue: DispatchQueue(label: "test.world-map"), replyQueue: DispatchQueue(label: "test.reply")
    )
    func call(_ method: String, _ args: Any?) -> [String: Any] {
      let done = expectation(description: method)
      var reply: [String: Any] = [:]
      handler.handle(method: method, arguments: args) { reply = $0; done.fulfill() }
      wait(for: [done], timeout: 60)
      return reply
    }
    let saved = call("save", ["sessionDirectory": sessionDirectory.path, "path": "groups/g/world_map.arworldmap"])
    XCTAssertEqual(saved["status"] as? String, "ok")
    XCTAssertEqual(saved["path"] as? String, "groups/g/world_map.arworldmap")
    let sha = try XCTUnwrap(saved["sha256"] as? String)
    XCTAssertEqual(saved["featurePoints"] as? Int, map.rawFeaturePoints.points.count)

    let wrong = call("load", ["sessionDirectory": sessionDirectory.path, "path": "groups/g/world_map.arworldmap", "sha256": String(repeating: "0", count: 64)])
    XCTAssertEqual(wrong["code"] as? String, WorldMapFailureCode.checksumMismatch)
    XCTAssertFalse(staging.hasPending)
    let loaded = call("load", ["sessionDirectory": sessionDirectory.path, "path": "groups/g/world_map.arworldmap", "sha256": sha])
    XCTAssertEqual(loaded["status"] as? String, "ok")
    XCTAssertEqual(loaded["staged"] as? Bool, true)
    XCTAssertTrue(staging.hasPending)
    XCTAssertEqual(call("status", nil)["staged"] as? Bool, true)
  }
}

private final class FakeWorldMapSource: WorldMapSource {
  var running = false
  var map: ARWorldMap?
  var error: Error?

  func currentWorldMap(_ completion: @escaping (ARWorldMap?, Error?) -> Void) -> Bool {
    guard running else { return false }
    let map = self.map, error = self.error
    DispatchQueue.global().async { completion(map, error) }
    return true
  }
}
