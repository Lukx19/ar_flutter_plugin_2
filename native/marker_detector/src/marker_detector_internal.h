// Internal access to detector state for the JNI shim.
#ifndef CAPTURE3D_MARKER_DETECTOR_INTERNAL_H
#define CAPTURE3D_MARKER_DETECTOR_INTERNAL_H

#include <cstddef>
#include <cstdint>
#include <vector>

#include "marker_detector.h"

static_assert(sizeof(C3MarkerTiePoint) == C3MD_TIE_POINT_BYTES, "tie point size");

namespace c3md {

/// Reused grey buffer of a detector (bitmap conversion).
std::vector<uint8_t>& greyBuffer(C3MarkerDetector* detector);

/// Grows the grey buffer of `detector` to at least `bytes` (it never
/// shrinks). Returns C3MD_OK, C3MD_ERROR_ARGUMENT for a null detector, or
/// C3MD_ERROR_INTERNAL when the allocation fails; no exception escapes, so
/// the JNI shim calls it before locking bitmap pixels.
int ensureGreyBuffer(C3MarkerDetector* detector, size_t bytes) noexcept;

/// Bytes an output window of `maxRecords` records of `recordBytes` at byte
/// `offset` needs, or -1 when `offset` or `maxRecords` is negative.
inline int64_t outputWindowBytes(int64_t offset, int64_t maxRecords, int64_t recordBytes) {
  if (offset < 0 || maxRecords < 0) return -1;
  return offset + maxRecords * recordBytes;
}

}  // namespace c3md

#endif  // CAPTURE3D_MARKER_DETECTOR_INTERNAL_H
