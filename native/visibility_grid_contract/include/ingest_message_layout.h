/*
 * Ingest message layout of the visibility grid contract (platform to grid
 * isolate). Little-endian; offsets are normative and mirror
 * `lib/visibility_grid_contract/ingest_message.dart` and
 * `visibilitycontract/VisibilityGridContractLayout.kt`.
 *
 * The header pose is the camera pose at the sample timestamp (depth image or
 * point cloud), matched from a pose history, never the current frame pose.
 */
#ifndef CAPTURE3D_INGEST_MESSAGE_LAYOUT_H
#define CAPTURE3D_INGEST_MESSAGE_LAYOUT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 'C3VI' little-endian. */
#define C3_INGEST_MAGIC 0x49563343u
#define C3_INGEST_VERSION 1u
#define C3_INGEST_MAX_MESSAGE_BYTES 262144u

enum {
  C3_INGEST_KIND_IDLE = 0,
  C3_INGEST_KIND_DEPTH_IMAGE = 1,
  C3_INGEST_KIND_POINT_CLOUD = 2,
  C3_INGEST_KIND_AGGREGATED_VOXELS = 3,
  C3_INGEST_KIND_PHOTO_SAVED = 4,
  C3_INGEST_KIND_MARKER_DETECTIONS = 5,
  C3_INGEST_KIND_SESSION_EVENT = 6,

  C3_INGEST_SOURCE_DEPTH = 1,
  C3_INGEST_SOURCE_POINTS = 2,
  C3_INGEST_SOURCE_LIDAR_CLASS = 4,
  C3_INGEST_SOURCE_SYNTHETIC = 8,

  C3_POSE_MATCH_EXACT = 0,
  C3_POSE_MATCH_INTERPOLATED = 1,
  C3_POSE_MATCH_NEAREST_WITHIN_20_MS = 2,
  C3_POSE_MATCH_MISSING = 3,

  C3_DEPTH_UNIT_U16_MM = 0,
  C3_DEPTH_UNIT_F32_M = 1,
  C3_CONFIDENCE_NONE = 0,
  C3_CONFIDENCE_U8 = 1
};

#pragma pack(push, 1)
/* 160 B header. */
typedef struct C3IngestHeader {
  uint32_t magic;
  uint16_t version;
  uint8_t kind;
  uint8_t source_flags;
  uint32_t sequence;
  uint32_t payload_bytes;
  int64_t sample_timestamp_ns;
  int64_t frame_timestamp_ns;
  int64_t acquire_time_ns;
  int64_t send_time_ns;
  uint8_t tracking_state;
  uint8_t pose_match;
  uint16_t group_epoch;
  int32_t pose_gap_us;
  float camera_to_world[16]; /* column-major, at the sample timestamp */
  float intrinsics[4];       /* fx fy cx cy at the payload image size */
  uint16_t width;
  uint16_t height;
  uint16_t row_stride_bytes;
  uint8_t depth_unit;
  uint8_t confidence_format;
  uint32_t dropped;
  uint32_t reserved[3];
} C3IngestHeader;

/* Kind 2 point, 20 B, after a u32 count. */
typedef struct C3IngestPoint {
  uint32_t id;
  float x, y, z;
  float confidence;
} C3IngestPoint;

/* Kind 3 aggregated voxel, 28 B, after a u32 count. */
typedef struct C3IngestAggregatedVoxel {
  float position_sum[3];
  float normal_sum[3];
  uint32_t sample_count;
} C3IngestAggregatedVoxel;

/* Kind 4 photo saved event, 128 B. */
typedef struct C3PhotoSavedEvent {
  uint8_t photo_id_sha256[32];
  uint8_t attempt_uuid[16];
  int64_t exposure_timestamp_ns;
  float camera_to_world_rows[12]; /* rows 0-2, row 3 implied */
  float intrinsics[4];            /* at the still resolution */
  uint16_t width_div_8;
  uint16_t height_div_8;
  uint16_t sharpness;
  uint8_t flags; /* bit 0 automatic, bit 1 blur rejected */
  uint8_t group_epoch_low;
} C3PhotoSavedEvent;

/* Kind 6 session event, 16 B. */
typedef struct C3SessionEvent {
  uint8_t event;
  uint8_t reserved[7];
  int64_t value;
} C3SessionEvent;
#pragma pack(pop)

#ifdef __cplusplus
}
#define C3_INGEST_ASSERT static_assert
#else
#define C3_INGEST_ASSERT _Static_assert
#endif

C3_INGEST_ASSERT(sizeof(C3IngestHeader) == 160, "ingest header size");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, sequence) == 8, "sequence");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, sample_timestamp_ns) == 16, "sample ts");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, tracking_state) == 48, "tracking");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, pose_match) == 49, "pose match");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, pose_gap_us) == 52, "pose gap");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, camera_to_world) == 56, "pose");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, intrinsics) == 120, "intrinsics");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, width) == 136, "width");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, depth_unit) == 142, "depth unit");
C3_INGEST_ASSERT(offsetof(C3IngestHeader, dropped) == 144, "dropped");
C3_INGEST_ASSERT(sizeof(C3IngestPoint) == 20, "point size");
C3_INGEST_ASSERT(sizeof(C3IngestAggregatedVoxel) == 28, "voxel size");
C3_INGEST_ASSERT(sizeof(C3PhotoSavedEvent) == 128, "photo saved size");
C3_INGEST_ASSERT(offsetof(C3PhotoSavedEvent, exposure_timestamp_ns) == 48, "exposure");
C3_INGEST_ASSERT(offsetof(C3PhotoSavedEvent, intrinsics) == 104, "photo intrinsics");
C3_INGEST_ASSERT(offsetof(C3PhotoSavedEvent, flags) == 126, "photo flags");
C3_INGEST_ASSERT(sizeof(C3SessionEvent) == 16, "session event size");

#endif /* CAPTURE3D_INGEST_MESSAGE_LAYOUT_H */
