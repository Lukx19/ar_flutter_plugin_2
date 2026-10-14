import Foundation
import simd

/// Kind 6 session events (contract 2.4 and its additive amendments):
/// tracking lost and regained, AR session restarted, depth mode changed,
/// recording started and stopped, tracking reset, AR session resumed. Each
/// event is queued in order (never replaced). Callers on different threads
/// are serialised; the message buffer is allocated once.
final class SessionEventEmitter {
  private let sink: IngestMessageSink
  private let clock: IngestClock
  private let lock = NSLock()
  private let buffer = IngestMessageBuffer(
    capacity: IngestMessageLayout.headerBytes + IngestMessageLayout.sessionEventBytes
  )

  private(set) var emitted = 0

  init(sink: IngestMessageSink, clock: IngestClock) {
    self.sink = sink
    self.clock = clock
  }

  /// Emits [event] with [value]; [sampleNs] is the AR timestamp it refers to (0 if none).
  func emit(event: Int, value: Int64, sampleNs: Int64, trackingState: Int, groupEpoch: Int) {
    lock.lock(); defer { lock.unlock() }
    let b = buffer
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindSessionEvent)
    IngestMessageWriter.payloadBytes(b, IngestMessageLayout.sessionEventBytes)
    IngestMessageWriter.timing(b, sampleNs: sampleNs, frameNs: sampleNs, acquireNs: clock.nowNs())
    IngestMessageWriter.pose(
      b, trackingState: trackingState, poseMatch: IngestMessageLayout.poseMatchMissing, groupEpoch: groupEpoch,
      poseGapUs: 0, cameraToWorld: matrix_identity_float4x4
    )
    let p = IngestMessageLayout.headerBytes
    b.zero(p, IngestMessageLayout.sessionEventBytes)
    b.u8(p, event)
    b.i64(p + 8, value)
    if sink.offer(b, length: IngestMessageLayout.headerBytes + IngestMessageLayout.sessionEventBytes) { emitted += 1 }
  }
}
