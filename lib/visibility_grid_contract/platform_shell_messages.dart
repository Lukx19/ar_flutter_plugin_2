/// Channel names and the complete message list between the platform shell
/// (Kotlin now, Swift later), the grid isolate and the UI isolate.
abstract final class VisibilityGridChannels {
  /// Grid isolate pulls ingest messages; the platform replies.
  static const String ingest = 'capture3d/visibility_grid/ingest';

  /// Grid isolate sends renderer deltas to the platform renderer.
  static const String render = 'capture3d/visibility_grid/render';

  /// UI isolate sends display mode and colour tables to the platform.
  static const String display = 'capture3d/visibility_grid/display';

  /// UI isolate starts and stops the platform shell.
  static const String control = 'capture3d/visibility_grid/control';
}

/// Sender of a platform-shell message.
enum PlatformShellEndpoint { gridIsolate, uiIsolate, platform }

/// One row of the platform-shell message list.
final class PlatformShellMessage {
  const PlatformShellMessage({
    required this.name,
    required this.from,
    required this.to,
    required this.channel,
    required this.maxBytes,
    required this.rate,
  });

  final String name;
  final PlatformShellEndpoint from;
  final PlatformShellEndpoint to;

  /// Channel name, or `null` for an existing channel kept unchanged.
  final String? channel;

  /// Largest message in bytes, or 0 when the existing contract defines it.
  final int maxBytes;
  final String rate;
}

/// Every message crossing the platform boundary for the visibility grid.
const List<PlatformShellMessage> platformShellMessages = [
  PlatformShellMessage(
    name: 'ingest pull request',
    from: PlatformShellEndpoint.gridIsolate,
    to: PlatformShellEndpoint.platform,
    channel: VisibilityGridChannels.ingest,
    maxBytes: 8,
    rate: 'one in flight',
  ),
  PlatformShellMessage(
    name: 'ingest message (kinds 0-6)',
    from: PlatformShellEndpoint.platform,
    to: PlatformShellEndpoint.gridIsolate,
    channel: VisibilityGridChannels.ingest,
    maxBytes: 262144,
    rate: 'depth 4-5 Hz, points 8 Hz, markers 5-10 Hz, photo per shot',
  ),
  PlatformShellMessage(
    name: 'renderer delta',
    from: PlatformShellEndpoint.gridIsolate,
    to: PlatformShellEndpoint.platform,
    channel: VisibilityGridChannels.render,
    maxBytes: 65536,
    rate: 'per fusion batch, byte budget',
  ),
  PlatformShellMessage(
    name: 'display mode',
    from: PlatformShellEndpoint.uiIsolate,
    to: PlatformShellEndpoint.platform,
    channel: VisibilityGridChannels.display,
    maxBytes: 32,
    rate: 'on change',
  ),
  PlatformShellMessage(
    name: 'colour table',
    from: PlatformShellEndpoint.uiIsolate,
    to: PlatformShellEndpoint.platform,
    channel: VisibilityGridChannels.display,
    maxBytes: 1036,
    rate: 'on change',
  ),
  PlatformShellMessage(
    name: 'shell control (start, stop, marker detection, recording)',
    from: PlatformShellEndpoint.uiIsolate,
    to: PlatformShellEndpoint.platform,
    channel: VisibilityGridChannels.control,
    maxBytes: 1024,
    rate: 'on change',
  ),
  PlatformShellMessage(
    name: 'pose_batch_v2 (unchanged)',
    from: PlatformShellEndpoint.platform,
    to: PlatformShellEndpoint.uiIsolate,
    channel: null,
    maxBytes: 236,
    rate: '30 Hz, one in flight',
  ),
  PlatformShellMessage(
    name: 'shutter and durable terminal (NativeCaptureWireV2, unchanged)',
    from: PlatformShellEndpoint.uiIsolate,
    to: PlatformShellEndpoint.platform,
    channel: null,
    maxBytes: 0,
    rate: 'per shot',
  ),
];

/// Byte layout of one existing `pose_batch_v2` sample (236 B), reused
/// unchanged by the grid. Mirrors `PackedPoseWireV2.kt` and
/// `ARFramePose.fromPacked`.
abstract final class PoseBatchSampleLayout {
  static const String version = 'pose_batch_v2';
  static const int bytes = 236;
  static const int sequence = 0; // i64
  static const int systemTimestampMs = 8; // i64
  static const int sensorTimestampNs = 16; // i64
  static const int bindingGeneration = 24; // i64
  static const int groupGeneration = 32; // i64
  static const int confidence = 40; // f32
  static const int flags = 44; // i32: state & 0xff, bit 8 tracking
  static const int source = 48; // i32: 0 live anchor, 1 synthetic
  static const int openCvPosition = 52; // f32 x3
  static const int openCvRotation = 64; // f32 x4
  static const int openCvCameraToWorld = 80; // f32 x16 column-major
  static const int trackingPosition = 144; // f32 x3
  static const int trackingRotation = 156; // f32 x4
  static const int trackingCameraToWorld = 172; // f32 x16 column-major
}
