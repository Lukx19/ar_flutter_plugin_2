import Foundation
import XCTest

@testable import ar_flutter_plugin_2

private final class StillExposureTestScheduler: AdaptiveStillExposureScheduler {
  var now: TimeInterval = 10
  private var actions: [(TimeInterval, () -> Void)] = []

  func perform(_ action: @escaping () -> Void) { action() }
  func after(_ delay: TimeInterval, _ action: @escaping () -> Void) {
    actions.append((now + delay, action))
  }
  func advance(_ seconds: TimeInterval) {
    now += seconds
    while let next = actions.enumerated().filter({ $0.element.0 <= now }).min(by: {
      $0.element.0 < $1.element.0
    }) {
      actions.remove(at: next.offset).1()
    }
  }
}

/// The real control bridge and a configuration-lock-checking device fake prove
/// temporary still controls are bounded and cannot overwrite later user intent.
final class AdaptiveStillExposureTests: XCTestCase {
  private func capabilities() -> AdaptiveStillExposureCapabilities {
    AdaptiveStillExposureCapabilities(
      minISO: 34, maxISO: 3264, minExposureSeconds: 1 / 34_420,
      maxExposureSeconds: 1 / 24, fixedAperture: true, videoHDREnabled: false,
      flickerDetected: false)
  }

  private func metering(_ now: TimeInterval = 10) -> AdaptiveStillExposureMetering {
    AdaptiveStillExposureMetering(timestamp: now - 0.01, exposureSeconds: 0.03, iso: 200)
  }

  private func plan(
    _ capabilities: AdaptiveStillExposureCapabilities?, _ metering: AdaptiveStillExposureMetering?,
    now: TimeInterval = 10, ordinarySingle: Bool = true, automaticExposure: Bool = true,
    adjustingExposure: Bool = false
  ) -> AdaptiveStillExposurePlan? {
    AdaptiveStillExposurePolicy.plan(
      capabilities: capabilities, metering: metering, now: now,
      ordinarySingle: ordinarySingle, automaticExposure: automaticExposure,
      adjustingExposure: adjustingExposure)
  }

  private func device() -> FakeCameraControlDevice {
    let device = FakeCameraControlDevice()
    device.adaptiveStillCapabilities = capabilities()
    device.exposureSeconds = 0.03
    device.iso = 200
    return device
  }

  func testFreshConvergedAutoPreservesMeteredBrightnessAtTenMilliseconds() throws {
    let value = try XCTUnwrap(plan(capabilities(), metering()))
    XCTAssertEqual(value.exposureSeconds, 0.01, accuracy: 1e-9)
    XCTAssertEqual(value.iso, 600, accuracy: 0.01)
    XCTAssertEqual(value.exposureSeconds * Double(value.iso), 0.03 * 200, accuracy: 1e-6)
  }

  func testStaleUnknownFlickerHdrManualAndInsufficientHeadroomRetainOrdinaryAE() {
    XCTAssertNil(plan(nil, metering()))
    XCTAssertNil(plan(capabilities(), nil))
    XCTAssertNil(plan(capabilities(), metering(9.74)))
    XCTAssertNil(plan(capabilities(), metering(11)))
    XCTAssertNil(plan(capabilities(), metering(), ordinarySingle: false))
    XCTAssertNil(plan(capabilities(), metering(), automaticExposure: false))
    XCTAssertNil(plan(capabilities(), metering(), adjustingExposure: true))
    var facts = capabilities()
    facts.flickerDetected = nil
    XCTAssertNil(plan(facts, metering()))
    facts.flickerDetected = true
    XCTAssertNil(plan(facts, metering()))
    facts = capabilities()
    facts.videoHDREnabled = true
    XCTAssertNil(plan(facts, metering()))
    facts = capabilities()
    facts.fixedAperture = false
    XCTAssertNil(plan(facts, metering()))
    facts = capabilities()
    facts.maxISO = 500
    XCTAssertNil(plan(facts, metering()))
    var sample = metering()
    sample.iso = 600
    XCTAssertNil(
      plan(capabilities(), sample),
      "the ISO1600 noise ceiling must preserve ordinary AE rather than darken")
    sample = metering()
    sample.exposureSeconds = .nan
    XCTAssertNil(plan(capabilities(), sample))
    sample = metering()
    sample.exposureSeconds = 0.005
    XCTAssertNil(plan(capabilities(), sample), "an already faster exposure is not made slower")
  }

  func testTemporaryExposureKeepsAutoIntentAndRestoresAfterTheFrame() throws {
    let device = device()
    let bridge = CameraControlBridge(device: device)
    let scheduler = StillExposureTestScheduler()
    var lease: AdaptiveStillExposureLease?
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler
    ) { lease = $0 }
    let applied = try XCTUnwrap(lease)
    XCTAssertEqual(device.exposureMode, .custom)
    XCTAssertEqual(device.iso, 600, accuracy: 0.01)
    let state = try XCTUnwrap(try bridge.handle("getCurrentExposureState", [:]) as? [String: Any?])
    XCTAssertEqual(
      state["isAutoExposureEnabled"] as? Bool, true,
      "temporary hardware custom mode must not become manual UI intent")
    XCTAssertEqual(state["exposureMode"] as? String, "auto")
    applied.finish()
    applied.finish()
    XCTAssertEqual(device.exposureMode, .auto)
    XCTAssertEqual(device.configurations, 2, "one apply and one idempotent restore")
    XCTAssertTrue(device.violations.isEmpty)
  }

  func testApplyAcknowledgementTimeoutFallsBackOnceAndLateAckCannotReapply() {
    let device = device()
    device.delayAdaptiveAcknowledgement = true
    let bridge = CameraControlBridge(device: device)
    let scheduler = StillExposureTestScheduler()
    var replies = 0
    var lease: AdaptiveStillExposureLease?
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler
    ) {
      replies += 1
      lease = $0
    }
    XCTAssertEqual(replies, 0, "a physical apply acknowledgement owns a bounded preparation window")
    XCTAssertEqual(device.adaptiveSetters, 1)
    scheduler.advance(0.101)
    XCTAssertEqual(replies, 1)
    XCTAssertNil(lease)
    XCTAssertEqual(device.exposureMode, .auto)
    device.adaptiveAcknowledgements.forEach { $0() }
    XCTAssertEqual(replies, 1)
    XCTAssertEqual(device.exposureMode, .auto)
  }

  func testCancelledPreparationNeverOwnsAnExposureOrOverwritesAWaitingManualChange() throws {
    let device = device()
    device.delayAdaptiveAcknowledgement = true
    let bridge = CameraControlBridge(device: device)
    let scheduler = StillExposureTestScheduler()
    var cancelled = false
    var replies = 0
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler,
      isCancelled: { cancelled }
    ) { lease in
      replies += 1
      XCTAssertNil(lease)
    }
    XCTAssertEqual(device.adaptiveSetters, 1)
    cancelled = true
    _ = try bridge.handle("setISO", ["isoValue": 900])
    device.adaptiveAcknowledgements.forEach { $0() }
    scheduler.advance(1)
    XCTAssertEqual(replies, 1)
    XCTAssertEqual(device.exposureMode, .custom)
    XCTAssertEqual(
      device.iso, 900, "cancel and late acknowledgement cannot restore over newer manual ISO")
  }

  func testExposureLeaseExpiresWithoutBlockingAnotherCapture() throws {
    let device = device()
    let bridge = CameraControlBridge(device: device)
    let scheduler = StillExposureTestScheduler()
    var lease: AdaptiveStillExposureLease?
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler
    ) { lease = $0 }
    let expired = try XCTUnwrap(lease)
    scheduler.advance(0.751)
    XCTAssertEqual(
      device.exposureMode, .auto, "a missing frame callback cannot keep custom exposure alive")
    _ = try bridge.handle("setISO", ["isoValue": 800])
    expired.finish()
    XCTAssertEqual(device.exposureMode, .custom)
    XCTAssertEqual(device.iso, 800)
  }

  func testLaterManualAndReplacementOwnersKeepTheirSettings() throws {
    let device = device()
    let bridge = CameraControlBridge(device: device)
    let scheduler = StillExposureTestScheduler()
    var lease: AdaptiveStillExposureLease?
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler
    ) { lease = $0 }
    let previous = try XCTUnwrap(lease)
    _ = try bridge.handle("setExposureTime", ["exposureTimeMicroseconds": 20_000])
    previous.finish()
    XCTAssertEqual(device.exposureMode, .custom)
    XCTAssertEqual(device.exposureSeconds, 0.02, accuracy: 1e-9)

    _ = try bridge.handle("setAutoExposureEnabled", ["enabled": true])
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler
    ) { lease = $0 }
    let retired = try XCTUnwrap(lease)
    let replacement = CameraControlBridge(device: device)
    _ = try replacement.handle("setISO", ["isoValue": 700])
    retired.finish()
    scheduler.advance(1)
    XCTAssertEqual(device.exposureMode, .custom)
    XCTAssertEqual(device.iso, 700)
    XCTAssertTrue(device.violations.isEmpty)
  }

  func testReplacementOwnerRestoresInheritedAutoIntentBeforeReadingControls() throws {
    let device = device()
    let bridge = CameraControlBridge(device: device)
    let scheduler = StillExposureTestScheduler()
    var lease: AdaptiveStillExposureLease?
    bridge.prepareAdaptiveStillExposure(
      metering: metering(), ordinarySingle: true, scheduler: scheduler
    ) { lease = $0 }
    XCTAssertEqual(device.exposureMode, .custom)
    let replacement = CameraControlBridge(device: device)
    let state = try replacement.handle("getCurrentExposureState", [:]) as? [String: Any?]
    XCTAssertEqual(state?["isAutoExposureEnabled"] as? Bool, true)
    XCTAssertEqual(device.exposureMode, .auto)
    _ = try replacement.handle("setISO", ["isoValue": 700])
    lease?.finish()
    scheduler.advance(1)
    XCTAssertEqual(device.iso, 700)
    XCTAssertEqual(device.exposureMode, .custom)
  }

  func testManualLockedAndUnsupportedDevicesNeverReceiveTemporarySetters() {
    for mode in [CameraExposureModeValue.custom, .locked, .auto] {
      let device = device()
      device.exposureMode = mode
      if mode == .auto { device.supportsCustomExposure = false }
      let bridge = CameraControlBridge(device: device)
      let scheduler = StillExposureTestScheduler()
      var replies = 0
      bridge.prepareAdaptiveStillExposure(
        metering: metering(), ordinarySingle: true, scheduler: scheduler
      ) {
        replies += 1
        XCTAssertNil($0)
      }
      XCTAssertEqual(replies, 1)
      XCTAssertEqual(device.adaptiveSetters, 0)
    }
  }
}
