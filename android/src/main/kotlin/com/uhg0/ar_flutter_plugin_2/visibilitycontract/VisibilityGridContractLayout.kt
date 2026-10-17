package com.uhg0.ar_flutter_plugin_2.visibilitycontract

/**
 * Byte layouts of the visibility grid platform boundary contract. Every
 * record is little-endian; offsets are normative and mirror the Dart codecs in
 * `lib/visibility_grid_contract/` and the C headers under `native/`. Golden
 * bytes shared with Dart live in `test_assets/visibility_grid/`.
 */
internal fun contractMagic(tag: String): Int {
    require(tag.length == 4)
    return tag[0].code or (tag[1].code shl 8) or (tag[2].code shl 16) or (tag[3].code shl 24)
}

internal object VisibilityGridChannels {
    const val INGEST = "capture3d/visibility_grid/ingest"
    const val RENDER = "capture3d/visibility_grid/render"
    const val DISPLAY = "capture3d/visibility_grid/display"
    const val CONTROL = "capture3d/visibility_grid/control"
}

internal object IngestMessageLayout {
    val MAGIC = contractMagic("C3VI")
    const val VERSION = 1
    const val HEADER_BYTES = 160
    const val MAX_MESSAGE_BYTES = 262_144

    const val KIND = 6
    const val SOURCE_FLAGS = 7
    const val SEQUENCE = 8
    const val PAYLOAD_BYTES = 12
    const val SAMPLE_TIMESTAMP_NS = 16
    const val FRAME_TIMESTAMP_NS = 24
    const val ACQUIRE_TIME_NS = 32
    const val SEND_TIME_NS = 40
    const val TRACKING_STATE = 48
    const val POSE_MATCH = 49
    const val GROUP_EPOCH = 50
    const val POSE_GAP_US = 52
    const val CAMERA_TO_WORLD = 56
    const val INTRINSICS = 120
    const val WIDTH = 136
    const val HEIGHT = 138
    const val ROW_STRIDE = 140
    const val DEPTH_UNIT = 142
    const val CONFIDENCE_FORMAT = 143
    const val DROPPED = 144
    const val RESERVED = 148

    const val KIND_IDLE = 0
    const val KIND_DEPTH_IMAGE = 1
    const val KIND_POINT_CLOUD = 2
    const val KIND_AGGREGATED_VOXELS = 3
    const val KIND_PHOTO_SAVED = 4
    const val KIND_MARKER_DETECTIONS = 5
    const val KIND_SESSION_EVENT = 6

    /**
     * Anchor pose update of pose stability (plan 2.14). Travels on the
     * pose tracking channel, not through the ingest pull handler, so it is
     * never part of a pull kind mask.
     */
    const val KIND_ANCHOR_POSE_UPDATE = 7

    /**
     * Marker tie points of one saved photo (grid integration amendment):
     * payload is the 64 B `C3TB` batch header and 64 B native tie point
     * records; queued in order, never replaced by a fresher message.
     */
    const val KIND_PHOTO_TIE_POINTS = 8

    const val SOURCE_DEPTH = 1
    const val SOURCE_POINTS = 2
    const val SOURCE_LIDAR_CLASS = 4
    const val SOURCE_SYNTHETIC = 8

    const val POSE_MATCH_EXACT = 0
    const val POSE_MATCH_INTERPOLATED = 1
    const val POSE_MATCH_NEAREST_WITHIN_20_MS = 2
    const val POSE_MATCH_MISSING = 3

    const val DEPTH_UNIT_U16_MM = 0
    const val DEPTH_UNIT_F32_M = 1
    const val CONFIDENCE_NONE = 0
    const val CONFIDENCE_U8 = 1

    const val POINT_COUNT_BYTES = 4
    const val POINT_BYTES = 20
    const val VOXEL_COUNT_BYTES = 4
    const val VOXEL_BYTES = 28
    const val MARKER_PREFIX_BYTES = 8
    const val SESSION_EVENT_BYTES = 16

    const val SESSION_TRACKING_LOST = 0
    const val SESSION_TRACKING_REGAINED = 1
    const val SESSION_AR_RESTARTED = 2
    const val SESSION_DEPTH_MODE_CHANGED = 3
    const val SESSION_RECORDING_STARTED = 4
    const val SESSION_RECORDING_STOPPED = 5

    /**
     * ARCore tracking may have been reset (additive amendment): the world
     * frame of earlier samples can differ from later ones. Value: low byte
     * one of the `TRACKING_RESET_` causes, bits 8 and up a cause detail
     * (failure reason code, or the anchor jump in millimetres).
     */
    const val SESSION_TRACKING_RESET = 6

    /** The AR session resumed after a pause (additive amendment). Value: frame gap in nanoseconds. */
    const val SESSION_AR_RESUMED = 7

    /**
     * Every acquired depth image is refused, or depth is accepted again after
     * such a run (additive amendment 2026-10-12). Value: low byte one of the
     * `DEPTH_REFUSAL_` reasons ([DEPTH_REFUSAL_NONE] = accepted again), bits 8
     * and up the consecutive refused images.
     */
    const val SESSION_DEPTH_REFUSED = 8

    const val DEPTH_REFUSAL_NONE = 0

    /** A non-positive depth image or frame timestamp. */
    const val DEPTH_REFUSAL_INVALID_TIMESTAMP = 1

    /** The depth image timestamp is ahead of its frame by more than the clock offset tolerance. */
    const val DEPTH_REFUSAL_IMAGE_AHEAD_OF_FRAME = 2

    const val TRACKING_RESET_AR_RESUMED = 1
    const val TRACKING_RESET_TRACKING_FAILURE = 2
    const val TRACKING_RESET_ANCHOR_JUMP = 3

    val PULL_REQUEST_MAGIC = contractMagic("C3VP")
    const val PULL_REQUEST_BYTES = 8

    /** u16 accepted kind mask (bit k accepts kind k); byte 7 was reserved zero before kind 8. */
    const val PULL_REQUEST_MASK = 6
}

internal object PhotoSavedLayout {
    const val BYTES = 128
    const val PHOTO_ID = 0
    const val ATTEMPT_UUID = 32
    const val EXPOSURE_TIMESTAMP_NS = 48
    const val CAMERA_TO_WORLD_ROWS = 56
    const val INTRINSICS = 104
    const val WIDTH_DIV_8 = 120
    const val HEIGHT_DIV_8 = 122
    const val SHARPNESS = 124
    const val FLAGS = 126
    const val GROUP_EPOCH_LOW = 127
    const val FLAG_AUTOMATIC = 1
    const val FLAG_BLUR_REJECTED = 2
}

internal object MarkerDetectionLayout {
    const val BYTES = 64
    const val MARKER_ID = 0
    const val DICTIONARY = 2
    const val FLAGS = 3
    const val CORNERS = 4
    const val RVEC = 36
    const val TVEC = 48
    const val REPROJECTION_ERROR_F16 = 60
    const val VIEWING_ANGLE = 62
    const val SHARPNESS = 63
    const val DICTIONARY_ARUCO_MIP_36H12 = 0
    const val FLAG_REJECTED_SMALL = 1
    const val FLAG_REJECTED_BLUR = 2
    const val FLAG_REJECTED_OBLIQUE = 4
    /** Not a rejection: the pose may be the IPPE mirror of the true one. */
    const val FLAG_POSE_AMBIGUOUS = 8
}

internal object RendererDeltaLayout {
    val MAGIC = contractMagic("C3RD")
    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val MAX_MESSAGE_BYTES = 65_536
    const val KIND = 6
    const val GRID_VERSION = 8
    const val FIRST_SLOT = 12
    const val RECORD_COUNT = 16
    const val BUFFER_CAPACITY = 20
    const val BRICK_KEY = 24

    const val KIND_SURFACE_SLICE = 1
    const val KIND_BRICK_SUMMARIES = 2
    const val KIND_OCCUPANCY_MASKS = 3
    const val KIND_REGION_SUMMARIES = 4
    const val KIND_FREE_SLOTS = 5
    const val KIND_RESET = 6
    const val KIND_FRAME_END = 7
    const val KIND_COMPACT_VOXELS = 8

    fun recordBytes(kind: Int): Int = when (kind) {
        KIND_SURFACE_SLICE -> SurfaceInstanceLayout.BYTES
        KIND_BRICK_SUMMARIES -> BrickSummaryLayout.BYTES
        KIND_OCCUPANCY_MASKS -> OCCUPANCY_MASK_BYTES
        KIND_REGION_SUMMARIES -> RegionSummaryLayout.BYTES
        KIND_FREE_SLOTS -> FREE_SLOT_RANGE_BYTES
        KIND_RESET, KIND_FRAME_END -> 0
        KIND_COMPACT_VOXELS -> COMPACT_VOXEL_BYTES
        else -> -1
    }

    const val OCCUPANCY_MASK_BYTES = 64
    const val COMPACT_VOXEL_BYTES = 8
    const val FREE_SLOT_RANGE_BYTES = 8
}

internal object SurfaceInstanceLayout {
    const val BYTES = 40
    const val POSITION = 0
    const val NORMAL = 12
    const val SIZE_F16 = 16
    const val SOURCE = 18
    const val FLAGS = 19
    const val COVERAGE = 20
    const val COMPLETION = 21
    const val CONFIDENCE = 22
    const val AGE = 23
    const val BINS = 24
    const val STABLE_ID = 36
    const val FLAG_PUBLISHED = 1
    const val FLAG_PAGED = 2
    const val FLAG_SELECTED = 4
}

internal object BrickSummaryLayout {
    const val BYTES = 16
    const val ORIGIN = 0
    const val SURFACE_COUNT = 6
    const val COMPLETE_COUNT = 8
    const val MEAN_COVERAGE = 10
    const val RESIDENCY = 11
    const val LAST_UPDATE = 12
}

internal object RegionSummaryLayout {
    const val BYTES = 16
    const val KEY = 0
    const val BRICK_COUNT = 6
    const val SURFACE_COUNT = 8
    const val COMPLETION = 12
    const val RESIDENCY = 13
}

internal object DisplayModeLayout {
    val MAGIC = contractMagic("C3DM")
    const val VERSION = 1
    const val BYTES = 32
    const val BASE_LAYER = 6
    const val OVERLAY_BITS = 7
    const val COLOUR_ATTRIBUTE = 8
    const val SIZE_RULE = 9
    const val SPIKE_NEAREST_K = 10
    const val POINT_SIZE = 12
    const val COLOUR_TABLE_GENERATION = 16

    /** u8 minimum surface quality (confidence byte); reserved zero before, so 0 draws every surface. */
    const val MIN_QUALITY = 20
}

internal object ColourTableLayout {
    val MAGIC = contractMagic("C3CT")
    const val VERSION = 1
    const val HEADER_BYTES = 12
    const val ENTRIES = 256
    const val BYTES = HEADER_BYTES + ENTRIES * 4
    const val ATTRIBUTE = 6
    const val GENERATION = 8
}

internal object ShellControlLayout {
    val MAGIC = contractMagic("C3GC")
    const val VERSION = 1
    const val BYTES = 32
    const val KIND = 6
    const val FLAGS = 7
    const val DEPTH_SOURCE_PREFERENCE = 8
    const val RUN_MODE = 9
    const val MARKER_DETECTION_HZ = 10
    const val MARKER_SIZE_METRES = 12
    const val GROUP_EPOCH = 16
    const val KIND_START = 1
    const val KIND_STOP = 2
    const val KIND_SET_MARKER_DETECTION = 3
    const val KIND_SET_RECORDING = 4
    const val FLAG_MARKER_DETECTION = 1
    const val FLAG_RECORDING = 2
    const val FLAG_RENDERER_OUTPUT = 4
    const val FLAG_POINT_CLOUD = 8
}

internal object IngestRecordingLayout {
    val MAGIC = contractMagic("C3IR")
    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val DEVICE_MODEL_HASH = 8
    const val CREATED_TIME_NS = 16
    const val MESSAGE_COUNT = 24
    const val LENGTH_PREFIX_BYTES = 4
}

/**
 * Status and error codes sent to Dart, which maps them to localized text.
 * Values mirror `NativeStatusCode` in Dart; never renumber, only append.
 */
internal enum class NativeStatusCode(val code: Int) {
    OK(0),
    TRACKING_LOST(1),
    TRACKING_LIMITED_INSUFFICIENT_LIGHT(2),
    TRACKING_LIMITED_EXCESSIVE_MOTION(3),
    TRACKING_LIMITED_INSUFFICIENT_FEATURES(4),
    AR_SESSION_RESTARTED(5),
    AR_SESSION_UNSUPPORTED(6),
    CAMERA_PERMISSION_DENIED(7),
    CAMERA_UNAVAILABLE(8),
    DEPTH_UNSUPPORTED(100),
    DEPTH_TEMPORARILY_UNAVAILABLE(101),
    DEPTH_POSE_MISSING(102),
    DEPTH_STALE(103),
    POINTS_NON_FINITE_DROPPED(104),
    POINTS_ONLY_MODE(105),
    PHOTO_SAVE_FAILED(200),
    PHOTO_STORAGE_FULL(201),
    PHOTO_BLUR_REJECTED(202),
    MARKER_DETECTOR_UNAVAILABLE(300),
    MARKER_REJECTED_SMALL(301),
    MARKER_REJECTED_BLUR(302),
    MARKER_REJECTED_OBLIQUE(303),
    MARKER_MOVED_BETWEEN_GROUPS(304),
    RENDERER_UNAVAILABLE(400),
    RENDERER_BUFFER_FULL(401),
    CONTRACT_VERSION_MISMATCH(500),
    CONTRACT_MESSAGE_MALFORMED(501),
    INGEST_QUEUE_OVERFLOW(502),
    RECORDING_WRITE_FAILED(503),
}
