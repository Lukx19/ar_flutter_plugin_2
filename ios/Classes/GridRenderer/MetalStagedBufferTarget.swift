import Foundation
import Metal

/// A GPU buffer could not be allocated (the renderer failed).
struct GridRendererAllocationError: Error, CustomStringConvertible {
  let buffer: String
  let bytes: Int
  var description: String { "grid renderer could not allocate \(bytes) B for \(buffer)" }
}

/// Persistent private Metal instance buffers fed through a fenced, triple
/// buffered staging ring (the Swift twin of the Android
/// `GlStagedBufferTarget`).
///
/// Writing delta bytes straight into a buffer the GPU is still drawing from
/// would race the frames in flight. Instead each frame takes one of [slots]
/// shared staging buffers whose previous copies have completed (checked
/// with an `MTLSharedEvent` value, never waited on), writes the bytes there
/// and records GPU-side blit copies into the persistent private buffers, in
/// the frame's command buffer before its render pass; Metal orders the
/// render reads after those blits. When the next staging slot is still
/// busy, [frameUploadCapacity] is zero and uploads wait for a later frame
/// instead of stalling this one.
///
/// Persistent buffers grow by allocating a larger buffer and copying on the
/// GPU (the old one is retained by the command buffer until it completes);
/// new space is filled with zeros, which reads as unpublished records.
/// Operations on overlapping ranges of one buffer within a frame are split
/// into separate blit encoders, so a later copy or fill never overtakes an
/// earlier one.
final class MetalStagedBufferTarget: GpuBufferTarget {
  /// Staging bytes per frame slot; above the per-frame budget so one full message always fits.
  static let defaultStagingBytes = 256 * 1024
  static let defaultSlots = 3

  let device: MTLDevice
  private let stagingBytes: Int
  private let staging: [MTLBuffer]
  private let fence: GridFrameFence
  private var slotFenceValue: [UInt64]
  private var buffers: [MTLBuffer?] = Array(repeating: nil, count: GridBuffer.allCases.count)
  private var capacity: [Int] = Array(repeating: 0, count: GridBuffer.allCases.count)
  private var slot = 0
  private var cursor = 0
  private var slotUsable = false
  private var commandBuffer: MTLCommandBuffer?
  private var encoder: MTLBlitCommandEncoder?
  /// Resource byte ranges used by the open blit encoder.
  private var touched: [(ObjectIdentifier, Range<Int>)] = []

  private(set) var stagedBytes = 0
  private(set) var busyFrames = 0
  private(set) var growths = 0
  private(set) var blitEncoders = 0

  init(device: MTLDevice, stagingBytes: Int = MetalStagedBufferTarget.defaultStagingBytes,
       slots: Int = MetalStagedBufferTarget.defaultSlots) throws {
    self.device = device
    self.stagingBytes = stagingBytes
    var created: [MTLBuffer] = []
    for s in 0..<slots {
      guard let buffer = device.makeBuffer(length: stagingBytes, options: [.storageModeShared, .cpuCacheModeWriteCombined]) else {
        throw GridRendererAllocationError(buffer: "staging", bytes: stagingBytes)
      }
      buffer.label = "grid staging \(s)"
      created.append(buffer)
    }
    staging = created
    fence = GridFrameFence(device: device)
    slotFenceValue = Array(repeating: 0, count: slots)
    slot = slots - 1
  }

  /// Persistent buffer of [buffer], nil before its first allocation.
  func metalBuffer(_ buffer: GridBuffer) -> MTLBuffer? { buffers[buffer.rawValue] }

  /// Picks this frame's staging slot without waiting for the GPU; uploads
  /// of this frame are encoded into [commandBuffer].
  func beginFrame(_ commandBuffer: MTLCommandBuffer) {
    self.commandBuffer = commandBuffer
    slot = (slot + 1) % staging.count
    cursor = 0
    touched.removeAll(keepingCapacity: true)
    slotUsable = fence.completedValue >= slotFenceValue[slot]
    if !slotUsable { busyFrames += 1 }
  }

  /// Ends the frame's blits and fences the staging slot it wrote.
  func endFrame() {
    endEncoder()
    if let commandBuffer = commandBuffer, cursor > 0 {
      slotFenceValue[slot] = fence.signal(after: commandBuffer)
    }
    commandBuffer = nil
  }

  func frameUploadCapacity() -> Int { slotUsable && commandBuffer != nil ? stagingBytes - cursor : 0 }

  /// Copies [source] into this frame's staging slot; returns the slot buffer
  /// and offset, or nil when it does not fit (16-byte aligned).
  func stage(_ source: UnsafeRawBufferPointer) -> (MTLBuffer, Int)? {
    guard let base = source.baseAddress, slotUsable, cursor + source.count <= stagingBytes else { return nil }
    let buffer = staging[slot]
    let at = cursor
    (buffer.contents() + at).copyMemory(from: base, byteCount: source.count)
    cursor += (source.count + 15) & ~15
    stagedBytes += source.count
    return (buffer, at)
  }

  /// The frame's blit encoder for an operation on bytes [range] of
  /// [resource] (and optionally [otherRange] of [other]): an operation that
  /// overlaps a range already used in the current encoder starts a new
  /// encoder, so it runs after the earlier one (Metal orders encoders of a
  /// command buffer by their tracked resources).
  func blitEncoder(for resource: MTLResource, range: Range<Int>, and other: MTLResource? = nil,
                   otherRange: Range<Int> = 0..<0) -> MTLBlitCommandEncoder? {
    let id = ObjectIdentifier(resource)
    let otherId = other.map { ObjectIdentifier($0) }
    if overlaps(id, range) || (otherId.map { overlaps($0, otherRange) } ?? false) { endEncoder() }
    if encoder == nil {
      guard let commandBuffer = commandBuffer, let created = commandBuffer.makeBlitCommandEncoder() else { return nil }
      created.label = "grid uploads"
      encoder = created
      blitEncoders += 1
    }
    touched.append((id, range))
    if let otherId = otherId { touched.append((otherId, otherRange)) }
    return encoder
  }

  private func overlaps(_ id: ObjectIdentifier, _ range: Range<Int>) -> Bool {
    touched.contains { $0.0 == id && $0.1.overlaps(range) }
  }

  private func endEncoder() {
    encoder?.endEncoding()
    encoder = nil
    touched.removeAll(keepingCapacity: true)
  }

  func ensureCapacity(_ buffer: GridBuffer, bytes: Int) throws {
    let i = buffer.rawValue
    if bytes <= capacity[i] { return }
    guard let grown = device.makeBuffer(length: bytes, options: .storageModePrivate) else {
      throw GridRendererAllocationError(buffer: "\(buffer)", bytes: bytes)
    }
    grown.label = "grid \(buffer)"
    let kept = 0..<capacity[i]
    if let old = buffers[i], !kept.isEmpty,
       let blit = blitEncoder(for: old, range: kept, and: grown, otherRange: kept) {
      blit.copy(from: old, sourceOffset: 0, to: grown, destinationOffset: 0, size: kept.count)
      growths += 1
    }
    // New space must read as padding: zero surface and summary records are
    // unpublished, and compact voxels get explicit 0xFF padding by delta.
    if let blit = blitEncoder(for: grown, range: kept.upperBound..<bytes) {
      blit.fill(buffer: grown, range: kept.upperBound..<bytes, value: 0)
    }
    buffers[i] = grown
    capacity[i] = bytes
  }

  func write(_ buffer: GridBuffer, dstOffset: Int, source: UnsafeRawBufferPointer) {
    guard source.count > 0, let destination = buffers[buffer.rawValue] else { return }
    guard let (stagingBuffer, at) = stage(source) else {
      preconditionFailure("grid staging ring overrun")
    }
    let range = dstOffset..<(dstOffset + source.count)
    blitEncoder(for: destination, range: range)?.copy(
      from: stagingBuffer, sourceOffset: at, to: destination, destinationOffset: dstOffset, size: source.count)
  }

  func clear(_ buffer: GridBuffer, dstOffset: Int, length: Int) {
    guard length > 0, let destination = buffers[buffer.rawValue] else { return }
    let range = dstOffset..<(dstOffset + length)
    blitEncoder(for: destination, range: range)?.fill(buffer: destination, range: range, value: 0)
  }

  /// Releases every persistent buffer (the store forgets its tables with them).
  func releaseBuffers() {
    for i in buffers.indices { buffers[i] = nil }
    for i in capacity.indices { capacity[i] = 0 }
  }
}

