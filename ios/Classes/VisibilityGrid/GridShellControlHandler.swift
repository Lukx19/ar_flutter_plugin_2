import Foundation

/// Configuration of a started shell, from the control start message.
struct IngestShellConfig: Equatable {
  static let depthAutomatic = 0
  static let depthPreferred = 1
  static let depthPointsOnly = 2

  var flags: Int
  var depthSourcePreference: Int
  var runMode: Int
  var markerDetectionHz: Int
  var markerSizeMetres: Float
  var groupEpoch: Int
}

/// What the control handler drives (the ingest shell).
protocol GridShellControlTarget: AnyObject {
  func start(_ config: IngestShellConfig)
  func stop()
  func setMarkerDetection(enabled: Bool, hz: Int, sizeMetres: Float)
  /// Starts or stops the debug recording; false when it cannot.
  func setRecording(_ enabled: Bool) -> Bool
}

/// Handler of the control channel `capture3d/visibility_grid/control`: 32 B
/// shell control messages (start with configuration, stop, set marker
/// detection, set recording) from the UI isolate, each answered with a 4 B
/// little-endian `NativeStatusCode` (0 accepted). Runs on the platform thread.
///
/// A start or set marker detection message that turns marker detection on
/// while [markerDetectorAvailable] is false is refused with
/// `MARKER_DETECTOR_UNAVAILABLE` and changes nothing.
final class GridShellControlHandler {
  private let shell: () -> GridShellControlTarget?
  private let markerDetectorAvailable: () -> Bool

  init(shell: @escaping () -> GridShellControlTarget?, markerDetectorAvailable: @escaping () -> Bool) {
    self.shell = shell
    self.markerDetectorAvailable = markerDetectorAvailable
  }

  /// Channel handler: always replies with a 4 B status.
  func onMessage(_ message: Data?, reply: (Data) -> Void) {
    reply(ChannelStatusReply.data(handle(message).rawValue))
  }

  /// Applies one control message; returns the status code.
  func handle(_ message: Data?) -> NativeStatusCode {
    guard let m = message, m.count >= ShellControlLayout.bytes else { return .contractMessageMalformed }
    let fields = m.withUnsafeBytes { raw -> (UInt32, UInt16, Int, Int, Int, Int, Int, Float, Int32) in
      func u8(_ o: Int) -> Int { Int(raw[o]) }
      return (
        UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self)),
        UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: 4, as: UInt16.self)),
        u8(ShellControlLayout.kind), u8(ShellControlLayout.flags), u8(ShellControlLayout.depthSourcePreference),
        u8(ShellControlLayout.runMode),
        Int(UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: ShellControlLayout.markerDetectionHz, as: UInt16.self))),
        Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: ShellControlLayout.markerSizeMetres, as: UInt32.self))),
        Int32(littleEndian: raw.loadUnaligned(fromByteOffset: ShellControlLayout.groupEpoch, as: Int32.self))
      )
    }
    let (magic, version, kind, flags, depthPreference, runMode, hz, size, epoch) = fields
    guard magic == ShellControlLayout.magic else { return .contractMessageMalformed }
    guard Int(version) == ShellControlLayout.version else { return .contractVersionMismatch }
    // No shell: the plugin is detached from its engine.
    guard let target = shell() else { return .arSessionUnsupported }
    let markersRequested = flags & ShellControlLayout.flagMarkerDetection != 0
    switch kind {
    case ShellControlLayout.kindStart:
      if markersRequested && !markerDetectorAvailable() { return .markerDetectorUnavailable }
      target.start(IngestShellConfig(
        flags: flags, depthSourcePreference: depthPreference, runMode: runMode, markerDetectionHz: hz,
        markerSizeMetres: size, groupEpoch: Int(epoch)
      ))
      if flags & ShellControlLayout.flagRecording != 0 && !target.setRecording(true) { return .recordingWriteFailed }
      return .ok
    case ShellControlLayout.kindStop:
      _ = target.setRecording(false)
      target.stop()
      return .ok
    case ShellControlLayout.kindSetMarkerDetection:
      if markersRequested && !markerDetectorAvailable() { return .markerDetectorUnavailable }
      target.setMarkerDetection(enabled: markersRequested, hz: hz, sizeMetres: size)
      return .ok
    case ShellControlLayout.kindSetRecording:
      return target.setRecording(flags & ShellControlLayout.flagRecording != 0) ? .ok : .recordingWriteFailed
    default:
      return .contractMessageMalformed
    }
  }
}
