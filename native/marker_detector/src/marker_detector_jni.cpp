// JNI entry points of the marker detector for
// com.uhg0.ar_flutter_plugin_2.markerdetection.MarkerDetectorNative.
//
// Buffers cross as direct ByteBuffers (grey images, output records) or as an
// android.graphics.Bitmap (decoded stills), so no Java array is copied per
// call. A detector handle is a jlong owned by the Kotlin wrapper.

#include <android/bitmap.h>
#include <jni.h>

#include <cstring>

#include "marker_detector.h"
#include "marker_detector_internal.h"

#define C3MD_JNI(name) Java_com_uhg0_ar_1flutter_1plugin_12_markerdetection_MarkerDetectorNative_##name

namespace {

C3MarkerDetector* handle(jlong h) { return reinterpret_cast<C3MarkerDetector*>(h); }

uint8_t* directAddress(JNIEnv* env, jobject buffer, jlong minimumBytes) {
  if (buffer == nullptr) return nullptr;
  auto* address = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  if (address == nullptr || env->GetDirectBufferCapacity(buffer) < minimumBytes) return nullptr;
  return address;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL C3MD_JNI(nativeAvailable)(JNIEnv*, jclass) { return c3md_detector_available(); }

JNIEXPORT jlong JNICALL C3MD_JNI(nativeCreate)(JNIEnv*, jclass, jint dictionary) {
  return reinterpret_cast<jlong>(c3md_create(dictionary));
}

JNIEXPORT void JNICALL C3MD_JNI(nativeDestroy)(JNIEnv*, jclass, jlong h) { c3md_destroy(handle(h)); }

JNIEXPORT jint JNICALL C3MD_JNI(nativeSetCamera)(JNIEnv* env, jclass, jlong h, jdouble fx, jdouble fy, jdouble cx,
                                                 jdouble cy, jdoubleArray distortion) {
  double d[5] = {0, 0, 0, 0, 0};
  if (distortion != nullptr) {
    if (env->GetArrayLength(distortion) < 5) return C3MD_ERROR_ARGUMENT;
    env->GetDoubleArrayRegion(distortion, 0, 5, d);
  }
  return c3md_set_camera(handle(h), fx, fy, cx, cy, distortion == nullptr ? nullptr : d);
}

JNIEXPORT jint JNICALL C3MD_JNI(nativeSetMarkerSize)(JNIEnv*, jclass, jlong h, jdouble size) {
  return c3md_set_marker_size(handle(h), size);
}

JNIEXPORT jint JNICALL C3MD_JNI(nativeSetMarkerSizeForId)(JNIEnv*, jclass, jlong h, jint id, jdouble size) {
  return c3md_set_marker_size_for_id(handle(h), id, size);
}

JNIEXPORT jint JNICALL C3MD_JNI(nativeSetQuality)(JNIEnv*, jclass, jlong h, jfloat minSide, jfloat minSharpness,
                                                  jfloat maxAngle) {
  const C3MarkerQualityParams p{minSide, minSharpness, maxAngle};
  return c3md_set_quality(handle(h), &p);
}

/// Detects, estimates poses and copies the 64 B records to `out` at
/// `outOffset` (at most `maxRecords`). Returns the record count or an error.
JNIEXPORT jint JNICALL C3MD_JNI(nativeDetect)(JNIEnv* env, jclass, jlong h, jobject grey, jint width, jint height,
                                              jint stride, jobject out, jint outOffset, jint maxRecords) {
  auto* d = handle(h);
  const uint8_t* pixels = directAddress(env, grey, static_cast<jlong>(stride) * (height - 1) + width);
  uint8_t* target = directAddress(env, out, outOffset + static_cast<jlong>(maxRecords) * C3_MARKER_DETECTION_RECORD_BYTES);
  if (d == nullptr || pixels == nullptr || target == nullptr || maxRecords < 0) return C3MD_ERROR_ARGUMENT;
  int count = c3md_detect(d, pixels, width, height, stride);
  if (count < 0) return count;
  if (count > 0) {
    const int poses = c3md_estimate_pose(d);
    if (poses < 0) return poses;
  }
  if (count > maxRecords) count = maxRecords;
  std::memcpy(target + outOffset, c3md_records(d), static_cast<size_t>(count) * C3_MARKER_DETECTION_RECORD_BYTES);
  return count;
}

/// Converts an RGBA_8888 bitmap to grey (BT.601 luma) into the detector's
/// reused buffer, detects tie points and copies the 64 B tie records to
/// `out` at `outOffset`. Returns the count or an error.
JNIEXPORT jint JNICALL C3MD_JNI(nativeDetectTiePointsBitmap)(JNIEnv* env, jclass, jlong h, jobject bitmap,
                                                             jint searchDownscale, jint refineMethod, jobject out,
                                                             jint outOffset, jint maxRecords) {
  auto* d = handle(h);
  uint8_t* target = directAddress(env, out, outOffset + static_cast<jlong>(maxRecords) * C3MD_TIE_POINT_BYTES);
  if (d == nullptr || bitmap == nullptr || target == nullptr || maxRecords < 0) return C3MD_ERROR_ARGUMENT;
  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    return C3MD_ERROR_ARGUMENT;
  }
  void* pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return C3MD_ERROR_INTERNAL;
  auto& grey = c3md::greyBuffer(d);
  const size_t needed = static_cast<size_t>(info.width) * info.height;
  if (grey.size() < needed) grey.resize(needed);  // grows once to the largest still
  for (uint32_t y = 0; y < info.height; y++) {
    const uint8_t* row = static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
    uint8_t* dst = grey.data() + static_cast<size_t>(y) * info.width;
    for (uint32_t x = 0; x < info.width; x++) {
      const uint8_t* p = row + 4 * x;
      dst[x] = static_cast<uint8_t>((77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8);
    }
  }
  AndroidBitmap_unlockPixels(env, bitmap);
  int count = c3md_detect_tiepoints(d, grey.data(), static_cast<int32_t>(info.width),
                                    static_cast<int32_t>(info.height), static_cast<int32_t>(info.width),
                                    searchDownscale, refineMethod);
  if (count < 0) return count;
  if (count > maxRecords) count = maxRecords;
  std::memcpy(target + outOffset, c3md_tiepoints(d), static_cast<size_t>(count) * C3MD_TIE_POINT_BYTES);
  return count;
}

/// Detect, pose and refine times (ms) of the last calls into `out` (3).
JNIEXPORT void JNICALL C3MD_JNI(nativeTimings)(JNIEnv* env, jclass, jlong h, jdoubleArray out) {
  const double* t = c3md_timings(handle(h));
  if (t != nullptr && out != nullptr && env->GetArrayLength(out) >= 3) env->SetDoubleArrayRegion(out, 0, 3, t);
}

}  // extern "C"
