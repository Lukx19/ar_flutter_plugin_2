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
    XCTAssertEqual(records.map { $0[1] as? String }, ["WORLD_MAP_APPLIED", "WORLD_MAP_RELOCALIZED"])
    XCTAssertEqual(records.last?[2] as? String, "info")

    monitor.runStarted(withInitialWorldMap: false, epoch: 4)
    monitor.trackingChanged(.normal)
    XCTAssertEqual(monitor.statusReply()["relocalization"] as? String, "none", "a run without a map is no relocalization")
  }

  func testRelocalizationStatusNamesTheTrackingAndRecordsAnAppliedMap() {
    let emitter = NativeSessionLogEmitter()
    emitter.setActive(true)
    let monitor = WorldMapRelocalizationMonitor(emitter: emitter, nowNs: { 0 })
    XCTAssertEqual(monitor.statusReply()["tracking"] as? String, "notAvailable")
    XCTAssertEqual(monitor.statusReply()["relocalizingReported"] as? Bool, false)

    monitor.runStarted(withInitialWorldMap: true, epoch: 2)
    monitor.trackingChanged(.limited(.initializing))
    XCTAssertEqual(monitor.statusReply()["tracking"] as? String, "initializing")
    XCTAssertEqual(monitor.statusReply()["relocalizingReported"] as? Bool, false)
    monitor.trackingChanged(.limited(.relocalizing))
    let relocalizing = monitor.statusReply()
    XCTAssertEqual(relocalizing["tracking"] as? String, "relocalizing")
    XCTAssertEqual(relocalizing["relocalizingReported"] as? Bool, true)
    XCTAssertEqual(relocalizing["relocalization"] as? String, "relocalizing")
    let records = emitter.buffer.drain()
    XCTAssertEqual(records.map { $0[1] as? String }, ["WORLD_MAP_APPLIED"])
    XCTAssertEqual((records.first?[4] as? [String: Any])?["epoch"] as? Int, 2)

    monitor.runStarted(withInitialWorldMap: false, epoch: 3)
    let fresh = monitor.statusReply()
    XCTAssertEqual(fresh["tracking"] as? String, "notAvailable", "a new run forgets the earlier tracking")
    XCTAssertEqual(fresh["relocalizingReported"] as? Bool, false)
    XCTAssertTrue(emitter.buffer.drain().isEmpty, "a run without a map records nothing")
  }

  /// The run reports the path of the map it started from, so the app can
  /// name the saved group whose world frame it relocalizes into; a run
  /// without a map, or a later run, names none.
  func testRelocalizationStatusNamesTheAppliedMapPath() {
    let monitor = WorldMapRelocalizationMonitor(emitter: NativeSessionLogEmitter(), nowNs: { 0 })
    let path = "session-a/groups/group-1/world_map-4.arworldmap"
    monitor.runStarted(withInitialWorldMap: true, epoch: 2, mapPath: path)
    XCTAssertEqual(monitor.statusReply()["mapPath"] as? String, path, "relocalizing")
    monitor.trackingChanged(.normal)
    XCTAssertEqual(monitor.statusReply()["mapPath"] as? String, path, "relocalized")
    monitor.runStarted(withInitialWorldMap: false, epoch: 3, mapPath: path)
    XCTAssertNil(monitor.statusReply()["mapPath"], "a run without a map")
    monitor.runStarted(withInitialWorldMap: true, epoch: 4)
    XCTAssertNil(monitor.statusReply()["mapPath"], "a map staged without a path")
  }

  /// The staged map's path travels with it: the run that applies it carries
  /// the path in its configuration; a clear or a later stage drops it.
  func testAppliedMapCarriesTheLoadedPathIntoTheRunConfiguration() throws {
    let map = try WorldMapArchive.decode(try recordedWorldMapData())
    let staging = WorldMapInitialMapStore()
    let path = "session-a/groups/group-1/world_map-4.arworldmap"
    let generation = staging.claim()
    XCTAssertTrue(staging.stage(map, generation: generation, source: path))
    let driver = ConfigurationRecordingDriver()
    let host = ARSessionHost(
      plan: Self.hostPlan, driver: driver, registry: nil,
      makeConfiguration: { ARWorldTrackingConfiguration() }, initialMaps: staging, clock: { 0 })
    let observer = StartRecordingObserver()
    host.addFrameObserver(observer)
    host.start()
    XCTAssertEqual(host.runConfiguration?.initialWorldMapSource, path)
    host.restart()
    XCTAssertNil(host.runConfiguration?.initialWorldMapSource, "the next run starts without a map")
    staging.stage(map, generation: staging.claim(), source: path)
    staging.stage(map)
    host.restart()
    XCTAssertEqual(host.runConfiguration?.hasInitialWorldMap, true)
    XCTAssertNil(host.runConfiguration?.initialWorldMapSource, "the later stage named no path")
    host.delegateQueue.sync {}
    host.dispose()
  }

  // MARK: Session host

  /// The capture session host applies a staged map to the next run that
  /// starts a new world frame (start or restart), never to a resume, and
  /// observers learn from `didStart` that the run relocalizes.
  func testSessionHostAppliesTheStagedMapOnlyToARunThatResetsTracking() throws {
    let map = try WorldMapArchive.decode(try recordedWorldMapData())
    let staging = WorldMapInitialMapStore()
    let driver = ConfigurationRecordingDriver()
    let host = ARSessionHost(
      plan: Self.hostPlan, driver: driver, registry: nil,
      makeConfiguration: { ARWorldTrackingConfiguration() }, initialMaps: staging, clock: { 0 })
    let observer = StartRecordingObserver()
    host.addFrameObserver(observer)

    host.start()
    XCTAssertNil(driver.worldMaps.last!, "nothing staged: no initial map")
    staging.stage(map)
    host.pause()
    host.resume()
    XCTAssertNil(driver.worldMaps.last!, "a resume keeps the current world frame")
    XCTAssertTrue(staging.hasPending, "a resume leaves the map for the next restart")
    host.restart()
    XCTAssertTrue(driver.worldMaps.last! === map)
    XCTAssertEqual(driver.resets, [true, false, true])
    XCTAssertFalse(staging.hasPending, "the restart consumed the map")
    XCTAssertEqual(host.runConfiguration?.hasInitialWorldMap, true)
    host.restart()
    XCTAssertNil(driver.worldMaps.last!, "one run only")
    XCTAssertEqual(host.runConfiguration?.hasInitialWorldMap, false)

    host.delegateQueue.sync {}
    XCTAssertEqual(observer.starts.map { $0.epoch }, [1, 1, 2, 3])
    XCTAssertEqual(observer.starts.map { $0.map }, [false, false, true, false])
    host.dispose()
  }

  func testSessionHostWithoutStagingNeverAppliesAMap() throws {
    let map = try WorldMapArchive.decode(try recordedWorldMapData())
    WorldMapInitialMapStore.shared.clear()
    let driver = ConfigurationRecordingDriver()
    let host = ARSessionHost(
      plan: Self.hostPlan, driver: driver, registry: nil,
      makeConfiguration: { ARWorldTrackingConfiguration() }, initialMaps: nil, clock: { 0 })
    WorldMapInitialMapStore.shared.stage(map)
    host.start()
    XCTAssertNil(driver.worldMaps.last!)
    XCTAssertTrue(WorldMapInitialMapStore.shared.hasPending)
    WorldMapInitialMapStore.shared.clear()
    host.dispose()
  }

  /// A recorded session cannot relocalize: its restart discards a staged
  /// map, so the map never reaches a later live run.
  func testRecordedSessionDiscardsAStagedMap() throws {
    let map = try WorldMapArchive.decode(try recordedWorldMapData())
    let directory = try RecordedSessionFixture.room2URL()
    let staging = WorldMapInitialMapStore()
    let source = RecordedFrameSource(
      session: try RecordedSession(directory: directory),
      config: RecordedPlaybackConfig(directory: directory, restart: .off, restartGeneration: 0),
      options: ARSessionOptions(), registry: nil, initialMaps: staging)
    defer { source.dispose() }
    staging.stage(map)
    source.start()
    XCTAssertFalse(staging.hasPending)
    XCTAssertEqual(source.runConfiguration?.hasInitialWorldMap, false)
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

  /// The services re-register (the handler is released) while a save waits
  /// for the session's world map and while a load reads: both still reply.
  func testReleasedHandlerStillRepliesToCallsInFlight() {
    let source = DeferredWorldMapSource()
    let emitter = NativeSessionLogEmitter()
    var handler: WorldMapChannelHandler? = WorldMapChannelHandler(
      source: source, initialMaps: WorldMapInitialMapStore(),
      monitor: WorldMapRelocalizationMonitor(emitter: emitter), emitter: emitter,
      workQueue: DispatchQueue(label: "test.world-map"), replyQueue: DispatchQueue(label: "test.reply")
    )
    weak var released = handler
    let saved = expectation(description: "save reply")
    let loaded = expectation(description: "load reply")
    var saveReply: [String: Any] = [:]
    var loadReply: [String: Any] = [:]
    let dir = sessionDirectory.path
    handler?.handle(method: "save", arguments: ["sessionDirectory": dir, "path": "groups/g/w"]) { saveReply = $0; saved.fulfill() }
    handler?.handle(method: "load", arguments: ["sessionDirectory": dir, "path": "missing"]) { loadReply = $0; loaded.fulfill() }
    handler = nil
    source.complete(nil, NSError(domain: ARErrorDomain, code: ARError.Code.insufficientFeatures.rawValue))
    wait(for: [saved, loaded], timeout: 10)
    XCTAssertEqual(saveReply["code"] as? String, WorldMapFailureCode.notAvailable)
    XCTAssertEqual(loadReply["code"] as? String, WorldMapFailureCode.readFailed)
    let deadline = Date().addingTimeInterval(2)
    while released != nil && Date() < deadline { usleep(10_000) }
    XCTAssertNil(released, "the handler goes away once the calls replied")
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
    XCTAssertEqual(loaded["restarted"] as? Bool, false, "no restart asked")
    XCTAssertEqual(source.restarts, 0)
    XCTAssertTrue(staging.hasPending)
    XCTAssertEqual(call("status", nil)["staged"] as? Bool, true)

    let args: [String: Any] = [
      "sessionDirectory": sessionDirectory.path, "path": "groups/g/world_map.arworldmap", "sha256": sha,
      "restartSession": true,
    ]
    source.restartEpoch = nil
    let idle = call("load", args)
    XCTAssertEqual(idle["restarted"] as? Bool, false, "no running session: the next start applies it")
    XCTAssertNil(idle["epoch"])
    source.restartEpoch = 4
    let restarted = call("load", args)
    XCTAssertEqual(restarted["status"] as? String, "ok")
    XCTAssertEqual(restarted["restarted"] as? Bool, true)
    XCTAssertEqual(restarted["epoch"] as? Int, 4)
    XCTAssertEqual(source.restarts, 2)

    source.restartEpoch = 5
    let failed = call("load", ["sessionDirectory": sessionDirectory.path, "path": "missing", "restartSession": true])
    XCTAssertEqual(failed["code"] as? String, WorldMapFailureCode.readFailed)
    XCTAssertEqual(source.restarts, 2, "a failed load never restarts the session")
  }

  // MARK: Load ownership and the restart reply

  private func ownershipHandler(
    _ source: FakeWorldMapSource, _ staging: WorldMapInitialMapStore,
    workQueue: DispatchQueue, replyQueue: DispatchQueue
  ) -> WorldMapChannelHandler {
    WorldMapChannelHandler(
      source: source, initialMaps: staging,
      monitor: WorldMapRelocalizationMonitor(emitter: NativeSessionLogEmitter()),
      emitter: NativeSessionLogEmitter(), workQueue: workQueue, replyQueue: replyQueue)
  }

  /// Writes the recorded map into the session folder; returns its path and SHA-256.
  private func writeRecordedMap() throws -> (args: [String: Any], map: ARWorldMap) {
    let data = try recordedWorldMapData()
    let map = try WorldMapArchive.decode(data)
    let ref = try WorldMapFileStore.write(
      data, sessionDirectory: sessionDirectory.path, relativePath: "groups/g/world_map.arworldmap")
    let args: [String: Any] = [
      "sessionDirectory": sessionDirectory.path, "path": ref.path, "sha256": ref.sha256,
      "restartSession": true,
    ]
    return (args, map)
  }

  /// A session start on the main thread takes the staged map between the
  /// load's staging and its reply: the reply restarts nothing (a second
  /// `.resetTracking` would drop the map) and says the start applied it.
  func testALoadWhoseMapASessionStartTookFirstNeverRestarts() throws {
    let (args, _) = try writeRecordedMap()
    let source = FakeWorldMapSource()
    source.restartEpoch = 3
    let staging = WorldMapInitialMapStore()
    let replyQueue = DispatchQueue(label: "test.reply")
    let handler = ownershipHandler(
      source, staging, workQueue: DispatchQueue(label: "test.world-map"), replyQueue: replyQueue)
    replyQueue.suspend()
    let done = expectation(description: "load")
    var reply: [String: Any] = [:]
    handler.handle(method: "load", arguments: args) { reply = $0; done.fulfill() }
    let deadline = Date().addingTimeInterval(30)
    while !staging.hasPending && Date() < deadline { usleep(10_000) }
    XCTAssertTrue(staging.hasPending, "the load staged the map")
    let configuration = ARWorldTrackingConfiguration()
    XCTAssertTrue(staging.apply(to: configuration), "the start takes the staged map")
    replyQueue.resume()
    wait(for: [done], timeout: 30)
    XCTAssertEqual(reply["status"] as? String, "ok")
    XCTAssertEqual(reply["restarted"] as? Bool, false)
    XCTAssertEqual(reply["appliedByStart"] as? Bool, true)
    XCTAssertNil(reply["epoch"])
    XCTAssertEqual(source.restarts, 0, "no second reset that drops the map")
    XCTAssertNotNil(configuration.initialWorldMap)
  }

  /// A `clear` (a new session) that arrives while a continuation's load
  /// still reads: the load stages nothing and restarts nothing.
  func testAClearDuringALoadSupersedesIt() throws {
    let (args, _) = try writeRecordedMap()
    let source = FakeWorldMapSource()
    source.restartEpoch = 3
    let staging = WorldMapInitialMapStore()
    let workQueue = DispatchQueue(label: "test.world-map")
    let handler = ownershipHandler(source, staging, workQueue: workQueue, replyQueue: DispatchQueue(label: "test.reply"))
    workQueue.suspend()
    let done = expectation(description: "load")
    var reply: [String: Any] = [:]
    handler.handle(method: "load", arguments: args) { reply = $0; done.fulfill() }
    var cleared: [String: Any] = [:]
    handler.handle(method: "clear", arguments: nil) { cleared = $0 }
    XCTAssertEqual(cleared["status"] as? String, "ok")
    workQueue.resume()
    wait(for: [done], timeout: 30)
    XCTAssertEqual(reply["status"] as? String, "ok")
    XCTAssertEqual(reply["staged"] as? Bool, false)
    XCTAssertEqual(reply["superseded"] as? Bool, true)
    XCTAssertEqual(reply["restarted"] as? Bool, false)
    XCTAssertEqual(source.restarts, 0)
    XCTAssertFalse(staging.hasPending, "the new session starts without the old map")
  }

  /// A later load supersedes an earlier one still in flight; only the later
  /// one stages and restarts.
  func testALaterLoadSupersedesAnEarlierOneInFlight() throws {
    let (args, _) = try writeRecordedMap()
    let source = FakeWorldMapSource()
    source.restartEpoch = 3
    let staging = WorldMapInitialMapStore()
    let workQueue = DispatchQueue(label: "test.world-map")
    let handler = ownershipHandler(source, staging, workQueue: workQueue, replyQueue: DispatchQueue(label: "test.reply"))
    workQueue.suspend()
    let first = expectation(description: "first")
    let second = expectation(description: "second")
    var firstReply: [String: Any] = [:]
    var secondReply: [String: Any] = [:]
    handler.handle(method: "load", arguments: args) { firstReply = $0; first.fulfill() }
    handler.handle(method: "load", arguments: args) { secondReply = $0; second.fulfill() }
    workQueue.resume()
    wait(for: [first, second], timeout: 60)
    XCTAssertEqual(firstReply["superseded"] as? Bool, true)
    XCTAssertEqual(firstReply["restarted"] as? Bool, false)
    XCTAssertEqual(secondReply["staged"] as? Bool, true)
    XCTAssertEqual(secondReply["restarted"] as? Bool, true)
    XCTAssertEqual(source.restarts, 1)
  }

  /// A failed load (missing file, wrong checksum) leaves no map staged by
  /// an earlier session's load.
  func testAFailedLoadDropsAnEarlierStagedMap() throws {
    let (args, map) = try writeRecordedMap()
    let source = FakeWorldMapSource()
    source.restartEpoch = 3
    let staging = WorldMapInitialMapStore()
    let handler = ownershipHandler(
      source, staging, workQueue: DispatchQueue(label: "test.world-map"), replyQueue: DispatchQueue(label: "test.reply"))
    func load(_ arguments: [String: Any]) -> [String: Any] {
      let done = expectation(description: "load")
      var reply: [String: Any] = [:]
      handler.handle(method: "load", arguments: arguments) { reply = $0; done.fulfill() }
      wait(for: [done], timeout: 30)
      return reply
    }
    staging.stage(map)
    var missing = args
    missing["path"] = "groups/other/world_map.arworldmap"
    XCTAssertEqual(load(missing)["code"] as? String, WorldMapFailureCode.readFailed)
    XCTAssertFalse(staging.hasPending)
    staging.stage(map)
    var wrongSha = args
    wrongSha["sha256"] = String(repeating: "0", count: 64)
    XCTAssertEqual(load(wrongSha)["code"] as? String, WorldMapFailureCode.checksumMismatch)
    XCTAssertFalse(staging.hasPending)
    XCTAssertEqual(source.restarts, 0)
  }
}

/// A running session whose world map arrives when the test completes it.
private final class DeferredWorldMapSource: WorldMapSource {
  private var pending: ((ARWorldMap?, Error?) -> Void)?

  func currentWorldMap(_ completion: @escaping (ARWorldMap?, Error?) -> Void) -> Bool {
    pending = completion
    return true
  }

  func restartRunningSession() -> Int? { nil }

  func complete(_ map: ARWorldMap?, _ error: Error?) {
    let completion = pending
    pending = nil
    DispatchQueue.global().async { completion?(map, error) }
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

  /// Epoch the next restart reports; nil: no running session.
  var restartEpoch: Int?
  private(set) var restarts = 0

  func restartRunningSession() -> Int? {
    restarts += 1
    return restartEpoch
  }
}

/// Records the initial world map and reset option of every run.
private final class ConfigurationRecordingDriver: ARSessionDriving {
  var worldMaps: [ARWorldMap?] = []
  var resets: [Bool] = []
  func run(_ configuration: ARConfiguration, options: ARSession.RunOptions) {
    worldMaps.append((configuration as? ARWorldTrackingConfiguration)?.initialWorldMap)
    resets.append(options.contains(.resetTracking))
  }
  func pause() {}
}

private final class StartRecordingObserver: ARFrameObserver {
  var starts: [(epoch: Int, map: Bool)] = []
  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {}
  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {
    starts.append((epoch, configuration.hasInitialWorldMap))
  }
}

extension WorldMapTests {
  static let hostPlan = ARSessionConfigurationPlan(
    options: ARSessionOptions(),
    capabilities: ARCameraCapabilities(
      modelIdentifier: "test", worldTrackingSupported: true, sceneDepthSupported: false,
      smoothedSceneDepthSupported: false, sceneReconstructionSupported: false,
      videoFormats: [], highResolutionFrameCaptureFormat: nil))
}
