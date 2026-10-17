// Host test of the marker detector C ABI without a detection backend: record encoding
// against the shared golden, half floats, diagonal centre, viewing angle,
// distortion round trip, edge sharpness, and edge-line corner refinement on
// rendered marker images with known corners (pinhole and distorted).
//
//   clang++ -std=c++17 -O2 -I../include -I../src \
//     ../src/marker_geometry.cpp ../src/marker_detector.cpp marker_detector_host_test.cpp
//   ./a.out ../../../test_assets/visibility_grid/ingest_markers.bin
//
// Exit code 0 when every check passes; failures print one line each.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "marker_detector.h"
#include "marker_detector_internal.h"
#include "marker_pose.h"

namespace {

int failures = 0;

void check(bool ok, const char* what, double value = 0) {
  if (!ok) {
    std::printf("FAIL %s (%.6g)\n", what, value);
    failures++;
  }
}

constexpr double kPi = 3.14159265358979323846;

// ---- synthetic rendering ---------------------------------------------------

struct Rng {
  uint64_t s;
  double uniform() {
    s = s * 6364136223846793005ull + 1442695040888963407ull;
    return ((s >> 11) & ((1ull << 53) - 1)) / static_cast<double>(1ull << 53);
  }
  double gauss() {
    const double u = std::max(uniform(), 1e-12), v = uniform();
    return std::sqrt(-2 * std::log(u)) * std::cos(2 * kPi * v);
  }
};

struct Scene {
  int width = 1600, height = 1200;
  double fx = 1500, fy = 1500, cx = 799.5, cy = 599.5;
  double distortion[5] = {0, 0, 0, 0, 0};
  bool distorted = false;
  double R[9];  // camera-from-marker rotation (OpenCV camera)
  double t[3];
  double side = 0.1;
  uint8_t bits[36];
};

void rodrigues(const double* r, double* R) {
  const double a = std::sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
  if (a < 1e-12) {
    const double id[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    std::memcpy(R, id, sizeof id);
    return;
  }
  const double x = r[0] / a, y = r[1] / a, z = r[2] / a;
  const double c = std::cos(a), s = std::sin(a), k = 1 - c;
  const double m[9] = {k * x * x + c,     k * x * y - s * z, k * x * z + s * y, k * x * y + s * z, k * y * y + c,
                       k * y * z - s * x, k * x * z - s * y, k * y * z + s * x, k * z * z + c};
  std::memcpy(R, m, sizeof m);
}

// Pinhole (undistorted) projection of a marker-plane point.
void project(const Scene& sc, double X, double Y, double& u, double& v) {
  const double x = sc.R[0] * X + sc.R[1] * Y + sc.t[0];
  const double y = sc.R[3] * X + sc.R[4] * Y + sc.t[1];
  const double z = sc.R[6] * X + sc.R[7] * Y + sc.t[2];
  u = sc.fx * x / z + sc.cx;
  v = sc.fy * y / z + sc.cy;
}

// Raw (distorted) pixel of a marker-plane point.
void projectRaw(const Scene& sc, double X, double Y, double& u, double& v) {
  project(sc, X, Y, u, v);
  if (!sc.distorted) return;
  double nx = (u - sc.cx) / sc.fx, ny = (v - sc.cy) / sc.fy;
  c3md_distort_normalised(sc.distortion, &nx, &ny);
  u = nx * sc.fx + sc.cx;
  v = ny * sc.fy + sc.cy;
}

// Marker-plane point seen through pinhole pixel (u, v): solves the plane
// homography H = K [r1 r2 t] backwards.
bool unproject(const Scene& sc, double u, double v, double& X, double& Y) {
  const double rx = (u - sc.cx) / sc.fx, ry = (v - sc.cy) / sc.fy;
  // [r1 r2 t] [X Y 1]^T = s [rx ry 1]^T
  const double a[9] = {sc.R[0], sc.R[1], sc.t[0], sc.R[3], sc.R[4], sc.t[1], sc.R[6], sc.R[7], sc.t[2]};
  // Solve a * [X Y 1]' ~ [rx ry 1]: cross product form, 2 equations.
  // (a0 X + a1 Y + a2) - rx (a6 X + a7 Y + a8) = 0
  // (a3 X + a4 Y + a5) - ry (a6 X + a7 Y + a8) = 0
  const double m00 = a[0] - rx * a[6], m01 = a[1] - rx * a[7], b0 = rx * a[8] - a[2];
  const double m10 = a[3] - ry * a[6], m11 = a[4] - ry * a[7], b1 = ry * a[8] - a[5];
  const double det = m00 * m11 - m01 * m10;
  if (std::abs(det) < 1e-15) return false;
  X = (b0 * m11 - m01 * b1) / det;
  Y = (m00 * b1 - b0 * m10) / det;
  return true;
}

uint8_t ink(const Scene& sc, double X, double Y) {
  const double h = sc.side / 2;
  if (std::abs(X) > h || std::abs(Y) > h) return 215;
  const double module = sc.side / 8;
  const int col = std::min(7, static_cast<int>((X + h) / module));
  const int row = std::min(7, static_cast<int>((h - Y) / module));
  if (row == 0 || col == 0 || row == 7 || col == 7) return 35;
  return sc.bits[(row - 1) * 6 + (col - 1)] ? 215 : 35;
}

void gaussianBlur(std::vector<uint8_t>& img, int w, int h, double sigma) {
  if (sigma <= 0) return;
  const int r = static_cast<int>(std::ceil(3 * sigma));
  std::vector<double> k(2 * r + 1);
  double sum = 0;
  for (int i = -r; i <= r; i++) sum += k[i + r] = std::exp(-0.5 * i * i / (sigma * sigma));
  for (auto& v : k) v /= sum;
  std::vector<double> tmp(static_cast<size_t>(w) * h);
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      double s = 0;
      for (int i = -r; i <= r; i++) s += k[i + r] * img[y * w + std::min(w - 1, std::max(0, x + i))];
      tmp[y * w + x] = s;
    }
  }
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      double s = 0;
      for (int i = -r; i <= r; i++) s += k[i + r] * tmp[std::min(h - 1, std::max(0, y + i)) * w + x];
      img[y * w + x] = static_cast<uint8_t>(std::lround(std::min(255.0, std::max(0.0, s))));
    }
  }
}

// Renders the marker with 4 x 4 supersampling inside its footprint.
std::vector<uint8_t> render(const Scene& sc, double blurSigma, double noiseSigma, uint64_t seed) {
  std::vector<uint8_t> img(static_cast<size_t>(sc.width) * sc.height, 215);
  double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
  const double h = sc.side / 2 * 1.3;
  for (const double sx : {-h, h}) {
    for (const double sy : {-h, h}) {
      double u, v;
      projectRaw(sc, sx, sy, u, v);
      x0 = std::min(x0, u);
      y0 = std::min(y0, v);
      x1 = std::max(x1, u);
      y1 = std::max(y1, v);
    }
  }
  const int ss = 4;
  for (int y = std::max(0, static_cast<int>(y0) - 2); y < std::min(sc.height, static_cast<int>(y1) + 3); y++) {
    for (int x = std::max(0, static_cast<int>(x0) - 2); x < std::min(sc.width, static_cast<int>(x1) + 3); x++) {
      int acc = 0;
      for (int j = 0; j < ss; j++) {
        for (int i = 0; i < ss; i++) {
          double u = x - 0.5 + (i + 0.5) / ss, v = y - 0.5 + (j + 0.5) / ss;
          if (sc.distorted) {
            double nx = (u - sc.cx) / sc.fx, ny = (v - sc.cy) / sc.fy;
            c3md_undistort_normalised(sc.distortion, &nx, &ny);
            u = nx * sc.fx + sc.cx;
            v = ny * sc.fy + sc.cy;
          }
          double X, Y;
          acc += unproject(sc, u, v, X, Y) ? ink(sc, X, Y) : 215;
        }
      }
      img[static_cast<size_t>(y) * sc.width + x] = static_cast<uint8_t>((acc + ss * ss / 2) / (ss * ss));
    }
  }
  gaussianBlur(img, sc.width, sc.height, blurSigma);
  Rng rng{seed * 2654435761ull + 7};
  if (noiseSigma > 0) {
    for (auto& p : img) p = static_cast<uint8_t>(std::lround(std::min(255.0, std::max(0.0, p + rng.gauss() * noiseSigma))));
  }
  return img;
}

Scene makeScene(double distance, double tiltDeg, double rollDeg, uint64_t seed, bool distorted) {
  Scene sc;
  Rng rng{seed};
  for (auto& b : sc.bits) b = rng.uniform() < 0.5 ? 1 : 0;
  // Marker facing the camera (rotation pi about x), tilted about y, rolled about z.
  double Rf[9], Rt[9], Rr[9];
  const double rf[3] = {kPi, 0, 0};
  const double rt[3] = {0, tiltDeg * kPi / 180, 0};
  const double rr[3] = {0, 0, rollDeg * kPi / 180};
  rodrigues(rf, Rf);
  rodrigues(rt, Rt);
  rodrigues(rr, Rr);
  double tmp[9];
  for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) {
      tmp[3 * i + j] = 0;
      for (int k = 0; k < 3; k++) tmp[3 * i + j] += Rt[3 * i + k] * Rf[3 * k + j];
    }
  }
  for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) {
      sc.R[3 * i + j] = 0;
      for (int k = 0; k < 3; k++) sc.R[3 * i + j] += Rr[3 * i + k] * tmp[3 * k + j];
    }
  }
  sc.t[0] = (rng.uniform() - 0.5) * 0.3 * distance;
  sc.t[1] = (rng.uniform() - 0.5) * 0.3 * distance;
  sc.t[2] = distance;
  if (distorted) {
    const double d[5] = {0.08, -0.05, 0.0005, -0.0003, 0.0};
    std::memcpy(sc.distortion, d, sizeof d);
    sc.distorted = true;
  }
  return sc;
}

void truthCorners(const Scene& sc, bool raw, float* out8) {
  const double h = sc.side / 2;
  const double xs[4] = {-h, h, h, -h}, ys[4] = {h, h, -h, -h};
  for (int c = 0; c < 4; c++) {
    double u, v;
    if (raw) {
      projectRaw(sc, xs[c], ys[c], u, v);
    } else {
      project(sc, xs[c], ys[c], u, v);
    }
    out8[2 * c] = static_cast<float>(u);
    out8[2 * c + 1] = static_cast<float>(v);
  }
}

double cornerError(const float* a, const float* b) {
  double worst = 0;
  for (int c = 0; c < 4; c++) worst = std::max(worst, static_cast<double>(std::hypot(a[2 * c] - b[2 * c], a[2 * c + 1] - b[2 * c + 1])));
  return worst;
}

// ---- tests -------------------------------------------------------------------

void testGolden(const char* path) {
  if (path == nullptr) {
    std::printf("golden: skipped (no path)\n");
    return;
  }
  FILE* f = nullptr;
#if defined(_MSC_VER) || defined(_WIN32)
  fopen_s(&f, path, "rb");
#else
  f = std::fopen(path, "rb");
#endif
  check(f != nullptr, "golden file opens");
  if (f == nullptr) return;
  uint8_t bytes[1024];
  const size_t length = std::fread(bytes, 1, sizeof bytes, f);
  std::fclose(f);
  check(length == 160 + 8 + 2 * 64, "golden length", static_cast<double>(length));
  for (int i = 0; i < 2; i++) {
    const float corners[8] = {100.0f + i, 100, 140.0f + i, 100, 140.0f + i, 140, 100.0f + i, 140};
    const double rvec[3] = {0.1, -0.2, 0.3};
    const double tvec[3] = {0.05 * i, 0, 1.5};
    C3MarkerDetectionRecord record;
    c3md_encode_record(static_cast<uint16_t>(12 + i), C3MD_DICTIONARY_ARUCO_MIP_36H12,
                       i == 0 ? 0 : C3_MARKER_FLAG_REJECTED_BLUR, corners, rvec, tvec, 0.75f, 30.0f, 200.0f / 255.0f,
                       &record);
    check(std::memcmp(&record, bytes + 160 + 8 + 64 * i, 64) == 0, "record matches the golden byte for byte", i);
  }
}

void testHalfFloat() {
  check(c3md_f16_from_float(0.75f) == 0x3a00, "f16 0.75");
  check(c3md_f16_from_float(1.0f) == 0x3c00, "f16 1");
  check(c3md_f16_from_float(-2.0f) == 0xc000, "f16 -2");
  check(c3md_f16_from_float(65504.0f) == 0x7bff, "f16 max");
  check(c3md_f16_from_float(1e6f) == 0x7c00, "f16 overflow to infinity");
  check(c3md_f16_from_float(0.1f) == 0x2e66, "f16 0.1", c3md_f16_from_float(0.1f));
  check(c3md_f16_from_float(5.96046448e-8f) == 0x0001, "f16 smallest subnormal");
  check((c3md_f16_from_float(NAN) & 0x7c00) == 0x7c00 && (c3md_f16_from_float(NAN) & 0x3ff) != 0, "f16 NaN");
  for (uint32_t h = 0; h < 0x7c00; h += 7) {
    check(c3md_f16_from_float(c3md_f16_to_float(static_cast<uint16_t>(h))) == h, "f16 round trip", h);
  }
}

void testCentreAndAngle() {
  // Unit square in perspective: the diagonal centre equals the projected centre.
  Scene sc = makeScene(0.8, 50, 20, 3, false);
  float c[8], centre[2];
  truthCorners(sc, false, c);
  c3md_diagonal_centre(c, centre);
  double u, v;
  project(sc, 0, 0, u, v);
  check(std::hypot(centre[0] - u, centre[1] - v) < 1e-3, "diagonal centre is the projected centre",
        std::hypot(centre[0] - u, centre[1] - v));
  const double facing[3] = {kPi, 0, 0}, t[3] = {0, 0, 1.2};
  check(std::abs(c3md_viewing_angle_deg(facing, t)) < 1e-3, "facing marker has viewing angle 0");
  // A half turn about (-cos 30, 0, sin 30) maps the face normal +z to
  // (-sin 60, 0, -cos 60): 60 degrees away from the direction to the camera.
  const double rv[3] = {-kPi * std::cos(kPi / 6), 0, kPi * std::sin(kPi / 6)};
  check(std::abs(c3md_viewing_angle_deg(rv, t) - 60) < 1e-3, "tilted marker has viewing angle 60",
        c3md_viewing_angle_deg(rv, t));
}

void testDistortionRoundTrip() {
  const double d[5] = {0.08, -0.05, 0.0005, -0.0003, 0.01};
  double worst = 0;
  for (double x = -0.6; x <= 0.6; x += 0.1) {
    for (double y = -0.45; y <= 0.45; y += 0.09) {
      double a = x, b = y;
      c3md_distort_normalised(d, &a, &b);
      c3md_undistort_normalised(d, &a, &b);
      worst = std::max(worst, std::hypot(a - x, b - y));
    }
  }
  check(worst < 1e-9, "undistort inverts distort", worst);
}

void testRefinement() {
  struct Case {
    double distance, tilt, roll, blur;
    bool distorted;
  };
  const Case cases[] = {{0.7, 0, 10, 0.8, false},  {0.9, 35, -20, 1.0, false}, {1.2, 55, 30, 0.7, false},
                        {0.7, 20, 5, 0.8, true},   {1.0, 45, -35, 1.0, true}};
  int index = 0;
  double worstPinhole = 0, worstDistorted = 0, worstCentre = 0, worstUncorrected = 0;
  for (const Case& cs : cases) {
    Scene sc = makeScene(cs.distance, cs.tilt, cs.roll, 100 + index, cs.distorted);
    const auto img = render(sc, cs.blur, 2.0, 200 + index);
    float truth[8];
    truthCorners(sc, true, truth);
    Rng rng{static_cast<uint64_t>(300 + index)};
    float coarse[8];
    for (int k = 0; k < 8; k++) coarse[k] = truth[k] + static_cast<float>((rng.uniform() - 0.5) * 1.6);
    const double camera[4] = {sc.fx, sc.fy, sc.cx, sc.cy};
    float refined[8];
    const int status =
        c3md_refine_edges(img.data(), sc.width, sc.height, sc.width, coarse, 8, camera, sc.distortion, refined);
    check(status == C3MD_OK, "edge refinement succeeds", index);
    const double error = cornerError(refined, truth);
    if (cs.distorted) {
      worstDistorted = std::max(worstDistorted, error);
      float plain[8];
      c3md_refine_edges(img.data(), sc.width, sc.height, sc.width, coarse, 8, camera, nullptr, plain);
      worstUncorrected = std::max(worstUncorrected, cornerError(plain, truth));
    } else {
      worstPinhole = std::max(worstPinhole, error);
      // Centre of the refined corners against the projected marker centre.
      float centre[2];
      c3md_diagonal_centre(refined, centre);
      double u, v;
      project(sc, 0, 0, u, v);
      worstCentre = std::max(worstCentre, std::hypot(centre[0] - u, centre[1] - v));
    }
    check(cornerError(coarse, truth) > 0.2, "coarse corners start off", cornerError(coarse, truth));
    index++;
  }
  std::printf("refinement: worst corner error pinhole %.3f px, distorted %.3f px (without the model %.3f px), "
              "centre %.3f px\n",
              worstPinhole, worstDistorted, worstUncorrected, worstCentre);
  check(worstPinhole < 0.15, "edge refinement corner error below 0.15 px", worstPinhole);
  check(worstDistorted < 0.2, "edge refinement with distortion below 0.2 px", worstDistorted);
  check(worstCentre < 0.1, "refined centre error below 0.1 px", worstCentre);
}

// Full-resolution still (4128 x 3096, f = 2980 px, a 26 mm-equivalent lens)
// with the measured phone distortion: 10 cm markers at 1-3 m, tilted.
void testTwelveMegapixel() {
  double worstCentre = 0, worstCorner = 0;
  int index = 0;
  for (const double distance : {1.0, 2.0, 3.0}) {
    Scene sc = makeScene(distance, 35, 15, 500 + index, true);
    sc.width = 4128;
    sc.height = 3096;
    sc.fx = sc.fy = 2980;
    sc.cx = 2063.5;
    sc.cy = 1547.5;
    const auto img = render(sc, 0.8, 3.0, 600 + index);  // 12.8 MB
    float truth[8];
    truthCorners(sc, true, truth);
    Rng rng{static_cast<uint64_t>(700 + index)};
    float coarse[8];
    for (int k = 0; k < 8; k++) coarse[k] = truth[k] + static_cast<float>((rng.uniform() - 0.5) * 1.6);
    const double camera[4] = {sc.fx, sc.fy, sc.cx, sc.cy};
    float refined[8];
    check(c3md_refine_edges(img.data(), sc.width, sc.height, sc.width, coarse, 8, camera, sc.distortion, refined) ==
              C3MD_OK,
          "12 MP refinement succeeds", distance);
    worstCorner = std::max(worstCorner, cornerError(refined, truth));
    // Centre: diagonals intersected in the pinhole image, mapped back.
    float pinhole[8];
    for (int c = 0; c < 4; c++) {
      double nx = (refined[2 * c] - sc.cx) / sc.fx, ny = (refined[2 * c + 1] - sc.cy) / sc.fy;
      c3md_undistort_normalised(sc.distortion, &nx, &ny);
      pinhole[2 * c] = static_cast<float>(nx * sc.fx + sc.cx);
      pinhole[2 * c + 1] = static_cast<float>(ny * sc.fy + sc.cy);
    }
    float centre[2];
    c3md_diagonal_centre(pinhole, centre);
    double nx = (centre[0] - sc.cx) / sc.fx, ny = (centre[1] - sc.cy) / sc.fy;
    c3md_distort_normalised(sc.distortion, &nx, &ny);
    double u, v;
    projectRaw(sc, 0, 0, u, v);
    worstCentre = std::max(worstCentre, std::hypot(nx * sc.fx + sc.cx - u, ny * sc.fy + sc.cy - v));
    index++;
  }
  std::printf("12 MP: worst corner error %.3f px, centre %.3f px\n", worstCorner, worstCentre);
  check(worstCorner < 0.2, "12 MP corner error below 0.2 px", worstCorner);
  check(worstCentre < 0.1, "12 MP centre error below 0.1 px", worstCentre);
}

void testSharpness() {
  Scene sc = makeScene(0.8, 10, 0, 9, false);
  float truth[8];
  truthCorners(sc, true, truth);
  double previous = 1e9;
  for (const double blur : {0.6, 1.2, 2.4}) {
    const auto img = render(sc, blur, 1.0, 11);
    const double s = c3md_edge_sharpness(img.data(), sc.width, sc.height, sc.width, truth);
    check(s < previous, "sharpness falls with blur", s);
    previous = s;
  }
  check(previous < 0.25 && previous > 0.05, "sharpness of blur 2.4 px", previous);
}

double rotationAngleDeg(const double* A, const double* B) {
  double tr = 0;
  for (int k = 0; k < 9; k++) tr += A[k] * B[k];
  return std::acos(std::max(-1.0, std::min(1.0, (tr - 1) / 2))) * 180 / kPi;
}

/// Threshold edges of the ambiguity classification (marker_pose.h).
void testPoseAmbiguityEdges() {
  using c3md::kPoseAmbiguityErrorRatio;
  using c3md::kPoseAmbiguityFlipCuePx;
  using c3md::kPoseAmbiguityMinFlipDeg;
  c3md::PoseAlternative alt;
  alt.found = true;
  alt.rms = 0.2;
  alt.rotationGapDeg = 30;
  const double strong = 10 * kPoseAmbiguityFlipCuePx;  // cue far above the regime bound
  // Error ratio: at the threshold ambiguous, just below not.
  check(c3md::squarePoseAmbiguous(kPoseAmbiguityErrorRatio * alt.rms, alt, strong, 15), "ratio at threshold");
  check(!c3md::squarePoseAmbiguous(std::nextafter(kPoseAmbiguityErrorRatio * alt.rms, 0.0), alt, strong, 15),
        "ratio just below threshold");
  check(c3md::squarePoseAmbiguous(alt.rms, alt, strong, 15), "equal errors");
  c3md::PoseAlternative exact = alt;
  exact.rms = 0;
  check(c3md::squarePoseAmbiguous(0, exact, strong, 15), "two exact fits");
  // Flip cue: just below the bound ambiguous, at it not.
  check(c3md::squarePoseAmbiguous(0.01, alt, std::nextafter(kPoseAmbiguityFlipCuePx, 0.0), 15), "cue below bound");
  check(!c3md::squarePoseAmbiguous(0.01, alt, kPoseAmbiguityFlipCuePx, 15), "cue at bound");
  check(c3md::squarePoseAmbiguous(0.01, alt, NAN, 15), "unknown cue is ambiguous");
  // Without an alternative only the cue decides.
  c3md::PoseAlternative none;
  check(!c3md::squarePoseAmbiguous(0.2, none, strong, 15), "no alternative, strong cue");
  check(c3md::squarePoseAmbiguous(0.2, none, 0.1, 15), "no alternative, weak cue");
  // Flip size: the larger of the gap and twice the viewing angle.
  c3md::PoseAlternative merged = alt;
  merged.rotationGapDeg = 0;
  const double edge = kPoseAmbiguityMinFlipDeg / 2;
  check(c3md::squarePoseAmbiguous(0.2, merged, 0.0, edge), "merged minima, flip at the minimum");
  check(!c3md::squarePoseAmbiguous(0.2, merged, 0.0, std::nextafter(edge, 0.0)), "merged minima, flip below it");
  merged.rotationGapDeg = kPoseAmbiguityMinFlipDeg;
  check(c3md::squarePoseAmbiguous(0.2, merged, 0.0, 0), "gap at the minimum flip");
  // Cue: f S^2 sin(angle) / |t|^2.
  const double t[3] = {0, 3, 4};
  check(std::abs(c3md::flipPerspectiveCuePx(1000, 0.1, t, 30) - 1000 * 0.01 * 0.5 / 25) < 1e-12, "cue formula",
        c3md::flipPerspectiveCuePx(1000, 0.1, t, 30));
  const double zero[3] = {0, 0, 0};
  check(c3md::flipPerspectiveCuePx(1000, 0.1, zero, 30) == 0, "cue of a degenerate pose");
}

/// IPPE planar ambiguity on rendered corners: a 21 px marker about 17 deg
/// off frontal flips under 0.1 px corner noise (the kept pose is the
/// mirror), and every flip is flagged; large oblique and frontal markers
/// stay unflagged.
void testPoseAmbiguityOnCorners() {
  int trials = 0, flips = 0, unflaggedFlips = 0, flagged = 0;
  for (uint64_t seed = 1; seed <= 200; seed++) {
    Scene sc = makeScene(7.0, 17, 25, seed, false);
    float corners[8];
    truthCorners(sc, false, corners);
    Rng rng{seed * 7919};
    for (auto& c : corners) c += static_cast<float>(0.1 * rng.gauss());
    c3md::Camera cam;
    cam.fx = sc.fx;
    cam.fy = sc.fy;
    cam.cx = sc.cx;
    cam.cy = sc.cy;
    double rv[3], tv[3], rms, R[9];
    c3md::PoseAlternative alt;
    if (!c3md::solveSquarePose(cam, sc.side / 2, corners, rv, tv, rms, &alt)) continue;
    trials++;
    rodrigues(rv, R);
    const double error = rotationAngleDeg(R, sc.R);
    const double angle = c3md::viewingAngleDeg(rv, tv);
    const bool ambiguous =
        c3md::squarePoseAmbiguous(rms, alt, c3md::flipPerspectiveCuePx(sc.fx, sc.side, tv, angle), angle);
    if (ambiguous) flagged++;
    if (error > 10) {
      flips++;
      if (!ambiguous) unflaggedFlips++;
    }
  }
  check(trials == 200, "small near-frontal trials solved", trials);
  check(flips >= 20, "small near-frontal markers flip without the bound", flips);
  check(unflaggedFlips == 0, "every flip is flagged ambiguous", unflaggedFlips);
  check(flagged == trials, "the whole flip-prone regime is flagged", flagged);

  // Large and oblique: unambiguous under the same noise.
  for (uint64_t seed = 1; seed <= 50; seed++) {
    Scene sc = makeScene(0.8, 50, 25, seed, false);
    float corners[8];
    truthCorners(sc, false, corners);
    Rng rng{seed * 104729};
    for (auto& c : corners) c += static_cast<float>(0.1 * rng.gauss());
    c3md::Camera cam;
    cam.fx = sc.fx;
    cam.fy = sc.fy;
    cam.cx = sc.cx;
    cam.cy = sc.cy;
    double rv[3], tv[3], rms;
    c3md::PoseAlternative alt;
    check(c3md::solveSquarePose(cam, sc.side / 2, corners, rv, tv, rms, &alt), "oblique pose solved", seed);
    const double angle = c3md::viewingAngleDeg(rv, tv);
    check(alt.found && alt.rms > 4 * rms, "oblique alternative clearly worse", alt.rms / std::max(rms, 1e-12));
    check(!c3md::squarePoseAmbiguous(rms, alt, c3md::flipPerspectiveCuePx(sc.fx, sc.side, tv, angle), angle),
          "large oblique marker not ambiguous", seed);
  }
  // Exactly frontal: the two solutions coincide; a flip changes nothing.
  Scene sc = makeScene(0.8, 0, 25, 3, false);
  sc.t[0] = sc.t[1] = 0;
  float corners[8];
  truthCorners(sc, false, corners);
  c3md::Camera cam;
  cam.fx = sc.fx;
  cam.fy = sc.fy;
  cam.cx = sc.cx;
  cam.cy = sc.cy;
  double rv[3], tv[3], rms;
  c3md::PoseAlternative alt;
  check(c3md::solveSquarePose(cam, sc.side / 2, corners, rv, tv, rms, &alt), "frontal pose solved");
  const double angle = c3md::viewingAngleDeg(rv, tv);
  check(!c3md::squarePoseAmbiguous(rms, alt, c3md::flipPerspectiveCuePx(sc.fx, sc.side, tv, angle), angle),
        "frontal marker not ambiguous", alt.rotationGapDeg);
}

void testAbiWithoutDetector() {
  check(c3md_abi_version() == C3MD_ABI_VERSION, "abi version");
  check(c3md_create(-1) == nullptr && c3md_create(C3MD_DICTIONARY_COUNT) == nullptr, "unknown dictionary refused");
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  check(d != nullptr, "detector created");
  check(c3md_set_camera(d, 0, 500, 320, 240, nullptr) == C3MD_ERROR_ARGUMENT, "zero focal length refused");
  check(c3md_set_camera(d, 500, 500, 320, 240, nullptr) == C3MD_OK, "camera set");
  check(c3md_set_marker_size(d, 0.15) == C3MD_OK, "marker size set");
  check(c3md_set_marker_size_for_id(d, C3MD_MAX_MARKER_ID + 1, 0.1) == C3MD_ERROR_ARGUMENT, "id range checked");
  check(c3md_set_pose_method(d, 7) == C3MD_ERROR_ARGUMENT, "pose method checked");
  std::vector<uint8_t> grey(640 * 480, 128);
  const int expected = c3md_detector_available() ? 0 : C3MD_ERROR_UNAVAILABLE;
  check(c3md_detect(d, grey.data(), 640, 480, 640) == expected, "detect on a blank image");
  check(c3md_detect(d, grey.data(), 640, 480, 600) == C3MD_ERROR_ARGUMENT, "stride below width refused");
  check(c3md_record_count(d) == 0, "no records");
  c3md_destroy(d);
}

// Boundary helpers of the JNI shim: output windows refuse negative offsets
// and counts, and the bitmap grey buffer reports a failed allocation instead
// of throwing (the shim sizes it before locking bitmap pixels).
void testJniBoundaries() {
  check(c3md::outputWindowBytes(-64, 4, 64) == -1, "negative output offset refused");
  check(c3md::outputWindowBytes(0, -1, 64) == -1, "negative record count refused");
  check(c3md::outputWindowBytes(16, 2, 64) == 144, "output window bytes");
  check(c3md::ensureGreyBuffer(nullptr, 16) == C3MD_ERROR_ARGUMENT, "null detector refused");
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  check(c3md::ensureGreyBuffer(d, 4096) == C3MD_OK && c3md::greyBuffer(d).size() >= 4096, "grey buffer grows");
  check(c3md::ensureGreyBuffer(d, static_cast<size_t>(-1)) == C3MD_ERROR_INTERNAL, "failed allocation reported");
  check(c3md::greyBuffer(d).size() >= 4096, "grey buffer kept after a failed allocation");
  check(c3md::ensureGreyBuffer(d, 1024) == C3MD_OK && c3md::greyBuffer(d).size() >= 4096, "grey buffer never shrinks");
  c3md_destroy(d);
}

}  // namespace

int main(int argc, char** argv) {
  testGolden(argc > 1 ? argv[1] : nullptr);
  testHalfFloat();
  testCentreAndAngle();
  testDistortionRoundTrip();
  testRefinement();
  testTwelveMegapixel();
  testSharpness();
  testPoseAmbiguityEdges();
  testPoseAmbiguityOnCorners();
  testAbiWithoutDetector();
  testJniBoundaries();
  if (failures == 0) std::printf("marker detector host test: all checks passed\n");
  return failures == 0 ? 0 : 1;
}
