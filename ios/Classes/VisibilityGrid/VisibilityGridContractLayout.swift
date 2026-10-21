import Foundation

// Byte layouts of the visibility grid platform boundary contract (plan
// sections 2.4-2.12, amendments 2.16), mirrored from the Android
// `visibilitycontract/VisibilityGridContractLayout.kt` and
// `posetracking/PoseTrackingLayout.kt`. Every record is little-endian;
// offsets are normative. Golden bytes shared with Dart and Kotlin live in
// `test_assets/visibility_grid/`.

/// Four ASCII characters as a little-endian u32 (`C3VI` -> 0x49563343).
func contractMagic(_ tag: StaticString) -> UInt32 {
  precondition(tag.utf8CodeUnitCount == 4)
  let p = tag.utf8Start
  return UInt32(p[0]) | UInt32(p[1]) << 8 | UInt32(p[2]) << 16 | UInt32(p[3]) << 24
}

enum VisibilityGridChannels {
  static let ingest = "capture3d/visibility_grid/ingest"
  static let control = "capture3d/visibility_grid/control"
  static let poseTracking = "capture3d/visibility_grid/pose_tracking"
}

enum IngestMessageLayout {
  static let magic = contractMagic("C3VI")
  /// 2 (coordinate frame migration U4b): the header camera pose and the
  /// photo saved rows are `T_world_cam` (OpenCV camera axes,
  /// `opencv_c2w_v1`); version 1 carried the ARKit camera transform axes.
  static let version = 2
  static let headerBytes = 160
  static let maxMessageBytes = 262_144

  static let kind = 6
  static let sourceFlags = 7
  static let sequence = 8
  static let payloadBytes = 12
  static let sampleTimestampNs = 16
  static let frameTimestampNs = 24
  static let acquireTimeNs = 32
  static let sendTimeNs = 40
  static let trackingState = 48
  static let poseMatch = 49
  static let groupEpoch = 50
  static let poseGapUs = 52
  static let cameraToWorld = 56
  static let intrinsics = 120
  static let width = 136
  static let height = 138
  static let rowStride = 140
  static let depthUnit = 142
  static let confidenceFormat = 143
  static let dropped = 144
  static let reserved = 148

  static let kindIdle = 0
  static let kindDepthImage = 1
  static let kindPointCloud = 2
  static let kindAggregatedVoxels = 3
  static let kindPhotoSaved = 4
  static let kindMarkerDetections = 5
  static let kindSessionEvent = 6
  static let kindAnchorPoseUpdate = 7
  static let kindPhotoTiePoints = 8
  static let kindCount = 9

  static let sourceDepth = 1
  static let sourcePoints = 2
  static let sourceLidarClass = 4
  static let sourceSynthetic = 8

  static let poseMatchExact = 0
  static let poseMatchInterpolated = 1
  static let poseMatchNearestWithin20Ms = 2
  static let poseMatchMissing = 3

  static let depthUnitU16Mm = 0
  static let depthUnitF32M = 1
  static let confidenceNone = 0
  static let confidenceU8 = 1

  static let pointCountBytes = 4
  static let pointBytes = 20
  static let sessionEventBytes = 16

  static let sessionTrackingLost = 0
  static let sessionTrackingRegained = 1
  static let sessionArRestarted = 2
  static let sessionDepthModeChanged = 3
  static let sessionRecordingStarted = 4
  static let sessionRecordingStopped = 5
  static let sessionTrackingReset = 6
  static let sessionArResumed = 7
  static let sessionDepthRefused = 8

  static let trackingResetArResumed = 1
  static let trackingResetTrackingFailure = 2
  static let trackingResetAnchorJump = 3

  static let pullRequestMagic = contractMagic("C3VP")
  static let pullRequestBytes = 8
  /// u16 accepted kind mask (bit k accepts kind k).
  static let pullRequestMask = 6
}

/// Tracking state byte (header offset 48), mirrored by `IngestTrackingState`
/// in the plugin's Dart `visibility_grid_shell` library.
enum IngestTrackingState {
  static let stopped = 0
  static let tracking = 1
  static let paused = 2
}

enum PhotoSavedLayout {
  static let bytes = 128
  static let photoId = 0
  static let attemptUuid = 32
  static let exposureTimestampNs = 48
  static let cameraToWorldRows = 56
  static let intrinsics = 104
  static let widthDiv8 = 120
  static let heightDiv8 = 122
  static let sharpness = 124
  static let flags = 126
  static let groupEpochLow = 127
  static let flagAutomatic = 1
  static let flagBlurRejected = 2
}

/// Shot image extension of the photo saved event (amendment 2026-10-15):
/// 32 B appended to the 128 B event in a kind 4 payload of 160 B when the
/// photo is one image of an iOS burst or quality shot. Each image's 128 B
/// event carries its own frame's pose, intrinsics and exposure time.
/// Readers of the 128 B event ignore it; Android never writes it.
enum PhotoShotImageLayout {
  static let magic = contractMagic("C3SI")
  static let version = 1
  static let bytes = 32
  static let shotId = 8
  static let imageIndex = 24
  static let imageCount = 25
  static let shotMode = 26
  static let flags = 28
  static let modeSingle = 0
  static let modeBurst = 1
  static let modeQuality = 2
  static let flagTrackingNotNormal = 1
  static let flagVideoFrameFallback = 2
}

enum ShellControlLayout {
  static let magic = contractMagic("C3GC")
  static let version = 1
  static let bytes = 32
  static let kind = 6
  static let flags = 7
  static let depthSourcePreference = 8
  static let runMode = 9
  static let markerDetectionHz = 10
  static let markerSizeMetres = 12
  static let groupEpoch = 16
  static let kindStart = 1
  static let kindStop = 2
  static let kindSetMarkerDetection = 3
  static let kindSetRecording = 4
  static let flagMarkerDetection = 1
  static let flagRecording = 2
  static let flagRendererOutput = 4
  static let flagPointCloud = 8
}

enum IngestRecordingLayout {
  static let magic = contractMagic("C3IR")
  /// 2: recordings of ingest contract version 2 messages.
  static let version = 2
  static let headerBytes = 32
  static let deviceModelHash = 8
  static let createdTimeNs = 16
  static let messageCount = 24
  static let lengthPrefixBytes = 4
}

/// Status and error codes sent to Dart (`NativeStatusCode`); never renumber.
enum NativeStatusCode: Int32 {
  case ok = 0
  case trackingLost = 1
  case arSessionRestarted = 5
  case arSessionUnsupported = 6
  case depthUnsupported = 100
  case pointsOnlyMode = 105
  case markerDetectorUnavailable = 300
  case contractVersionMismatch = 500
  case contractMessageMalformed = 501
  case ingestQueueOverflow = 502
  case recordingWriteFailed = 503
}

// MARK: - Anchor pose tracking (plan 2.14, amendment 2.16 row 9)

enum AnchorKind {
  static let submap = 0
  static let photo = 1
  static let keyframe = 2
}

enum AnchorTracking {
  static let tracking = 0
  static let paused = 1
  static let stopped = 2
  /// Detached by the tracker at the anchor limit after a long stable pose; the pose is final.
  static let detachedByLimit = 3
}

enum AnchorCreation {
  static let created = 0
  static let notTracking = 1
  static let failed = 2
  static let overLimit = 3
}

enum AnchorIds {
  /// Photo anchors are numbered by the platform from 1, keyframe anchors by the grid isolate from here.
  static let firstKeyframeId: UInt32 = 0x4000_0000
}

/// Query of the grid isolate: 24 B header plus 56 B requests.
enum PoseQueryLayout {
  static let magic = contractMagic("C3PQ")
  static let version = 1
  static let headerBytes = 24
  static let requestBytes = 56
  static let maxRequests = 64

  static let versionOffset = 4
  static let flagsOffset = 6
  static let countOffset = 8
  static let groupEpochOffset = 10
  static let anchorLimitOffset = 12
  static let detachAfterOffset = 16

  static let flagTracking = 1
  static let flagDetachAll = 2

  static let requestAnchorIdOffset = 0
  static let requestFlagsOffset = 4
  static let requestFlagSubmap = 1
  static let requestSubjectOffset = 8
  static let requestCaptureNsOffset = 16
  static let requestPoseOffset = 24

  static let defaultAnchorLimit = 2000
  static let defaultDetachAfterSeconds = 120
}

/// Reply payload after a kind 7 ingest header: u16 updates, u16 creations,
/// 56 B update records, then 40 B creation records.
enum AnchorPoseUpdateLayout {
  static let trackedAnchorsOffset = IngestMessageLayout.reserved
  static let anchorReadMaxMicrosOffset = IngestMessageLayout.reserved + 4
  static let frameIntervalMaxMicrosOffset = IngestMessageLayout.reserved + 8

  static let prefixBytes = 4
  static let updateBytes = 56
  static let creationBytes = 40
  static let maxUpdates = 1024
  static let maxCreations = 64
  static let maxMessageBytes =
    IngestMessageLayout.headerBytes + prefixBytes + maxUpdates * updateBytes + maxCreations * creationBytes

  static let updateKindOffset = 4
  static let updateTrackingOffset = 5
  static let updateMatrixOffset = 8

  static let creationKindOffset = 4
  static let creationStatusOffset = 5
  static let creationSubjectOffset = 8
  static let creationCaptureNsOffset = 16
  static let creationFrameNsOffset = 24

  static func payloadBytes(updates: Int, creations: Int) -> Int {
    prefixBytes + updates * updateBytes + creations * creationBytes
  }
}

/// 4-byte little-endian status reply of the binary channels (2.16 row 22).
enum ChannelStatusReply {
  static func data(_ status: Int32) -> Data {
    var value = status.littleEndian
    return Data(bytes: &value, count: 4)
  }
}
