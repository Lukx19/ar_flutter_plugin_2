// AprilTag 3 backend: quad search, decoding against the dictionary's family
// (ARUCO_MIP_36h12 as the generated custom family, APRILTAG_36h11 as
// AprilTag's own tag36h11) and AprilTag's edge refinement.
//
// Corner order: AprilTag reports p[0..3] at tag coordinates (-1, 1), (1, 1),
// (1, -1), (-1, -1), where tag y grows with the printed bit rows (down the
// face). That is bottom-left, bottom-right, top-right, top-left of the
// printed face, so aruco corner k is AprilTag corner 3 - k.
// Pixel convention: AprilTag places pixel centres at +0.5 (it samples
// pixel (i, j) at (i + 0.5, j + 0.5)); the C ABI places them at integers,
// so 0.5 is subtracted from every corner.

#include "marker_apriltag.h"

#include <algorithm>
#include <new>

extern "C" {
#include "apriltag.h"
#include "aruco_mip_36h12_family.h"
#include "tag36h11.h"
}

#include "marker_detector.h"

namespace c3md {

struct TagDetector::Impl {
  apriltag_detector_t* detector = nullptr;
  apriltag_family_t* family = nullptr;
  void (*destroyFamily)(apriltag_family_t*) = nullptr;
};

bool tagDictionarySupported(int dictionary) {
  return dictionary == C3MD_DICTIONARY_ARUCO_MIP_36H12 || dictionary == C3MD_DICTIONARY_APRILTAG_36H11;
}

TagDetector* TagDetector::create(int dictionary) {
  if (!tagDictionarySupported(dictionary)) return nullptr;
  auto* self = new (std::nothrow) TagDetector();
  if (self == nullptr) return nullptr;
  self->impl_ = new (std::nothrow) Impl();
  if (self->impl_ == nullptr) {
    delete self;
    return nullptr;
  }
  Impl& m = *self->impl_;
  if (dictionary == C3MD_DICTIONARY_ARUCO_MIP_36H12) {
    m.family = aruco_mip_36h12_create();
    m.destroyFamily = aruco_mip_36h12_destroy;
  } else {
    m.family = tag36h11_create();
    m.destroyFamily = tag36h11_destroy;
  }
  m.detector = apriltag_detector_create();
  if (m.family == nullptr || m.detector == nullptr) {
    delete self;
    return nullptr;
  }
  apriltag_detector_add_family_bits(m.detector, m.family, kTagCorrectionBits);
  if (m.family->impl == nullptr) {  // decode table allocation failed
    delete self;
    return nullptr;
  }
  m.detector->nthreads = 1;
  m.detector->quad_sigma = 0.0f;
  m.detector->refine_edges = true;
  m.detector->decode_sharpening = 0.25;
  m.detector->debug = false;
  // AprilTag's default (5 grey levels) lets camera noise in flat areas form
  // thousands of clusters (72 ms per noisy 1920x1440 frame on the host
  // against 1.7 ms at 20); printed markers keep 40 levels in dim scenes.
  m.detector->qtp.min_white_black_diff = kTagMinWhiteBlackDiff;
  return self;
}

TagDetector::~TagDetector() {
  if (impl_ == nullptr) return;
  if (impl_->detector != nullptr) apriltag_detector_destroy(impl_->detector);
  if (impl_->family != nullptr && impl_->destroyFamily != nullptr) impl_->destroyFamily(impl_->family);
  delete impl_;
}

int TagDetector::detect(const uint8_t* grey, int width, int height, int stride, float decimate, TagDetection* out,
                        int max) {
  apriltag_detector_t* td = impl_->detector;
  td->quad_decimate = std::max(1.0f, decimate);
  // AprilTag reads the image only; the cast drops const for its C struct.
  image_u8_t image = {width, height, stride, const_cast<uint8_t*>(grey)};
  zarray_t* detections = apriltag_detector_detect(td, &image);
  if (detections == nullptr) return -1;
  const int found = zarray_size(detections);
  int written = 0;
  for (int i = 0; i < found && written < max; i++) {
    apriltag_detection_t* det = nullptr;
    zarray_get(detections, i, &det);
    if (det == nullptr) continue;
    TagDetection& d = out[written++];
    d.id = det->id;
    d.decisionMargin = det->decision_margin;
    d.hammingCorrected = det->hamming;
    for (int k = 0; k < 4; k++) {
      d.corners[2 * k] = static_cast<float>(det->p[3 - k][0] - 0.5);
      d.corners[2 * k + 1] = static_cast<float>(det->p[3 - k][1] - 0.5);
    }
  }
  apriltag_detections_destroy(detections);
  std::sort(out, out + written, [](const TagDetection& a, const TagDetection& b) { return a.id < b.id; });
  return written;
}

}  // namespace c3md
