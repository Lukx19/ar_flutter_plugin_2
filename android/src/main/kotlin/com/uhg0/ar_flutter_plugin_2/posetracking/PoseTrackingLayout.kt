package com.uhg0.ar_flutter_plugin_2.posetracking

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic

/**
 * Byte layouts of the anchor pose tracking channel, mirrored exactly from
 * `capture3d/lib/capture/visibility_grid/pose_stability/pose_tracking_messages.dart`.
 * Every value is little-endian.
 *
 * The grid isolate sends a [PoseQueryLayout] query (at most 10 Hz); the
 * platform replies with a 160 B ingest header of kind
 * [ANCHOR_POSE_UPDATE_KIND] followed by the [AnchorPoseUpdateLayout] payload.
 */
internal object PoseTrackingLayout {
    const val CHANNEL = "capture3d/visibility_grid/pose_tracking"

    /** Ingest kind of an anchor pose update. */
    const val ANCHOR_POSE_UPDATE_KIND = IngestMessageLayout.KIND_ANCHOR_POSE_UPDATE
}

/** Anchor kinds of update and creation records. */
internal object AnchorKind {
    const val SUBMAP = 0
    const val PHOTO = 1
    const val KEYFRAME = 2
}

/** Tracking byte of an update record. */
internal object AnchorTracking {
    const val TRACKING = 0
    const val PAUSED = 1
    const val STOPPED = 2

    /** Detached by the tracker at the anchor limit after a long stable pose; the pose is final. */
    const val DETACHED_BY_LIMIT = 3
}

/** Status byte of a creation record. */
internal object AnchorCreation {
    const val CREATED = 0
    const val NOT_TRACKING = 1
    const val FAILED = 2
    const val OVER_LIMIT = 3
}

/** Photo anchors are numbered by the platform from 1; keyframe anchors by the grid isolate from [FIRST_KEYFRAME_ID]. */
internal object AnchorIds {
    const val FIRST_KEYFRAME_ID = 0x40000000
}

/**
 * Query from the grid isolate: 24 B header plus keyframe requests of 56 B
 * (anchor id u32, reserved u32, subject id i64, capture ns i64, camera-to-world
 * t xyz f32, q xyzw f32, reserved u32).
 */
internal object PoseQueryLayout {
    val MAGIC = contractMagic("C3PQ")
    const val VERSION = 1
    const val HEADER_BYTES = 24
    const val REQUEST_BYTES = 56
    const val MAX_REQUESTS = 64
    const val MAX_BYTES = HEADER_BYTES + MAX_REQUESTS * REQUEST_BYTES

    const val VERSION_OFFSET = 4
    const val FLAGS_OFFSET = 6
    const val COUNT_OFFSET = 8
    const val GROUP_EPOCH_OFFSET = 10
    const val ANCHOR_LIMIT_OFFSET = 12
    const val DETACH_AFTER_OFFSET = 16

    /** Tracking on: create and read anchors. Off detaches every anchor. */
    const val FLAG_TRACKING = 1

    /** Detach every anchor before handling the requests (new group). */
    const val FLAG_DETACH_ALL = 2

    const val REQUEST_ANCHOR_ID_OFFSET = 0
    const val REQUEST_SUBJECT_OFFSET = 8
    const val REQUEST_CAPTURE_NS_OFFSET = 16
    const val REQUEST_POSE_OFFSET = 24

    /** Platform default of a zero anchor limit. */
    const val DEFAULT_ANCHOR_LIMIT = 2000

    /** Platform default of a zero detach-after-stable time. */
    const val DEFAULT_DETACH_AFTER_SECONDS = 120
}

/**
 * Reply payload after the ingest header of kind 7: u16 update count, u16
 * creation count, update records (56 B), then creation records (40 B).
 *
 * Update record: anchor id u32, kind u8, tracking u8, reserved u16,
 * anchor-to-world f32 x12 (rows 0-2 of the 4x4: r00 r01 r02 tx, r10 ...).
 *
 * Creation record: anchor id u32, kind u8, status u8, reserved u16, subject id
 * i64, capture timestamp ns i64, created at frame ns i64, reserved u32 x2.
 */
internal object AnchorPoseUpdateLayout {
    /** Header reserved word: anchors attached (u32). */
    const val TRACKED_ANCHORS_OFFSET = IngestMessageLayout.RESERVED

    /** Header reserved word: longest per-frame anchor read since the last reply (u32 micros). */
    const val ANCHOR_READ_MAX_MICROS_OFFSET = IngestMessageLayout.RESERVED + 4

    /** Header reserved word: longest AR frame interval since the last reply (u32 micros). */
    const val FRAME_INTERVAL_MAX_MICROS_OFFSET = IngestMessageLayout.RESERVED + 8

    const val PREFIX_BYTES = 4
    const val UPDATE_BYTES = 56
    const val CREATION_BYTES = 40

    /** Updates per reply; the rest follow in the next reply. */
    const val MAX_UPDATES = 1024
    const val MAX_CREATIONS = 64
    const val MAX_MESSAGE_BYTES =
        IngestMessageLayout.HEADER_BYTES + PREFIX_BYTES + MAX_UPDATES * UPDATE_BYTES + MAX_CREATIONS * CREATION_BYTES

    const val UPDATE_KIND_OFFSET = 4
    const val UPDATE_TRACKING_OFFSET = 5
    const val UPDATE_MATRIX_OFFSET = 8

    const val CREATION_KIND_OFFSET = 4
    const val CREATION_STATUS_OFFSET = 5
    const val CREATION_SUBJECT_OFFSET = 8
    const val CREATION_CAPTURE_NS_OFFSET = 16
    const val CREATION_FRAME_NS_OFFSET = 24

    fun payloadBytes(updates: Int, creations: Int): Int =
        PREFIX_BYTES + updates * UPDATE_BYTES + creations * CREATION_BYTES
}
