import Foundation
import Metal

/// Fence of the staging uploads: an `MTLSharedEvent` signalled by the
/// command buffer right after the frame's blits, read without waiting.
/// Falls back to a completion handler where the device has no shared event.
final class GridFrameFence {
  private let event: MTLSharedEvent?
  private let completion = GridFrameCompletion()
  private var next: UInt64 = 0

  init(device: MTLDevice) {
    event = device.makeSharedEvent()
    event?.label = "grid upload fence"
  }

  /// Highest value whose work completed.
  var completedValue: UInt64 {
    if let event = event { return event.signaledValue }
    return completion.completedValue
  }

  /// Signals a new value after the work encoded so far in [commandBuffer]
  /// (no encoder may be open); returns it. Render thread.
  func signal(after commandBuffer: MTLCommandBuffer) -> UInt64 {
    guard let event = event else { return completion.track(commandBuffer) }
    next += 1
    commandBuffer.encodeSignalEvent(event, value: next)
    return next
  }
}

/// Completion counter of whole frames: each tracked command buffer gets the
/// next value, reported complete by its completion handler. Frames of one
/// queue complete in order, so the counter is monotonic.
final class GridFrameCompletion {
  private let lock = NSLock()
  private var next: UInt64 = 0
  private var completed: UInt64 = 0

  var completedValue: UInt64 {
    lock.lock(); defer { lock.unlock() }
    return completed
  }

  /// Returns the value [commandBuffer] completes with. Render thread.
  func track(_ commandBuffer: MTLCommandBuffer) -> UInt64 {
    lock.lock()
    next += 1
    let value = next
    lock.unlock()
    commandBuffer.addCompletedHandler { [weak self] _ in self?.complete(value) }
    return value
  }

  private func complete(_ value: UInt64) {
    lock.lock()
    if value > completed { completed = value }
    lock.unlock()
  }
}
