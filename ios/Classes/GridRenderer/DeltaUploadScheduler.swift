import Foundation

/// Result of offering one renderer delta to the scheduler.
enum DeltaIntake: Int32 {
  /// Queued for upload.
  case accepted = 0
  /// Dropped; the sender must request a full resynchronisation (reset first).
  case resyncRequired = 1
  /// Dropped because the message is malformed.
  case malformed = 2
  /// Dropped because the renderer failed; nothing is drawn until a working
  /// render host prepares it again.
  case rendererFailed = 4
}

/// Upload statistics of the scheduler.
struct UploadSchedulerCounters {
  var offered = 0
  var accepted = 0
  var droppedAwaitingReset = 0
  var overflows = 0
  var malformed = 0
  var frames = 0
  var lastFrameBytes = 0
  var maxFrameBytes = 0
  var lastFrameMessages = 0
  var deferredFrames = 0
  var maxBacklogBytes = 0
}

/// Moves renderer deltas from the channel queue to the render thread with a
/// per-frame upload byte budget (the Swift twin of the Android
/// `DeltaUploadScheduler`).
///
/// [offer] copies each message into a preallocated ring (no allocation per
/// message) and returns at once. [drainFrame] runs on the render thread: it
/// applies queued messages in order until the next one would exceed the
/// frame's budget or the target's staging capacity, always at least one
/// uploading message per frame so the queue makes progress. Messages are
/// never dropped silently: when the ring is full, intake switches to
/// awaiting a reset and replies [DeltaIntake.resyncRequired] until the
/// sender's reset arrives. A new scheduler starts awaiting a reset, so a
/// renderer recreated after a failure resynchronises with the grid.
final class DeltaUploadScheduler {
  /// About 32 full messages; a backlog beyond it means the renderer stalled.
  static let defaultRingBytes = 2 * 1024 * 1024
  static let defaultMaxQueued = 4096
  /// Upload bytes per frame, as on Android: larger single uploads stalled the
  /// tablet CPU up to 17 ms when written into buffers in use.
  static let defaultFrameBudgetBytes = 64 * 1024

  private let ring: UnsafeMutableRawPointer
  private let capacity: Int
  private let maxQueued: Int
  private var starts: [Int]
  private var lengths: [Int]
  private var consumed: [Int]
  private let lock = NSLock()
  private var head = 0
  private var queued = 0
  private var readPos = 0
  private var writePos = 0
  private var usedBytes = 0
  private var awaitingReset = true
  private var epoch = 0
  private var applying = false
  private var stats = UploadSchedulerCounters()

  init(ringBytes: Int = DeltaUploadScheduler.defaultRingBytes, maxQueued: Int = DeltaUploadScheduler.defaultMaxQueued) {
    capacity = ringBytes
    self.maxQueued = maxQueued
    ring = UnsafeMutableRawPointer.allocate(byteCount: ringBytes, alignment: 16)
    starts = Array(repeating: 0, count: maxQueued)
    lengths = Array(repeating: 0, count: maxQueued)
    consumed = Array(repeating: 0, count: maxQueued)
  }

  deinit {
    ring.deallocate()
  }

  var counters: UploadSchedulerCounters { locked { stats } }

  /// Bytes queued and not yet applied.
  var backlogBytes: Int { locked { usedBytes } }

  var queuedMessages: Int { locked { queued } }

  var isAwaitingReset: Bool { locked { awaitingReset } }

  /// Copies [message] into the ring. Channel queue.
  func offer(_ message: UnsafeRawBufferPointer) -> DeltaIntake {
    lock.lock(); defer { lock.unlock() }
    stats.offered += 1
    let length = message.count
    guard length >= RendererDeltaLayout.headerBytes, length <= RendererDeltaLayout.maxMessageBytes,
          let source = message.baseAddress else {
      stats.malformed += 1
      return .malformed
    }
    let kind = message.le8(RendererDeltaLayout.kind)
    if awaitingReset {
      // The render thread may still read the discarded ring; the sender
      // retries its reset with the next export call.
      if kind != RendererDeltaLayout.kindReset || applying {
        stats.droppedAwaitingReset += 1
        return .resyncRequired
      }
      awaitingReset = false
    }
    let at = reserve(length)
    if at < 0 {
      stats.overflows += 1
      awaitingReset = true
      discardAll()
      return .resyncRequired
    }
    (ring + at).copyMemory(from: source, byteCount: length)
    stats.accepted += 1
    if usedBytes > stats.maxBacklogBytes { stats.maxBacklogBytes = usedBytes }
    return .accepted
  }

  /// Convenience for a `Data` message.
  func offer(_ message: Data) -> DeltaIntake {
    message.withUnsafeBytes { offer($0) }
  }

  /// Drops the queue and waits for the sender's reset, for example after the
  /// GPU buffers were released.
  func requireReset() {
    lock.lock(); defer { lock.unlock() }
    awaitingReset = true
    discardAll()
  }

  /// Applies queued messages to [store] within [budgetBytes] of uploads (and
  /// the target's staging capacity). Render thread. Returns uploaded bytes.
  /// A throw (buffer allocation failed) leaves the failing message queued.
  func drainFrame(store: InstanceBufferStore, target: GpuBufferTarget, budgetBytes: Int) throws -> Int {
    var uploaded = 0
    var messages = 0
    while true {
      let start: Int
      let length: Int
      let peeked: Int
      lock.lock()
      if queued == 0 {
        finishFrame(uploaded, messages, deferred: false)
        lock.unlock()
        return uploaded
      }
      start = starts[head]
      length = lengths[head]
      peeked = epoch
      applying = true
      lock.unlock()
      let message = UnsafeRawBufferPointer(start: ring + start, count: length)
      let cost = store.uploadBytes(message)
      let staging = target.frameUploadCapacity()
      if (uploaded > 0 && uploaded + cost > min(budgetBytes, staging)) || cost > staging {
        lock.lock()
        applying = false
        finishFrame(uploaded, messages, deferred: true)
        lock.unlock()
        return uploaded
      }
      do {
        try store.apply(message)
      } catch {
        lock.lock()
        applying = false
        lock.unlock()
        throw error
      }
      uploaded += cost
      messages += 1
      lock.lock()
      applying = false
      if peeked == epoch && queued > 0 { pop() }
      lock.unlock()
    }
  }

  private func locked<T>(_ body: () -> T) -> T {
    lock.lock(); defer { lock.unlock() }
    return body()
  }

  /// Holds [lock].
  private func finishFrame(_ bytes: Int, _ messages: Int, deferred: Bool) {
    stats.frames += 1
    stats.lastFrameBytes = bytes
    stats.lastFrameMessages = messages
    if bytes > stats.maxFrameBytes { stats.maxFrameBytes = bytes }
    if deferred { stats.deferredFrames += 1 }
  }

  /// Ring offset for [length] contiguous bytes, or -1 when full. Holds [lock].
  private func reserve(_ length: Int) -> Int {
    if queued == maxQueued { return -1 }
    if queued == 0 {
      readPos = 0
      writePos = 0
    }
    let at: Int
    var pad = 0
    if queued == 0 || writePos > readPos || (writePos == readPos && usedBytes == 0) {
      if capacity - writePos >= length {
        at = writePos
      } else if readPos >= length {
        pad = capacity - writePos
        at = 0
      } else {
        return -1
      }
    } else {
      if readPos - writePos >= length { at = writePos } else { return -1 }
    }
    let tail = (head + queued) % maxQueued
    starts[tail] = at
    lengths[tail] = length
    consumed[tail] = length + pad
    queued += 1
    usedBytes += length + pad
    writePos = at + length
    if writePos == capacity { writePos = 0 }
    return at
  }

  /// Holds [lock].
  private func pop() {
    usedBytes -= consumed[head]
    head = (head + 1) % maxQueued
    queued -= 1
    if queued == 0 {
      readPos = 0
      writePos = 0
      usedBytes = 0
    } else {
      readPos = starts[head]
    }
  }

  /// Holds [lock].
  private func discardAll() {
    epoch += 1
    head = 0
    queued = 0
    readPos = 0
    writePos = 0
    usedBytes = 0
  }
}
