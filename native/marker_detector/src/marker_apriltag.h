// AprilTag 3 detection backend of the marker detector (C3MD_WITH_APRILTAG).
#ifndef CAPTURE3D_MARKER_APRILTAG_H
#define CAPTURE3D_MARKER_APRILTAG_H

#include <cstdint>

namespace c3md {

/// One decoded marker: id and corners in aruco order (top-left, top-right,
/// bottom-right, bottom-left of the printed face) in image pixels with the
/// centre of the top-left pixel at (0, 0).
struct TagDetection {
  int id;
  float corners[8];
  float decisionMargin;
  int hammingCorrected;
};

/// Bits AprilTag corrects per code (its quick-decode table grows with the
/// square of this: 2 bits for the 250 ARUCO_MIP_36h12 codes is about 8 MB).
constexpr int kTagCorrectionBits = 2;

/// Smallest local grey difference AprilTag thresholds into black and white.
constexpr int kTagMinWhiteBlackDiff = 20;

/// AprilTag detector of one dictionary; not thread-safe.
class TagDetector {
 public:
  /// Null for a dictionary the AprilTag path does not decode (4X4_50,
  /// 6X6_250: AprilTag 3 has no ArUco families) or when out of memory.
  static TagDetector* create(int dictionary);
  ~TagDetector();
  TagDetector(const TagDetector&) = delete;
  TagDetector& operator=(const TagDetector&) = delete;

  /// Detects markers on a grey image (read in place, never copied or
  /// written). Quads are searched on the image decimated by `decimate`
  /// (>= 1); decoding and AprilTag's edge refinement use full resolution.
  /// Writes at most `max` detections sorted by id; returns the count or -1
  /// on an internal failure.
  int detect(const uint8_t* grey, int width, int height, int stride, float decimate, TagDetection* out, int max);

 private:
  TagDetector() = default;
  struct Impl;
  Impl* impl_ = nullptr;
};

/// True when the AprilTag path decodes `dictionary` (C3MD_DICTIONARY_*).
bool tagDictionarySupported(int dictionary);

}  // namespace c3md

#endif  // CAPTURE3D_MARKER_APRILTAG_H
