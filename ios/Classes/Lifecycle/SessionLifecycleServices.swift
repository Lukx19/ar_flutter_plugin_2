import Flutter
import Foundation

/// Registration point of the session log, native anomaly, background
/// checkpoint and world map services of one Flutter engine.
/// `SwiftArFlutterPlugin` calls [register(messenger:)] on registration and
/// [unregister(messenger:)] on engine detach; the latest engine owns them.
///
/// - `ar_flutter_plugin_2/session_log` ([SessionLogChannelHandler])
/// - `ar_flutter_plugin_2/world_map` ([WorldMapChannelHandler])
/// - [NativeAnomalyEmitter] and [WorldMapRelocalizationMonitor] observe the
///   current `ARFrameSource` through `ARFrameSourceRegistry`
/// - [BackgroundCheckpointTask] follows the application state
final class SessionLifecycleServices {
  static let shared = SessionLifecycleServices()

  private let lock = NSLock()
  private weak var registeredMessenger: FlutterBinaryMessenger?
  private var channels: [FlutterMethodChannel] = []
  private var anomalies: NativeAnomalyEmitter?
  private var relocalization: WorldMapRelocalizationMonitor?
  private var backgroundTask: BackgroundCheckpointTask?
  private var sourceObservation: UUID?
  private weak var source: ARFrameSource?

  func register(messenger: FlutterBinaryMessenger) {
    lock.lock()
    unregisterLocked()
    registeredMessenger = messenger

    let sessionLog = SessionLogChannelHandler()
    let logChannel = FlutterMethodChannel(name: SessionLogChannelHandler.channelName, binaryMessenger: messenger)
    logChannel.setMethodCallHandler { call, result in sessionLog.handle(call, result: result) }

    let monitor = WorldMapRelocalizationMonitor()
    let worldMap = WorldMapChannelHandler(monitor: monitor)
    let mapChannel = FlutterMethodChannel(name: WorldMapChannelHandler.channelName, binaryMessenger: messenger)
    mapChannel.setMethodCallHandler { call, result in worldMap.handle(call, result: result) }
    channels = [logChannel, mapChannel]

    let emitter = NativeAnomalyEmitter()
    emitter.startSystemObservers()
    anomalies = emitter
    relocalization = monitor

    let task = BackgroundCheckpointTask(runner: ApplicationBackgroundTasks())
    task.startObserving()
    backgroundTask = task
    lock.unlock()

    // Outside the lock: observe calls back at once with the current source.
    let observation = ARFrameSourceRegistry.shared.observe { [weak self] source in
      self?.follow(source)
    }
    lock.lock()
    if anomalies === emitter {
      sourceObservation = observation
    } else {
      ARFrameSourceRegistry.shared.stopObserving(observation)
    }
    lock.unlock()
  }

  /// The engine of [messenger] detached: releases the services when that
  /// engine owns them.
  func unregister(messenger: FlutterBinaryMessenger) {
    lock.lock(); defer { lock.unlock() }
    guard let owner = registeredMessenger, owner === messenger else { return }
    unregisterLocked()
  }

  func isRegistered(messenger: FlutterBinaryMessenger) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return !channels.isEmpty && registeredMessenger === messenger
  }

  private func follow(_ next: ARFrameSource?) {
    lock.lock()
    let previous = source
    let observers = frameObserversLocked()
    source = next
    lock.unlock()
    if previous === next { return }
    observers.forEach { previous?.removeFrameObserver($0) }
    observers.forEach { next?.addFrameObserver($0) }
  }

  private func frameObserversLocked() -> [ARFrameObserver] {
    var observers: [ARFrameObserver] = []
    if let anomalies = anomalies { observers.append(anomalies) }
    if let relocalization = relocalization { observers.append(relocalization) }
    return observers
  }

  private func unregisterLocked() {
    registeredMessenger = nil
    channels.forEach { $0.setMethodCallHandler(nil) }
    channels = []
    if let id = sourceObservation { ARFrameSourceRegistry.shared.stopObserving(id) }
    sourceObservation = nil
    for observer in frameObserversLocked() { source?.removeFrameObserver(observer) }
    source = nil
    anomalies?.stopSystemObservers()
    anomalies = nil
    relocalization = nil
    backgroundTask?.stopObserving()
    backgroundTask = nil
    NativeSessionLogEmitter.shared.setActive(false)
  }
}
