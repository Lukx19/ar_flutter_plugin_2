import ARKit
import UIKit
import XCTest
@testable import ar_flutter_plugin_2

/// ARKit and system events to session log records: tracking limited reasons,
/// interruptions, thermal state and memory warnings.
final class NativeAnomalyEmitterTests: XCTestCase {
  private var now: UInt64 = 5_000_000_000
  private var thermal = ProcessInfo.ThermalState.nominal
  private var center: NotificationCenter!
  private var emitter: NativeSessionLogEmitter!
  private var anomalies: NativeAnomalyEmitter!

  override func setUp() {
    center = NotificationCenter()
    emitter = NativeSessionLogEmitter(buffer: NativeSessionLogBuffer(nowNs: { [unowned self] in self.now }))
    emitter.setActive(true)
    anomalies = NativeAnomalyEmitter(
      emitter: emitter, notificationCenter: center,
      thermalState: { [unowned self] in self.thermal }, nowNs: { [unowned self] in self.now }
    )
  }

  func testLimitedReasonAndThermalMapping() {
    XCTAssertEqual(NativeAnomalyEmitter.limitedReasonName(.initializing), "initializing")
    XCTAssertEqual(NativeAnomalyEmitter.limitedReasonName(.excessiveMotion), "excessiveMotion")
    XCTAssertEqual(NativeAnomalyEmitter.limitedReasonName(.insufficientFeatures), "insufficientFeatures")
    XCTAssertEqual(NativeAnomalyEmitter.limitedReasonName(.relocalizing), "relocalizing")
    XCTAssertEqual(NativeAnomalyEmitter.thermalStatus(.nominal), 0)
    XCTAssertEqual(NativeAnomalyEmitter.thermalStatus(.fair), 1)
    XCTAssertEqual(NativeAnomalyEmitter.thermalStatus(.serious), 3)
    XCTAssertEqual(NativeAnomalyEmitter.thermalStatus(.critical), 4)
  }

  func testTrackingLimitedIsRecordedOnEachReasonChange() {
    anomalies.trackingChanged(.limited(.initializing))
    anomalies.trackingChanged(.limited(.initializing))
    anomalies.trackingChanged(.limited(.excessiveMotion))
    anomalies.trackingChanged(.normal)
    anomalies.trackingChanged(.limited(.excessiveMotion))
    let drained = emitter.buffer.drain()
    let full = drained.filter { $0[0] as? String == "F" }
    XCTAssertEqual(full.map { $0[1] as? String }, ["TRACKING_LIMITED", "TRACKING_LIMITED"])
    XCTAssertEqual(full.map { $0[5] as? String }, ["initializing", "excessiveMotion"])
    XCTAssertEqual((full[1][4] as? [String: Any])?["reason"] as? String, "excessiveMotion")
    // The repeat after normal tracking is within the flood interval: counted.
    XCTAssertEqual(drained.last?[0] as? String, "C")
    XCTAssertEqual(drained.last?[2] as? Int, 1)
  }

  func testInterruptionBeganAndEndedWithDuration() {
    anomalies.interruptionBegan()
    now += 1_500_000_000
    anomalies.interruptionEnded()
    let drained = emitter.buffer.drain()
    XCTAssertEqual(drained.map { $0[1] as? String }, ["AR_SESSION_INTERRUPTED", "AR_SESSION_INTERRUPTED"])
    XCTAssertEqual(drained.map { $0[2] as? String }, ["warning", "info"])
    XCTAssertEqual((drained[1][4] as? [String: Any])?["interruptedMs"] as? Int, 1500)
    XCTAssertEqual((drained[1][4] as? [String: Any])?["phase"] as? String, "ended")
  }

  func testThermalAndMemoryNotificationsUseTheAndroidDataKeys() {
    anomalies.startSystemObservers()
    thermal = .fair
    center.post(name: ProcessInfo.thermalStateDidChangeNotification, object: nil)
    thermal = .serious
    center.post(name: ProcessInfo.thermalStateDidChangeNotification, object: nil)
    center.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    anomalies.stopSystemObservers()
    center.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)

    let drained = emitter.buffer.drain()
    XCTAssertEqual(drained.map { $0[1] as? String }, ["THERMAL_THROTTLING", "MEMORY_PRESSURE"], "fair is below the threshold")
    XCTAssertEqual((drained[0][4] as? [String: Any])?["thermalStatus"] as? Int, 3)
    XCTAssertEqual(drained[0][5] as? String, "status3")
    XCTAssertEqual((drained[1][4] as? [String: Any])?["trimLevel"] as? String, "memoryWarning")
  }

  func testNothingIsRecordedWithoutAnOpenLog() {
    emitter.setActive(false)
    anomalies.trackingChanged(.limited(.insufficientFeatures))
    anomalies.memoryWarning()
    XCTAssertTrue(emitter.buffer.drain().isEmpty)
  }
}
