/*
 * 64 B marker detection record of the visibility grid contract.
 *
 * Little-endian. Written by the native marker library, carried after the 8 B
 * payload prefix of ingest message kind 5 and decoded by
 * `lib/visibility_grid_contract/marker_detection.dart`. The ingest message
 * header carries the grey image timestamp and the camera pose at it.
 */
#ifndef CAPTURE3D_MARKER_DETECTION_RECORD_H
#define CAPTURE3D_MARKER_DETECTION_RECORD_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
  C3_MARKER_DICTIONARY_ARUCO_MIP_36H12 = 0,

  C3_MARKER_FLAG_REJECTED_SMALL = 1,
  C3_MARKER_FLAG_REJECTED_BLUR = 2,
  C3_MARKER_FLAG_REJECTED_OBLIQUE = 4,
  /* Not a rejection: the pose may be the IPPE mirror of the true one
   * (small marker seen nearly head-on); corners are valid, the rotation is
   * not confident. */
  C3_MARKER_FLAG_POSE_AMBIGUOUS = 8,
  C3_MARKER_FLAGS_REJECTED = 7,

  C3_MARKER_DETECTION_RECORD_BYTES = 64,
  C3_MARKER_DETECTIONS_PREFIX_BYTES = 8
};

#pragma pack(push, 1)
typedef struct C3MarkerDetectionRecord {
  uint16_t marker_id;
  uint8_t dictionary;
  uint8_t flags;
  float corners_px[8];          /* x0 y0 x1 y1 x2 y2 x3 y3, aruco order */
  float rvec_camera_from_marker[3];
  float tvec_camera_from_marker_m[3];
  uint16_t reprojection_error_px_f16; /* IEEE 754 binary16 */
  uint8_t viewing_angle_deg;
  uint8_t sharpness;
} C3MarkerDetectionRecord;

/* Payload prefix of ingest kind 5, followed by `count` records. */
typedef struct C3MarkerDetectionsPrefix {
  uint16_t count;
  uint16_t image_width;
  uint16_t image_height;
  uint16_t reserved;
} C3MarkerDetectionsPrefix;
#pragma pack(pop)

#ifdef __cplusplus
}
#define C3_LAYOUT_ASSERT static_assert
#else
#define C3_LAYOUT_ASSERT _Static_assert
#endif

C3_LAYOUT_ASSERT(sizeof(C3MarkerDetectionRecord) == 64, "marker record size");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, dictionary) == 2, "dictionary");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, flags) == 3, "flags");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, corners_px) == 4, "corners");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, rvec_camera_from_marker) == 36, "rvec");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, tvec_camera_from_marker_m) == 48, "tvec");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, reprojection_error_px_f16) == 60, "reprojection");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, viewing_angle_deg) == 62, "viewing angle");
C3_LAYOUT_ASSERT(offsetof(C3MarkerDetectionRecord, sharpness) == 63, "sharpness");
C3_LAYOUT_ASSERT(sizeof(C3MarkerDetectionsPrefix) == 8, "marker prefix size");

#endif /* CAPTURE3D_MARKER_DETECTION_RECORD_H */
