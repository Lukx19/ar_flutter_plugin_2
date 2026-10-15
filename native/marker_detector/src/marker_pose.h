// Perspective-n-point: IPPE (infinitesimal plane-based pose,
// Collins and Bartoli 2014) for planar points, a normalised DLT for
// non-planar points, both refined by Levenberg-Marquardt on the pixel
// reprojection error with the camera's lens distortion. Poses are
// camera-from-object in the OpenCV camera convention, rotations as rvecs.
#ifndef CAPTURE3D_MARKER_POSE_H
#define CAPTURE3D_MARKER_POSE_H

#include "marker_geometry.h"

namespace c3md {

/// Upper bound of correspondences per solve (tapes have at most a few dozen
/// markers of four corners).
constexpr int kMaxPosePoints = 512;

/// Pixel projection of object point `X` (with distortion).
void projectPoint(const Camera& camera, const double* R, const double* t, const double* X, double& u, double& v);

/// Rotation matrix (row-major) to rvec.
void rvecFromRotation(const double* R, double* rvec);

/// RMS pixel reprojection error of `n` correspondences.
double reprojectionRms(const Camera& camera, int n, const double* objectXyz, const double* imageXy, const double* rvec,
                       const double* tvec);

/// Camera-from-object pose of `n` >= 4 correspondences (image points in raw
/// pixels). Planar points (any plane) use IPPE and keep the better of its
/// two solutions after refinement; non-planar points need n >= 6 (DLT).
/// Returns false when no pose was found; `rms` is the pixel RMS.
bool solvePose(const Camera& camera, int n, const double* objectXyz, const double* imageXy, double* rvec,
               double* tvec, double& rms);

/// Pose of a square marker of side 2 * halfSide from its four corners in
/// aruco order (top-left, top-right, bottom-right, bottom-left of the face;
/// marker frame x right, y up, z out of the face).
bool solveSquarePose(const Camera& camera, double halfSide, const float* corners8, double* rvec, double* tvec,
                     double& rms);

}  // namespace c3md

#endif  // CAPTURE3D_MARKER_POSE_H
