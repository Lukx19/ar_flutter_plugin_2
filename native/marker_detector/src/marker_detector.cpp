// Marker detector state and the detection calls of the C ABI: detection,
// single-marker pose, multi-point PnP and full-resolution tie points, on the
// AprilTag 3 backend (third_party/apriltag, C3MD_WITH_APRILTAG) with the
// generated ARUCO_MIP_36h12 family; pose by the IPPE and Levenberg-Marquardt
// solver of marker_pose.cpp. Android and iOS build the same sources.
//
// Built without the backend (host geometry test), the detector keeps its
// configuration and geometry helpers (including c3md_solve_points) but
// detection calls return C3MD_ERROR_UNAVAILABLE.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <new>
#include <vector>

#include "marker_detector.h"
#include "marker_detector_internal.h"
#include "marker_geometry.h"

#ifndef C3MD_WITH_APRILTAG
#define C3MD_WITH_APRILTAG 0
#endif

#include "marker_pose.h"
#if C3MD_WITH_APRILTAG
#include "marker_apriltag.h"
#endif

namespace {

[[maybe_unused]] double nowMs() {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

/// Bit cells per side including the black border.
int modulesOf(int dictionary) {
  switch (dictionary) {
    case C3MD_DICTIONARY_4X4_50:
      return 6;
    case C3MD_DICTIONARY_6X6_250:
      return 8;
    default:
      return 8;  // 36-bit dictionaries: 6 x 6 bits plus the border
  }
}

#if C3MD_WITH_APRILTAG
/// Quad search decimation of live frames: full resolution up to 1280 px
/// wide, half above (decoding and edge refinement stay at full resolution).
float liveDecimation(int width) { return width > 1280 ? 2.0f : 1.0f; }
#endif

[[maybe_unused]] double shortestSide(const float* c) {
  double side = 1e18;
  for (int k = 0; k < 4; k++) {
    const int n = (k + 1) % 4;
    side = std::min(side, static_cast<double>(std::hypot(c[2 * n] - c[2 * k], c[2 * n + 1] - c[2 * k + 1])));
  }
  return side;
}

}  // namespace

struct C3MarkerDetector {
  int dictionary = 0;
  int modules = 8;
  c3md::Camera camera;
  double defaultSize = 0.1;
  float sizes[C3MD_MAX_MARKER_ID + 1] = {};
  C3MarkerQualityParams quality{16.0f, 0.12f, 75.0f};
  int poseMethod = C3MD_POSE_IPPE_SQUARE;

  int count = 0;
  int ids[C3MD_MAX_MARKERS] = {};
  float corners[C3MD_MAX_MARKERS][8] = {};
  float sharpness[C3MD_MAX_MARKERS] = {};
  C3MarkerDetectionRecord records[C3MD_MAX_MARKERS] = {};
  int tieCount = 0;
  C3MarkerTiePoint ties[C3MD_MAX_MARKERS] = {};
  double timings[3] = {0, 0, 0};
  c3md::EdgeScratch scratch;
  std::vector<uint8_t> grey;  // converted bitmap pixels (JNI path)
  std::vector<double> scratch64;  // c3md_scratch, 8-byte units keep doubles aligned

#if C3MD_WITH_APRILTAG
  c3md::TagDetector* tags = nullptr;
  c3md::TagDetection found[C3MD_MAX_MARKERS];

  ~C3MarkerDetector() { delete tags; }
#endif

  double sizeOf(int id) const {
    return id >= 0 && id <= C3MD_MAX_MARKER_ID && sizes[id] > 0 ? sizes[id] : defaultSize;
  }
};

namespace c3md {

std::vector<uint8_t>& greyBuffer(C3MarkerDetector* d) { return d->grey; }

int ensureGreyBuffer(C3MarkerDetector* d, size_t bytes) noexcept {
  if (d == nullptr) return C3MD_ERROR_ARGUMENT;
  if (d->grey.size() >= bytes) return C3MD_OK;
  try {
    d->grey.resize(bytes);  // grows once to the largest still
    return C3MD_OK;
  } catch (...) {
    return C3MD_ERROR_INTERNAL;
  }
}

}  // namespace c3md

extern "C" {

C3MD_API int32_t c3md_abi_version(void) { return C3MD_ABI_VERSION; }

C3MD_API int32_t c3md_detector_available(void) { return C3MD_WITH_APRILTAG ? 1 : 0; }

C3MD_API C3MarkerDetector* c3md_create(int32_t dictionary) {
  if (dictionary < 0 || dictionary >= C3MD_DICTIONARY_COUNT) return nullptr;
  auto* d = new (std::nothrow) C3MarkerDetector();
  if (d == nullptr) return nullptr;
  d->dictionary = dictionary;
  d->modules = modulesOf(dictionary);
#if C3MD_WITH_APRILTAG
  d->tags = c3md::TagDetector::create(dictionary);  // null: dictionary not decoded by AprilTag
  if (d->tags == nullptr) {
    delete d;
    return nullptr;
  }
#endif
  return d;
}

C3MD_API void c3md_destroy(C3MarkerDetector* detector) { delete detector; }

C3MD_API int32_t c3md_set_camera(C3MarkerDetector* d, double fx, double fy, double cx, double cy,
                                 const double* distortion5) {
  if (d == nullptr || !(fx > 0) || !(fy > 0)) return C3MD_ERROR_ARGUMENT;
  d->camera.set(fx, fy, cx, cy, distortion5);
  return C3MD_OK;
}

C3MD_API int32_t c3md_set_distortion(C3MarkerDetector* d, double k1, double k2, double p1, double p2, double k3) {
  if (d == nullptr) return C3MD_ERROR_ARGUMENT;
  const double distortion[5] = {k1, k2, p1, p2, k3};
  d->camera.set(d->camera.fx, d->camera.fy, d->camera.cx, d->camera.cy, distortion);
  return C3MD_OK;
}

C3MD_API uint8_t* c3md_scratch(C3MarkerDetector* d, int64_t bytes) {
  if (d == nullptr || bytes < 0) return nullptr;
  const size_t units = static_cast<size_t>((bytes + 7) / 8) + 1;
  try {
    if (d->scratch64.size() < units) d->scratch64.resize(units);
  } catch (...) {
    return nullptr;
  }
  return reinterpret_cast<uint8_t*>(d->scratch64.data());
}

C3MD_API int32_t c3md_set_marker_size(C3MarkerDetector* d, double size_m) {
  if (d == nullptr || !(size_m > 0)) return C3MD_ERROR_ARGUMENT;
  d->defaultSize = size_m;
  return C3MD_OK;
}

C3MD_API int32_t c3md_set_marker_size_for_id(C3MarkerDetector* d, int32_t marker_id, double size_m) {
  if (d == nullptr || marker_id < 0 || marker_id > C3MD_MAX_MARKER_ID) return C3MD_ERROR_ARGUMENT;
  d->sizes[marker_id] = size_m > 0 ? static_cast<float>(size_m) : 0.0f;
  return C3MD_OK;
}

C3MD_API int32_t c3md_set_quality(C3MarkerDetector* d, const C3MarkerQualityParams* params) {
  if (d == nullptr || params == nullptr) return C3MD_ERROR_ARGUMENT;
  d->quality = *params;
  return C3MD_OK;
}

C3MD_API int32_t c3md_set_pose_method(C3MarkerDetector* d, int32_t method) {
  if (d == nullptr || (method != C3MD_POSE_IPPE_SQUARE && method != C3MD_POSE_SQPNP)) return C3MD_ERROR_ARGUMENT;
  d->poseMethod = method;
  return C3MD_OK;
}

C3MD_API int32_t c3md_detect(C3MarkerDetector* d, const uint8_t* grey, int32_t width, int32_t height,
                             int32_t stride) {
  if (d == nullptr || grey == nullptr || width < 8 || height < 8 || stride < width) return C3MD_ERROR_ARGUMENT;
  d->count = 0;
#if C3MD_WITH_APRILTAG
  const double t0 = nowMs();
  const int n = d->tags->detect(grey, width, height, stride, liveDecimation(width), d->found, C3MD_MAX_MARKERS);
  if (n < 0) {
    d->timings[0] = nowMs() - t0;
    return C3MD_ERROR_INTERNAL;
  }
  const double zero[3] = {0, 0, 0};
  for (int i = 0; i < n; i++) {
    d->ids[i] = d->found[i].id;
    std::memcpy(d->corners[i], d->found[i].corners, sizeof d->corners[i]);
    d->sharpness[i] = static_cast<float>(c3md::edgeSharpness(grey, width, height, stride, d->corners[i]));
    uint8_t flags = 0;
    if (shortestSide(d->corners[i]) < d->quality.min_side_px) flags |= C3_MARKER_FLAG_REJECTED_SMALL;
    if (d->sharpness[i] < d->quality.min_sharpness) flags |= C3_MARKER_FLAG_REJECTED_BLUR;
    c3md::encodeRecord(static_cast<uint16_t>(d->ids[i]), static_cast<uint8_t>(d->dictionary), flags, d->corners[i],
                       zero, zero, NAN, NAN, d->sharpness[i], d->records[i]);
  }
  d->count = n;
  d->timings[0] = nowMs() - t0;
  return n;
#else
  (void)grey;
  return C3MD_ERROR_UNAVAILABLE;
#endif
}

C3MD_API int32_t c3md_estimate_pose(C3MarkerDetector* d) {
  if (d == nullptr) return C3MD_ERROR_ARGUMENT;
#if C3MD_WITH_APRILTAG
  // Both pose methods use IPPE (two solutions) refined by Levenberg-Marquardt.
  const double t0 = nowMs();
  int written = 0;
  for (int i = 0; i < d->count; i++) {
    C3MarkerDetectionRecord& r = d->records[i];
    double rv[3], tv[3], rms;
    c3md::PoseAlternative alternative;
    const double side = d->sizeOf(d->ids[i]);
    if (!c3md::solveSquarePose(d->camera, side / 2, d->corners[i], rv, tv, rms, &alternative)) continue;
    const double angle = c3md::viewingAngleDeg(rv, tv);
    uint8_t flags = r.flags;
    if (angle > d->quality.max_viewing_angle_deg) flags |= C3_MARKER_FLAG_REJECTED_OBLIQUE;
    const double cue = c3md::flipPerspectiveCuePx(0.5 * (d->camera.fx + d->camera.fy), side, tv, angle);
    if (c3md::squarePoseAmbiguous(rms, alternative, cue, angle)) flags |= C3_MARKER_FLAG_POSE_AMBIGUOUS;
    c3md::encodeRecord(r.marker_id, r.dictionary, flags, d->corners[i], rv, tv, static_cast<float>(rms),
                       static_cast<float>(angle), d->sharpness[i], r);
    written++;
  }
  d->timings[1] = nowMs() - t0;
  return written;
#else
  return C3MD_ERROR_UNAVAILABLE;
#endif
}

C3MD_API const C3MarkerDetectionRecord* c3md_records(const C3MarkerDetector* d) {
  return d == nullptr ? nullptr : d->records;
}

C3MD_API int32_t c3md_record_count(const C3MarkerDetector* d) { return d == nullptr ? 0 : d->count; }

C3MD_API int32_t c3md_solve_points(C3MarkerDetector* d, int32_t n, const double* object_xyz, const float* image_xy,
                                   int32_t method, double* out7) {
  if (d == nullptr || n < 4 || object_xyz == nullptr || image_xy == nullptr || out7 == nullptr) {
    return C3MD_ERROR_ARGUMENT;
  }
  // IPPE (planar points) or DLT (n >= 6, non-planar), refined by
  // Levenberg-Marquardt; both methods take the same path.
  (void)method;
  if (n > c3md::kMaxPosePoints) return C3MD_ERROR_ARGUMENT;
  double image[2 * c3md::kMaxPosePoints];
  for (int i = 0; i < 2 * n; i++) image[i] = image_xy[i];
  double rms;
  if (!c3md::solvePose(d->camera, n, object_xyz, image, out7, out7 + 3, rms)) return C3MD_ERROR_INTERNAL;
  out7[6] = rms;
  return C3MD_OK;
}

C3MD_API int32_t c3md_detect_tiepoints(C3MarkerDetector* d, const uint8_t* grey, int32_t width, int32_t height,
                                       int32_t stride, int32_t search_downscale, int32_t refine_method) {
  if (d == nullptr || grey == nullptr || width < 8 || height < 8 || stride < width) return C3MD_ERROR_ARGUMENT;
  d->tieCount = 0;
#if C3MD_WITH_APRILTAG
  // Quads are searched on the image decimated by search_downscale; decoding
  // and AprilTag's edge refinement run at full resolution, so the detector
  // corners are already full-resolution coordinates. C3MD_REFINE_CORNER_SUBPIX
  // keeps those edge-refined corners; C3MD_REFINE_EDGE_LINES fits edge lines
  // (with the distortion model) on top.
  const double t0 = nowMs();
  const int n = d->tags->detect(grey, width, height, stride, static_cast<float>(std::max(1, search_downscale)),
                                d->found, C3MD_MAX_MARKERS);
  const double t1 = nowMs();
  d->timings[0] = t1 - t0;
  if (n < 0) return C3MD_ERROR_INTERNAL;
  c3md::Camera pinholeCamera;
  pinholeCamera.set(d->camera.fx, d->camera.fy, d->camera.cx, d->camera.cy, nullptr);
  int written = 0;
  for (int i = 0; i < n; i++) {
    const float* coarse = d->found[i].corners;
    C3MarkerTiePoint& t = d->ties[written];
    std::memset(&t, 0, sizeof t);
    t.marker_id = static_cast<uint16_t>(d->found[i].id);
    t.dictionary = static_cast<uint8_t>(d->dictionary);
    double pinhole[8];
    bool refined = false;
    if (refine_method == C3MD_REFINE_EDGE_LINES) {
      refined = c3md::refineEdges(grey, width, height, stride, coarse, d->modules, d->camera, d->scratch, pinhole);
    } else if (refine_method == C3MD_REFINE_CORNER_SUBPIX) {
      refined = true;  // AprilTag's edge refinement
    }
    if (refine_method != C3MD_REFINE_EDGE_LINES || !refined) {
      for (int k = 0; k < 4; k++) {
        pinhole[2 * k] = coarse[2 * k];
        pinhole[2 * k + 1] = coarse[2 * k + 1];
        c3md::pixelToPinhole(d->camera, pinhole[2 * k], pinhole[2 * k + 1]);
      }
    }
    double cx, cy;
    c3md::diagonalCentre(pinhole, cx, cy);
    c3md::pinholeToPixel(d->camera, cx, cy);
    t.centre_px[0] = static_cast<float>(cx);
    t.centre_px[1] = static_cast<float>(cy);
    double shift = 0;
    bool partial = false;
    for (int k = 0; k < 4; k++) {
      double x = pinhole[2 * k], y = pinhole[2 * k + 1];
      c3md::pinholeToPixel(d->camera, x, y);
      t.corners_px[2 * k] = static_cast<float>(x);
      t.corners_px[2 * k + 1] = static_cast<float>(y);
      shift += (x - coarse[2 * k]) * (x - coarse[2 * k]) + (y - coarse[2 * k + 1]) * (y - coarse[2 * k + 1]);
      if (x < 2 || y < 2 || x > width - 3 || y > height - 3) partial = true;
    }
    t.refine_shift_px = static_cast<float>(std::sqrt(shift / 4));
    t.sharpness = static_cast<float>(c3md::edgeSharpness(grey, width, height, stride, coarse));
    // Residual: RMS of a square fit of the pinhole corners.
    float pinholeCorners[8];
    for (int k = 0; k < 8; k++) pinholeCorners[k] = static_cast<float>(pinhole[k]);
    double rv[3], tv[3], rms;
    t.residual_px = c3md::solveSquarePose(pinholeCamera, d->sizeOf(t.marker_id) / 2, pinholeCorners, rv, tv, rms)
                        ? static_cast<float>(rms)
                        : NAN;
    t.flags = static_cast<uint8_t>((refined && refine_method != C3MD_REFINE_NONE ? C3MD_TIE_SUBPIXEL_REFINED : 0) |
                                   (partial ? C3MD_TIE_PARTIAL : 0) |
                                   (t.sharpness < d->quality.min_sharpness ? C3MD_TIE_LOW_SHARPNESS : 0) |
                                   (d->camera.distorted ? C3MD_TIE_UNDISTORTED_FIT : 0));
    written++;
  }
  d->tieCount = written;
  d->timings[2] = nowMs() - t1;
  return written;
#else
  (void)search_downscale;
  (void)refine_method;
  return C3MD_ERROR_UNAVAILABLE;
#endif
}

C3MD_API const C3MarkerTiePoint* c3md_tiepoints(const C3MarkerDetector* d) { return d == nullptr ? nullptr : d->ties; }

C3MD_API const double* c3md_timings(const C3MarkerDetector* d) { return d == nullptr ? nullptr : d->timings; }

}  // extern "C"
