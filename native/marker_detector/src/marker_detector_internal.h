// Internal access to detector state for the JNI shim.
#ifndef CAPTURE3D_MARKER_DETECTOR_INTERNAL_H
#define CAPTURE3D_MARKER_DETECTOR_INTERNAL_H

#include <cstdint>
#include <vector>

#include "marker_detector.h"

static_assert(sizeof(C3MarkerTiePoint) == C3MD_TIE_POINT_BYTES, "tie point size");

namespace c3md {

/// Reused grey buffer of a detector (bitmap conversion).
std::vector<uint8_t>& greyBuffer(C3MarkerDetector* detector);

}  // namespace c3md

#endif  // CAPTURE3D_MARKER_DETECTOR_INTERNAL_H
