/*
 * C ABI of the shared marker detector library (Android through JNI and
 * dart:ffi, iOS later through Swift and dart:ffi).
 *
 * Detection is aruco_nano on OpenCV (core, imgproc, calib3d, objdetect). The
 * corner refinement, sharpness, viewing angle, distortion and record encoding
 * are plain C++ and build without OpenCV, so a host build
 * (C3MD_WITH_OPENCV=0) runs their tests; there `c3md_detect`,
 * `c3md_estimate_pose`, `c3md_solve_points` and `c3md_detect_tiepoints`
 * return C3MD_ERROR_UNAVAILABLE.
 *
 * Ownership: a detector owns every buffer it returns (records, tie points,
 * timings). The caller views them once and never allocates per call; a
 * detector is not thread-safe, use one per thread.
 *
 * Coordinates: image pixels with the centre of the top-left pixel at (0, 0).
 * Poses are camera-from-marker in the OpenCV camera frame (x right, y down,
 * z forward); the marker frame is aruco's (x right, y up, z out of the
 * printed face), corners in aruco order (top-left, top-right, bottom-right,
 * bottom-left of the printed face).
 */
#ifndef CAPTURE3D_MARKER_DETECTOR_H
#define CAPTURE3D_MARKER_DETECTOR_H

#include <stdint.h>

#include "marker_detection_record.h"

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define C3MD_API __declspec(dllexport)
#else
#define C3MD_API __attribute__((visibility("default")))
#endif

enum {
  C3MD_ABI_VERSION = 1,

  C3MD_OK = 0,
  C3MD_ERROR_ARGUMENT = -1,
  C3MD_ERROR_UNAVAILABLE = -2,
  C3MD_ERROR_INTERNAL = -3,

  C3MD_MAX_MARKERS = 128,
  C3MD_MAX_MARKER_ID = 1023,

  /* Dictionaries. 0 is the contract value of the detection record; the
   * others are proposed extensions of the record's dictionary byte. */
  C3MD_DICTIONARY_ARUCO_MIP_36H12 = C3_MARKER_DICTIONARY_ARUCO_MIP_36H12,
  C3MD_DICTIONARY_APRILTAG_36H11 = 1,
  C3MD_DICTIONARY_4X4_50 = 2,
  C3MD_DICTIONARY_6X6_250 = 3,
  C3MD_DICTIONARY_COUNT = 4,

  /* Pose methods for a single marker. */
  C3MD_POSE_IPPE_SQUARE = 0,
  C3MD_POSE_SQPNP = 1,

  /* Corner refinement of tie points. */
  C3MD_REFINE_NONE = 0,
  C3MD_REFINE_CORNER_SUBPIX = 1,
  C3MD_REFINE_EDGE_LINES = 2,

  /* Tie point flags (match the tie-point observation flags of the session
   * store: subpixel refined, partial, low sharpness) plus bit 3. */
  C3MD_TIE_SUBPIXEL_REFINED = 1,
  C3MD_TIE_PARTIAL = 2,
  C3MD_TIE_LOW_SHARPNESS = 4,
  C3MD_TIE_UNDISTORTED_FIT = 8,

  C3MD_TIE_POINT_BYTES = 64
};

/* Quality gates. A detection failing a gate is still reported with the
 * matching C3_MARKER_FLAG_REJECTED_* flag so feedback can show it. */
typedef struct C3MarkerQualityParams {
  float min_side_px;            /* shortest side; default 16 */
  float min_sharpness;          /* 0..1 edge sharpness; default 0.12 */
  float max_viewing_angle_deg;  /* angle to the marker normal; default 75 */
} C3MarkerQualityParams;

/* One marker on a full-resolution still, 64 B. Corners and centre are in the
 * still's raw pixel coordinates; with distortion set, edges are fitted and
 * the centre (intersection of the diagonals) is computed in the undistorted
 * pinhole image and mapped back. */
#pragma pack(push, 1)
typedef struct C3MarkerTiePoint {
  uint16_t marker_id;
  uint8_t dictionary;
  uint8_t flags;            /* C3MD_TIE_* */
  float corners_px[8];
  float centre_px[2];
  float residual_px;        /* RMS of an IPPE_SQUARE fit, NaN without OpenCV */
  float sharpness;          /* 0..1, steepest edge step over contrast */
  float refine_shift_px;    /* RMS corner move of the refinement */
  uint32_t reserved[2];
} C3MarkerTiePoint;
#pragma pack(pop)

typedef struct C3MarkerDetector C3MarkerDetector;

C3MD_API int32_t c3md_abi_version(void);

/* 1 when the library was built with OpenCV and can detect markers. */
C3MD_API int32_t c3md_detector_available(void);

/* Creates a detector for one dictionary (C3MD_DICTIONARY_*). Returns NULL
 * for an unknown dictionary. Without OpenCV only the geometry calls work. */
C3MD_API C3MarkerDetector* c3md_create(int32_t dictionary);
C3MD_API void c3md_destroy(C3MarkerDetector* detector);

/* Pinhole intrinsics of the images passed next, plus optional distortion
 * (k1, k2, p1, p2, k3; NULL for none). */
C3MD_API int32_t c3md_set_camera(C3MarkerDetector* detector, double fx, double fy, double cx, double cy,
                                 const double* distortion5);

/* Same as c3md_set_camera's distortion, as scalars (dart:ffi callers need
 * no native allocation). */
C3MD_API int32_t c3md_set_distortion(C3MarkerDetector* detector, double k1, double k2, double p1, double p2,
                                     double k3);

/* Detector-owned scratch of at least `bytes` bytes (8-byte aligned), for
 * callers without a native allocator: grey images, PnP inputs and outputs.
 * Valid until the next c3md_scratch call or c3md_destroy; NULL when out of
 * memory. */
C3MD_API uint8_t* c3md_scratch(C3MarkerDetector* detector, int64_t bytes);

/* Printed side (m) of every marker, and of one id (size <= 0 restores the
 * default for that id). */
C3MD_API int32_t c3md_set_marker_size(C3MarkerDetector* detector, double size_m);
C3MD_API int32_t c3md_set_marker_size_for_id(C3MarkerDetector* detector, int32_t marker_id, double size_m);

C3MD_API int32_t c3md_set_quality(C3MarkerDetector* detector, const C3MarkerQualityParams* params);
C3MD_API int32_t c3md_set_pose_method(C3MarkerDetector* detector, int32_t method);

/* Detects markers on a grey image; returns the count (at most
 * C3MD_MAX_MARKERS) or a negative error. Measures sharpness and side. */
C3MD_API int32_t c3md_detect(C3MarkerDetector* detector, const uint8_t* grey, int32_t width, int32_t height,
                             int32_t stride);

/* Estimates camera-from-marker poses of the last detection, the RMS
 * reprojection error and the viewing angle, applies the quality gates and
 * fills the detection records. Returns the number of poses or an error. */
C3MD_API int32_t c3md_estimate_pose(C3MarkerDetector* detector);

/* The 64 B contract records of the last detection (count from c3md_detect).
 * Valid until the next detect. */
C3MD_API const C3MarkerDetectionRecord* c3md_records(const C3MarkerDetector* detector);
C3MD_API int32_t c3md_record_count(const C3MarkerDetector* detector);

/* One PnP over n >= 4 object (x, y, z, metres) to image (x, y, px)
 * correspondences with the camera of c3md_set_camera (image points are
 * undistorted first when distortion is set). method: C3MD_POSE_SQPNP, or
 * C3MD_POSE_IPPE_SQUARE meaning SQPNP refined by Levenberg-Marquardt.
 * out7: rvec[3], tvec[3], RMS reprojection error px. */
C3MD_API int32_t c3md_solve_points(C3MarkerDetector* detector, int32_t n, const double* object_xyz,
                                   const float* image_xy, int32_t method, double* out7);

/* Full-resolution tie points: detection on the image (area-downscaled by
 * search_downscale >= 1 first), corner refinement at full resolution
 * (C3MD_REFINE_*), centre, residual and sharpness. Returns the count; the
 * results stay in c3md_tiepoints until the next call. */
C3MD_API int32_t c3md_detect_tiepoints(C3MarkerDetector* detector, const uint8_t* grey, int32_t width,
                                       int32_t height, int32_t stride, int32_t search_downscale,
                                       int32_t refine_method);
C3MD_API const C3MarkerTiePoint* c3md_tiepoints(const C3MarkerDetector* detector);

/* Last detect, pose and refine times in milliseconds (3 doubles). */
C3MD_API const double* c3md_timings(const C3MarkerDetector* detector);

/* ---- Geometry helpers (no OpenCV) ---------------------------------- */

/* Edge-line refinement of one quadrilateral: each edge is sampled across its
 * middle 76 %, the steepest step along the normal located to sub-pixel, a
 * robust total-least-squares line fitted, corners intersected. `modules` is
 * the marker side in bit cells including the border (8 for 36h12).
 * camera4 (fx, fy, cx, cy) and distortion5 may be NULL; with both set the
 * edge samples are undistorted before fitting and the corners mapped back.
 * Returns C3MD_OK or C3MD_ERROR_ARGUMENT when an edge could not be fitted. */
C3MD_API int32_t c3md_refine_edges(const uint8_t* grey, int32_t width, int32_t height, int32_t stride,
                                   const float* coarse8, int32_t modules, const double* camera4,
                                   const double* distortion5, float* out8);

/* Intersection of the diagonals c0-c2 and c1-c3 (out2). */
C3MD_API int32_t c3md_diagonal_centre(const float* corners8, float* out2);

/* Mean steepest edge step divided by local contrast over three samples per
 * edge (1/px; an ideal step is about 1, Gaussian blur sigma s about 0.4/s). */
C3MD_API float c3md_edge_sharpness(const uint8_t* grey, int32_t width, int32_t height, int32_t stride,
                                   const float* corners8);

/* Angle (degrees, 0..180) between the marker normal and the direction from
 * the marker to the camera, from an OpenCV rvec and tvec. */
C3MD_API float c3md_viewing_angle_deg(const double* rvec3, const double* tvec3);

/* IEEE 754 binary16 conversion used by the records (round to nearest even). */
C3MD_API uint16_t c3md_f16_from_float(float value);
C3MD_API float c3md_f16_to_float(uint16_t bits);

/* Distortion of normalised camera coordinates with (k1, k2, p1, p2, k3) and
 * its iterative inverse (in place on x, y). */
C3MD_API void c3md_distort_normalised(const double* distortion5, double* x, double* y);
C3MD_API void c3md_undistort_normalised(const double* distortion5, double* x, double* y);

/* Encodes one contract record. sharpness is 0..1 and stored as 0..255. */
C3MD_API void c3md_encode_record(uint16_t marker_id, uint8_t dictionary, uint8_t flags, const float* corners8,
                                 const double* rvec3, const double* tvec3, float reprojection_error_px,
                                 float viewing_angle_deg, float sharpness, C3MarkerDetectionRecord* out);

#ifdef __cplusplus
}
#endif

#endif /* CAPTURE3D_MARKER_DETECTOR_H */
