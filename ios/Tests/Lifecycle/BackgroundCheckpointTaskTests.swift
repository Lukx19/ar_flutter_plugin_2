import UIKit
import XCTest
@testable import ar_flutter_plugin_2

/// Begin/end pairing of the background task around the lifecycle checkpoint.
final class BackgroundCheckpointTaskTests: XCTestCase {
  private var runner: FakeBackgroundTasks!
  private var center: NotificationCenter!
  private var emitter: NativeSessionLogEmitter!
  private var scheduled: [(delay: TimeInterval, work: () -> Void)] = []
  private var now: UInt64 = 0

  override func setUp() {
    runner = FakeBackgroundTasks()
    center = NotificationCenter()
    emitter = NativeSessionLogEmitter()
    emitter.setActive(true)
    scheduled = []
  }

  private func makeTask(hold: TimeInterval = 8) -> BackgroundCheckpointTask {
    let task = BackgroundCheckpointTask(
      runner: runner, emitter: emitter, holdSeconds: hold, notificationCenter: center,
      nowNs: { [unowned self] in self.now },
      schedule: { [unowned self] delay, work in self.scheduled.append((delay, work)) }
    )
    task.startObserving()
    return task
  }

  func testBackgroundBeginsOnceAndForegroundEndsIt() {
    let task = makeTask()
    center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    XCTAssertEqual(runner.begun, [1], "one task per background stay")
    XCTAssertTrue(task.isHeld)
    now = 250_000_000
    center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
    center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
    XCTAssertEqual(runner.ended, [1])
    XCTAssertFalse(task.isHeld)
    XCTAssertEqual(task.lastEndReason, .foreground)
    // The stale hold timeout of that stay does nothing.
    scheduled.forEach { $0.work() }
    XCTAssertEqual(runner.ended, [1])

    let record = emitter.buffer.drain().first
    XCTAssertEqual(record?[1] as? String, "BACKGROUND_TASK_ENDED")
    XCTAssertEqual(record?[2] as? String, "info")
    XCTAssertEqual((record?[4] as? [String: Any])?["heldMs"] as? Int, 250)
    XCTAssertEqual((record?[4] as? [String: Any])?["endReason"] as? String, "foreground")
  }

  func testHoldWindowEndsTheTaskWithinTheGrantedTime() {
    runner.remaining = 5
    let task = makeTask(hold: 8)
    task.didEnterBackground()
    XCTAssertEqual(scheduled.map(\.delay), [4], "one second before the system limit")
    scheduled[0].work()
    XCTAssertEqual(runner.ended, [1])
    XCTAssertEqual(task.lastEndReason, .holdElapsed)
    task.willEnterForeground()
    XCTAssertEqual(runner.ended, [1], "ended exactly once")
  }

  func testExpirationEndsTheTaskOnceAsAWarning() {
    let task = makeTask()
    task.didEnterBackground()
    runner.expire(1)
    runner.expire(1)
    scheduled.forEach { $0.work() }
    XCTAssertEqual(runner.ended, [1])
    XCTAssertEqual(task.lastEndReason, .expired)
    XCTAssertEqual(emitter.buffer.drain().first?[2] as? String, "warning")
  }

  func testEachStayGetsItsOwnTaskAndDetachEndsAHeldTask() {
    let task = makeTask()
    task.didEnterBackground()
    task.willEnterForeground()
    task.didEnterBackground()
    XCTAssertEqual(runner.begun, [1, 2])
    task.stopObserving()
    XCTAssertEqual(runner.ended, [1, 2])
    center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    XCTAssertEqual(runner.begun, [1, 2], "no observation after stop")
    XCTAssertEqual(task.begun, task.ended)
  }

  func testRefusedTaskIsNeverEnded() {
    runner.refuse = true
    let task = makeTask()
    task.didEnterBackground()
    XCTAssertFalse(task.isHeld)
    task.willEnterForeground()
    XCTAssertTrue(runner.ended.isEmpty)
    XCTAssertTrue(scheduled.isEmpty)
  }
}

private final class FakeBackgroundTasks: BackgroundTaskRunning {
  var begun: [Int] = []
  var ended: [Int] = []
  var remaining: TimeInterval = 30
  var refuse = false
  private var handlers: [Int: () -> Void] = [:]

  func beginBackgroundTask(withName name: String?, expirationHandler: (() -> Void)?) -> UIBackgroundTaskIdentifier {
    if refuse { return .invalid }
    let id = begun.count + 1
    begun.append(id)
    handlers[id] = expirationHandler
    return UIBackgroundTaskIdentifier(rawValue: id)
  }

  func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier) {
    XCTAssertNotEqual(identifier, .invalid)
    ended.append(identifier.rawValue)
  }

  var backgroundTimeRemaining: TimeInterval { remaining }

  func expire(_ id: Int) { handlers[id]?() }
}
