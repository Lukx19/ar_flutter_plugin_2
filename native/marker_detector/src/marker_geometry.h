// Internal geometry of the marker detector (no OpenCV).
#ifndef CAPTURE3D_MARKER_GEOMETRY_H
#define CAPTURE3D_MARKER_GEOMETRY_H

#include <cstdint>
#include <vector>

#include "marker_detection_record.h"

namespace c3md {

constexpr double kPi = 3.14159265358979323846;

/// Pinhole intrinsics plus optional (k1, k2, p1, p2, k3) distortion.
struct Camera {
  double fx = 1, fy = 1, cx = 0, cy = 0;
  double distortion[5] = {0, 0, 0, 0, 0};
  bool distorted = false;

  void set(double fx_, double fy_, double cx_, double cy_, const double* distortion5) {
    fx = fx_;
    fy = fy_;
    cx = cx_;
    cy = cy_;
    distorted = false;
    for (int k = 0; k < 5; k++) {
      distortion[k] = distortion5 == nullptr ? 0 : distortion5[k];
      if (distortion[k] != 0) distorted = true;
    }
  }
};

/// Reused buffers of the edge refinement.
struct EdgeScratch {
  std::vector<float> profile;
  std::vector<double> xs, ys, residuals;
};

void distortNormalised(const double* d, double& x, double& y);
void undistortNormalised(const double* d, double& x, double& y);
void pixelToPinhole(const Camera& c, double& x, double& y);
void pinholeToPixel(const Camera& c, double& x, double& y);

/// Edge-line refinement; writes the corners in the undistorted pinhole image.
bool refineEdges(const uint8_t* grey, int width, int height, int stride, const float* coarse, int modules,
                 const Camera& camera, EdgeScratch& scratch, double* pinhole8);

void diagonalCentre(const double* corners8, double& x, double& y);
double edgeSharpness(const uint8_t* grey, int width, int height, int stride, const float* corners8);
void rodrigues(const double* rvec, double* R);
double viewingAngleDeg(const double* rvec, const double* tvec);
uint16_t f16FromFloat(float value);
float f16ToFloat(uint16_t bits);
void encodeRecord(uint16_t markerId, uint8_t dictionary, uint8_t flags, const float* corners8, const double* rvec3,
                  const double* tvec3, float reprojectionErrorPx, float viewingAngleDeg, float sharpness,
                  C3MarkerDetectionRecord& out);

}  // namespace c3md

#endif  // CAPTURE3D_MARKER_GEOMETRY_H
