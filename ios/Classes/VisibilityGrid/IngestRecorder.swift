import Foundation

/// Debug-build ingest recording writer (contract 2.11): a 32 B header (magic
/// `C3IR`, version, device model hash, created wall time, message count) then
/// `u32 length` plus one ingest message, repeated. Same format as the Android
/// recorder, so iOS recordings replay through the Dart fake ingest source.
///
/// [record] runs on the reply path: it copies the message into one of
/// [slots] preallocated slots and returns; a serial writer queue appends
/// slots to the file. A message that finds no free slot is dropped and
/// counted, so the reply path never waits for storage.
final class IngestRecorder {
  static let slots = 4

  /// FNV-1a 64 over the UTF-16 code units of the device model (the Android hash).
  static func deviceModelHash(_ model: String?) -> Int64 {
    var h: UInt64 = 0xcbf2_9ce4_8422_2325
    for unit in (model ?? "").utf16 {
      h ^= UInt64(unit)
      h = h &* 0x0000_0100_0000_01b3
    }
    return Int64(bitPattern: h)
  }

  /// The 32 B recording header.
  static func header(deviceModelHash: Int64, createdTimeNs: Int64, messageCount: Int) -> IngestMessageBuffer {
    let b = IngestMessageBuffer(capacity: IngestRecordingLayout.headerBytes)
    b.u32(0, IngestRecordingLayout.magic)
    b.u16(4, IngestRecordingLayout.version)
    b.i64(IngestRecordingLayout.deviceModelHash, deviceModelHash)
    b.i64(IngestRecordingLayout.createdTimeNs, createdTimeNs)
    b.u32(IngestRecordingLayout.messageCount, UInt32(messageCount))
    return b
  }

  private let directory: URL
  private let deviceModel: String?
  private let wallClockNs: () -> Int64
  private let lock = NSLock()
  private let writer = DispatchQueue(label: "capture3d.grid.ingest.recorder", qos: .utility)
  private var slotBuffers: [IngestMessageBuffer] = []
  private var slotLength = [Int](repeating: 0, count: IngestRecorder.slots)
  private var head = 0
  private var count = 0
  private var handle: FileHandle?
  private var active = false

  private(set) var path: String?
  private(set) var messages = 0
  private(set) var dropped = 0
  private(set) var writeFailed = false

  init(directory: URL, deviceModel: String?,
       wallClockNs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1e9) }) {
    self.directory = directory
    self.deviceModel = deviceModel
    self.wallClockNs = wallClockNs
  }

  var isRecording: Bool { lock.lock(); defer { lock.unlock() }; return active }

  /// Opens a new recording file (never overwrites); false if one is open or it cannot be created.
  func start() -> Bool {
    lock.lock(); defer { lock.unlock() }
    if active { return false }
    if slotBuffers.isEmpty {
      slotBuffers = (0..<Self.slots).map { _ in
        IngestMessageBuffer(capacity: IngestRecordingLayout.lengthPrefixBytes + IngestMessageLayout.maxMessageBytes)
      }
    }
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let created = wallClockNs()
      var target = directory.appendingPathComponent("ingest-\(created).c3ir")
      var k = 1
      while FileManager.default.fileExists(atPath: target.path) {
        target = directory.appendingPathComponent("ingest-\(created)-\(k).c3ir")
        k += 1
      }
      guard FileManager.default.createFile(atPath: target.path, contents: nil) else {
        writeFailed = true
        return false
      }
      let h = try FileHandle(forWritingTo: target)
      let header = Self.header(deviceModelHash: Self.deviceModelHash(deviceModel), createdTimeNs: created, messageCount: 0)
      try h.write(contentsOf: header.data(IngestRecordingLayout.headerBytes))
      handle = h
      path = target.path
      messages = 0
      dropped = 0
      writeFailed = false
      head = 0
      count = 0
      active = true
      return true
    } catch {
      writeFailed = true
      return false
    }
  }

  /// Copies one replied message; never blocks on storage.
  func record(_ message: UnsafeRawPointer, length: Int) {
    lock.lock()
    guard active, !slotBuffers.isEmpty, length <= IngestMessageLayout.maxMessageBytes else { lock.unlock(); return }
    if count == Self.slots {
      dropped += 1
      lock.unlock()
      return
    }
    let index = (head + count) % Self.slots
    let slot = slotBuffers[index]
    slot.u32(0, UInt32(length))
    (slot.bytes + IngestRecordingLayout.lengthPrefixBytes).copyMemory(from: message, byteCount: length)
    slotLength[index] = IngestRecordingLayout.lengthPrefixBytes + length
    count += 1
    lock.unlock()
    writer.async { [self] in drainOne() }
  }

  private func drainOne() {
    lock.lock()
    guard count > 0, let h = handle else { lock.unlock(); return }
    let slot = slotBuffers[head]
    let length = slotLength[head]
    lock.unlock()
    var failed = false
    do {
      try h.write(contentsOf: Data(bytesNoCopy: slot.bytes, count: length, deallocator: .none))
    } catch {
      failed = true
    }
    lock.lock()
    head = (head + 1) % Self.slots
    count -= 1
    if failed { writeFailed = true } else if !writeFailed { messages += 1 }
    lock.unlock()
  }

  /// Flushes queued messages, patches the message count and closes the file.
  @discardableResult
  func stop() -> String? {
    lock.lock()
    guard active else { lock.unlock(); return nil }
    active = false
    lock.unlock()
    writer.sync {}
    lock.lock(); defer { lock.unlock() }
    if let h = handle {
      do {
        try h.seek(toOffset: UInt64(IngestRecordingLayout.messageCount))
        var n = UInt32(messages).littleEndian
        try h.write(contentsOf: Data(bytes: &n, count: 4))
        try h.synchronize()
      } catch {
        writeFailed = true
      }
      try? h.close()
    }
    handle = nil
    return path
  }
}
