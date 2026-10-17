// Test-only export of the host scene library (never in the Android or iOS
// builds): both refined IPPE solutions of one square marker and the inputs
// of the pose ambiguity decision, so the synthetic scene check can verify
// the bound independently of the record's flag.

#include <cmath>

#include "marker_geometry.h"
#include "marker_pose.h"

extern "C" __attribute__((visibility("default"))) int c3md_scene_pose_probe(double fx, double fy, double cx,
                                                                            double cy, double side,
                                                                            const float* corners8, double* out6) {
  c3md::Camera camera;
  camera.fx = fx;
  camera.fy = fy;
  camera.cx = cx;
  camera.cy = cy;
  double rv[3], tv[3], rms;
  c3md::PoseAlternative alternative;
  if (!c3md::solveSquarePose(camera, side / 2, corners8, rv, tv, rms, &alternative)) return 0;
  const double angle = c3md::viewingAngleDeg(rv, tv);
  // out: rms, alternative rms (-1 without one), rotation gap deg, viewing angle deg, flip cue px, decision.
  out6[0] = rms;
  out6[1] = alternative.found ? alternative.rms : -1;
  out6[2] = alternative.found ? alternative.rotationGapDeg : 0;
  out6[3] = angle;
  out6[4] = c3md::flipPerspectiveCuePx(0.5 * (fx + fy), side, tv, angle);
  out6[5] = c3md::squarePoseAmbiguous(rms, alternative, out6[4], angle) ? 1 : 0;
  return 1;
}
