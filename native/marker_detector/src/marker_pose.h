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

/// The refined IPPE solution a planar solve did not keep.
struct PoseAlternative {
  bool found = false;           // both IPPE solutions were in front of the camera and refined
  double rms = 0;               // its pixel RMS (never below the kept pose's)
  double rotationGapDeg = 0;    // angle between its rotation and the kept one
  double rvec[3] = {0, 0, 0};
  double tvec[3] = {0, 0, 0};
};

/// Camera-from-object pose of `n` >= 4 correspondences (image points in raw
/// pixels). Planar points (any plane) use IPPE and keep the better of its
/// two solutions after refinement; non-planar points need n >= 6 (DLT).
/// Returns false when no pose was found; `rms` is the pixel RMS. With
/// `alternative` set, a planar solve also reports the other refined minimum.
bool solvePose(const Camera& camera, int n, const double* objectXyz, const double* imageXy, double* rvec,
               double* tvec, double& rms, PoseAlternative* alternative = nullptr);

/// Pose of a square marker of side 2 * halfSide from its four corners in
/// aruco order (top-left, top-right, bottom-right, bottom-left of the face;
/// marker frame x right, y up, z out of the face).
bool solveSquarePose(const Camera& camera, double halfSide, const float* corners8, double* rvec, double* tvec,
                     double& rms, PoseAlternative* alternative = nullptr);

/// Planar pose ambiguity of one square marker. IPPE returns two poses that
/// mirror the marker normal about the line of sight; for a small marker
/// seen nearly head-on both reproject almost equally well and the kept one
/// can be the mirror (rotation off by about twice the viewing angle). A pose
/// is ambiguous when the flip it risks is at least kPoseAmbiguityMinFlipDeg
/// and either the error ratio (kept RMS over the alternative's) is at least
/// kPoseAmbiguityErrorRatio or the flip perspective cue is below
/// kPoseAmbiguityFlipCuePx. The ratio counts only for a distinct alternative
/// (rotation gap of at least kPoseAmbiguityMinFlipDeg); when both seeds
/// refine to one minimum the cue alone decides.
///
/// Error ratio 0.5: with 4 corners (8 residuals, 6 parameters) the kept RMS
/// is about half the corner noise sigma; a ratio of 0.5 or more means the
/// mirror's systematic misfit is below about one noise sigma, so the data
/// cannot reject it.
constexpr double kPoseAmbiguityErrorRatio = 0.5;

/// Flip perspective cue 0.25 px: the corner displacement that separates a
/// pose from its mirror is about f S^2 sin(angle) / |t|^2 pixels (S the
/// printed side, f the focal length in pixels): the mirror flips the sign
/// of the perspective term of a side of S f / |t| pixels. Below 0.25 px it
/// is within about 2.5 sigma of the detector's corner noise on the
/// synthetic marker scene (0.1 px RMS); every flipped detection of that
/// scene has a cue of at most 0.21 px.
constexpr double kPoseAmbiguityFlipCuePx = 0.25;

/// A flip that changes the rotation by less than 2 deg (the scene's
/// median rotation tolerance) is within pose noise and not flagged.
constexpr double kPoseAmbiguityMinFlipDeg = 2.0;

/// The flip perspective cue in pixels (see kPoseAmbiguityFlipCuePx).
double flipPerspectiveCuePx(double focalPx, double sideMetres, const double* tvec, double viewingAngleDeg);

/// Whether a square marker pose with RMS `rms`, its IPPE `alternative`,
/// flip cue `cuePx` and viewing angle is ambiguous (see above). The flip
/// size is the larger of the rotation gap to a distinct alternative and
/// twice the viewing angle (a merged or missing alternative still risks the
/// mirror; then only the cue decides).
bool squarePoseAmbiguous(double rms, const PoseAlternative& alternative, double cuePx, double viewingAngleDeg);

}  // namespace c3md

#endif  // CAPTURE3D_MARKER_POSE_H
