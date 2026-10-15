// Accuracy test of the AprilTag detection path (C3MD_WITH_APRILTAG) on the
// host, without OpenCV:
//
// - printed sheets: every marker of the SVG sheets in
//   benchmarks/marker_detection/print is drawn from aruco_mip_36h12.json
//   (cell check) and decodes to its printed id on rendered frontal and
//   oblique 1920x1440 views, with corner error against the known corners;
// - range grid: one 10 cm marker at 0.5-4 m, tilted 0-60 deg, blurred and
//   noisy: detection rate, corner RMS (detector corners and edge-line
//   refined), pose error;
// - AprilTag 36h11 decodes with the same corner order; 4X4_50 and 6X6_250
//   are refused on this path;
// - no detections on noise and random block patterns;
// - timings per 1920x1440 frame and full-resolution tie points on 12 MP;
// - optional: recorded stills (BMP, converted from the JPEG stills) and the
//   golden images of the iOS XCTest (PNG plus expected JSON).
//
//   marker_detector_accuracy_test_apriltag --codes <aruco_mip_36h12.json>
//     --sheets <print folder> [--stills <bmp folder>] [--write-golden <dir>]
//
// Exit code 0 when every check passes; failures print one line each.

#include <dirent.h>
#include <zlib.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "marker_detector.h"
#include "marker_test_render.h"

extern "C" {
#include "tag36h11.h"
}

using namespace marker_test;

namespace {

int failures = 0;

void check(bool ok, const char* what, double value = 0) {
  if (!ok) {
    std::printf("FAIL %s (%.6g)\n", what, value);
    failures++;
  }
}

double nowMs() {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct Cam {
  int width, height;
  double f, cx, cy;
};

const Cam kLive{1920, 1440, 1440, 959.5, 719.5};

void mul3(const double* A, const double* B, double* C) {
  double out[9];
  for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) out[3 * i + j] = A[3 * i] * B[j] + A[3 * i + 1] * B[3 + j] + A[3 * i + 2] * B[6 + j];
  }
  std::memcpy(C, out, sizeof out);
}

void rodrigues(const double* r, double* R) {
  const double a = std::sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
  if (a < 1e-12) {
    const double id[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    std::memcpy(R, id, sizeof id);
    return;
  }
  const double x = r[0] / a, y = r[1] / a, z = r[2] / a, c = std::cos(a), s = std::sin(a), k = 1 - c;
  const double m[9] = {k * x * x + c,     k * x * y - s * z, k * x * z + s * y, k * x * y + s * z, k * y * y + c,
                       k * y * z - s * x, k * x * z - s * y, k * y * z + s * x, k * z * z + c};
  std::memcpy(R, m, sizeof m);
}

/// R = Rz(roll) Ry(tiltY) Rx(tiltX) R0, angles in degrees.
void orient(const double* R0, double tiltX, double tiltY, double roll, double* R) {
  double Rx[9], Ry[9], Rz[9];
  const double rx[3] = {tiltX * kPi / 180, 0, 0}, ry[3] = {0, tiltY * kPi / 180, 0}, rz[3] = {0, 0, roll * kPi / 180};
  rodrigues(rx, Rx);
  rodrigues(ry, Ry);
  rodrigues(rz, Rz);
  mul3(Rx, R0, R);
  mul3(Ry, R, R);
  mul3(Rz, R, R);
}

/// Homography plane -> pixels of a camera-from-plane pose; `S` maps plane
/// units to metric plane coordinates (z = 0).
void homography(const Cam& cam, const double* R, const double* t, const double* S, double* H) {
  const double P[9] = {R[0], R[1], t[0], R[3], R[4], t[1], R[6], R[7], t[2]};
  const double K[9] = {cam.f, 0, cam.cx, 0, cam.f, cam.cy, 0, 0, 1};
  double KP[9];
  mul3(K, P, KP);
  mul3(KP, S, H);
}

struct Truth {
  int id;
  float c[8];
};

struct Stats {
  std::vector<double> v;
  void add(double x) { v.push_back(x); }
  double rms() const {
    double s = 0;
    for (double x : v) s += x * x;
    return v.empty() ? NAN : std::sqrt(s / v.size());
  }
  double quantile(double q) const {
    if (v.empty()) return NAN;
    std::vector<double> s(v);
    std::sort(s.begin(), s.end());
    return s[std::min(s.size() - 1, static_cast<size_t>(q * (s.size() - 1) + 0.5))];
  }
  double max() const { return v.empty() ? NAN : *std::max_element(v.begin(), v.end()); }
};

struct Outcome {
  int expected = 0, found = 0, wrongIds = 0;
};

/// Detects on `img` and matches detections to `truth` by id (nearest centre);
/// corner errors per corner into `corners`, edge-line refined into `refined`.
Outcome evaluate(C3MarkerDetector* d, const std::vector<uint8_t>& img, int w, int h, const std::vector<Truth>& truth,
                 Stats& corners, Stats* refined) {
  Outcome o;
  o.expected = static_cast<int>(truth.size());
  const int n = c3md_detect(d, img.data(), w, h, w);
  check(n >= 0, "detect succeeds", n);
  const C3MarkerDetectionRecord* records = c3md_records(d);
  std::vector<bool> used(truth.size(), false);
  for (int i = 0; i < n; i++) {
    const C3MarkerDetectionRecord& r = records[i];
    const float* c = r.corners_px;
    const double mx = (c[0] + c[2] + c[4] + c[6]) / 4, my = (c[1] + c[3] + c[5] + c[7]) / 4;
    int best = -1;
    double bestD = 1e18;
    for (size_t k = 0; k < truth.size(); k++) {
      if (used[k]) continue;
      const float* t = truth[k].c;
      const double dd = std::hypot((t[0] + t[2] + t[4] + t[6]) / 4 - mx, (t[1] + t[3] + t[5] + t[7]) / 4 - my);
      if (dd < bestD) {
        bestD = dd;
        best = static_cast<int>(k);
      }
    }
    if (best < 0 || bestD > 20 || truth[best].id != r.marker_id) {
      o.wrongIds++;
      continue;
    }
    used[best] = true;
    o.found++;
    for (int k = 0; k < 4; k++) {
      corners.add(std::hypot(c[2 * k] - truth[best].c[2 * k], c[2 * k + 1] - truth[best].c[2 * k + 1]));
    }
    if (refined != nullptr) {
      float out[8];
      if (c3md_refine_edges(img.data(), w, h, w, c, 8, nullptr, nullptr, out) == C3MD_OK) {
        for (int k = 0; k < 4; k++) {
          refined->add(std::hypot(out[2 * k] - truth[best].c[2 * k], out[2 * k + 1] - truth[best].c[2 * k + 1]));
        }
      }
    }
  }
  return o;
}

// ---- printed sheets ---------------------------------------------------------

struct SheetView {
  const char* name;
  double tiltX, tiltY, roll, blur, noise;
};

void homography(const Cam& cam, const double* R, const double* t, const double* S, double* H);

/// Pose that shows the whole sheet at about `fill` of the image, moved back
/// until every sheet corner lies 8 px inside the image.
void sheetPose(const Cam& cam, const Sheet& sheet, const SheetView& v, double fill, double* R, double* t, double* S) {
  const double I[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  orient(I, v.tiltX, v.tiltY, v.roll, R);
  const double wm = sheet.width / 1000, hm = sheet.height / 1000;
  t[0] = 0;
  t[1] = 0;
  t[2] = cam.f * std::max(wm / (fill * cam.width), hm / (fill * cam.height));
  const double s[9] = {1e-3, 0, -wm / 2, 0, 1e-3, -hm / 2, 0, 0, 1};
  std::memcpy(S, s, sizeof s);
  for (int attempt = 0; attempt < 40; attempt++) {
    double H[9];
    homography(cam, R, t, S, H);
    bool inside = true;
    for (const double x : {0.0, sheet.width}) {
      for (const double y : {0.0, sheet.height}) {
        double u, w;
        inside = inside && applyH(H, x, y, u, w) && u > 8 && w > 8 && u < cam.width - 9 && w < cam.height - 9;
      }
    }
    if (inside) return;
    t[2] *= 1.05;
  }
}

std::vector<uint8_t> renderSheet(const Cam& cam, const Sheet& sheet, const double* H, double blur, double noise,
                                 uint64_t seed) {
  auto ink = [&](double x, double y) -> int {
    if (x < 0 || y < 0 || x >= sheet.width || y >= sheet.height) return 90;
    return sheet.black(x, y) ? 35 : 215;
  };
  auto img = renderPlane(cam.width, cam.height, H, ink, 90, 3);
  gaussianBlur(img, cam.width, cam.height, blur);
  addNoise(img, noise, seed);
  return img;
}

std::vector<Truth> sheetTruth(const Sheet& sheet, const double* H) {
  std::vector<Truth> truth;
  for (const PrintedMarker& m : sheet.markers) {
    Truth t{m.id, {}};
    const double xs[4] = {m.x, m.x + m.size, m.x + m.size, m.x}, ys[4] = {m.y, m.y, m.y + m.size, m.y + m.size};
    for (int k = 0; k < 4; k++) {
      double u, v;
      applyH(H, xs[k], ys[k], u, v);
      t.c[2 * k] = static_cast<float>(u);
      t.c[2 * k + 1] = static_cast<float>(v);
    }
    truth.push_back(t);
  }
  return truth;
}

std::vector<std::string> listFiles(const std::string& dir, const char* prefix, const char* suffix) {
  std::vector<std::string> out;
  DIR* d = opendir(dir.c_str());
  if (d == nullptr) return out;
  while (dirent* e = readdir(d)) {
    const std::string name = e->d_name;
    if (name.rfind(prefix, 0) == 0 && name.size() > std::strlen(suffix) &&
        name.compare(name.size() - std::strlen(suffix), std::strlen(suffix), suffix) == 0) {
      out.push_back(dir + "/" + name);
    }
  }
  closedir(d);
  std::sort(out.begin(), out.end());
  return out;
}

void testPrintedSheets(const std::string& dir, const std::vector<uint64_t>& codes, C3MarkerDetector* d) {
  std::vector<std::string> pages = listFiles(dir, "page_", ".svg");
  const std::vector<std::string> tapes = listFiles(dir, "long_tape_page_", ".svg");
  pages.insert(pages.end(), tapes.begin(), tapes.end());
  check(pages.size() == 16, "16 printed sheets", static_cast<double>(pages.size()));
  const SheetView views[] = {{"frontal", 0, 0, 0, 0.7, 1.5}, {"oblique 40 deg", 0, 40, 8, 0.9, 2.0},
                             {"oblique 55 deg", 50, -20, -15, 1.0, 2.5}};
  Stats corners;
  int markers = 0, expected = 0, found = 0, wrong = 0, cellMismatches = 0;
  uint64_t seed = 1;
  for (const std::string& path : pages) {
    Sheet sheet;
    check(parseSheet(path, sheet), "sheet parses");
    markers += static_cast<int>(sheet.markers.size());
    for (const PrintedMarker& m : sheet.markers) {
      check(m.id >= 0 && m.id < static_cast<int>(codes.size()), "printed id in the dictionary", m.id);
      if (m.id < 0 || m.id >= static_cast<int>(codes.size())) continue;
      const double cell = m.size / 8;
      for (int r = 0; r < 8; r++) {
        for (int c = 0; c < 8; c++) {
          if (sheet.black(m.x + (c + 0.5) * cell, m.y + (r + 0.5) * cell) != isBlack(codes[m.id], r, c)) {
            cellMismatches++;
          }
        }
      }
    }
    int pageFound = 0, pageExpected = 0;
    for (const SheetView& v : views) {
      double R[9], t[3], S[9], H[9];
      sheetPose(kLive, sheet, v, 0.92, R, t, S);
      homography(kLive, R, t, S, H);
      const auto img = renderSheet(kLive, sheet, H, v.blur, v.noise, seed++);
      const Outcome o = evaluate(d, img, kLive.width, kLive.height, sheetTruth(sheet, H), corners, nullptr);
      expected += o.expected;
      found += o.found;
      wrong += o.wrongIds;
      pageFound += o.found;
      pageExpected += o.expected;
    }
    std::printf("  %-22s %2zu markers, decoded %d of %d views x markers\n", sheet.name.c_str(), sheet.markers.size(),
                pageFound, pageExpected);
  }
  std::printf("printed sheets: %d markers on %zu sheets, cells equal to the code table: %s; decoded %d of %d (%.1f %%), "
              "wrong ids %d; corner error RMS %.3f px, p95 %.3f px, max %.3f px\n",
              markers, pages.size(), cellMismatches == 0 ? "yes" : "NO", found, expected, 100.0 * found / std::max(1, expected),
              wrong, corners.rms(), corners.quantile(0.95), corners.max());
  check(cellMismatches == 0, "printed cells equal the code table", cellMismatches);
  check(found == expected, "every printed marker decodes to its id in every view", expected - found);
  check(wrong == 0, "no wrong ids on the sheets", wrong);
  check(corners.rms() < 0.3, "sheet corner RMS below 0.3 px", corners.rms());
}

// ---- synthetic range grid ---------------------------------------------------

struct MarkerScene {
  double R[9], t[3];
  double side = 0.1;
  uint64_t code = 0;
  int id = 0;
};

/// Marker frame: x right, y up, z out of the face; facing the camera is a
/// rotation of pi about x.
MarkerScene makeMarker(double distance, double tilt, double roll, int id, uint64_t code, Rng& rng) {
  MarkerScene m;
  double facing[9];
  const double rf[3] = {kPi, 0, 0};
  rodrigues(rf, facing);
  orient(facing, 0, tilt, roll, m.R);
  m.t[0] = (rng.uniform() - 0.5) * 0.4 * distance;
  m.t[1] = (rng.uniform() - 0.5) * 0.3 * distance;
  m.t[2] = distance;
  m.id = id;
  m.code = code;
  return m;
}

template <typename Ink>
std::vector<uint8_t> renderMarker(const Cam& cam, const MarkerScene& m, Ink ink, double blur, double noise,
                                  uint64_t seed, Truth& truth, double* H) {
  const double S[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  homography(cam, m.R, m.t, S, H);
  const double h = m.side / 2;
  double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
  const double xs[4] = {-h, h, h, -h}, ys[4] = {h, h, -h, -h};
  truth.id = m.id;
  for (int k = 0; k < 4; k++) {
    double u, v;
    applyH(H, xs[k], ys[k], u, v);
    truth.c[2 * k] = static_cast<float>(u);
    truth.c[2 * k + 1] = static_cast<float>(v);
    applyH(H, xs[k] * 1.4, ys[k] * 1.4, u, v);
    x0 = std::min(x0, u);
    y0 = std::min(y0, v);
    x1 = std::max(x1, u);
    y1 = std::max(y1, v);
  }
  auto img = renderPlane(cam.width, cam.height, H, ink, 128, 4, static_cast<int>(x0) - 2, static_cast<int>(y0) - 2,
                         static_cast<int>(x1) + 3, static_cast<int>(y1) + 3);
  gaussianBlur(img, cam.width, cam.height, blur);
  addNoise(img, noise, seed);
  return img;
}

/// Grey value of the ArUco marker `m` (with a white quiet zone of one
/// module and a surround halfway between `black` and `white`).
auto arucoInk(const MarkerScene& m, int black = 35, int white = 215) {
  return [&m, black, white](double X, double Y) -> int {
    const double h = m.side / 2, module = m.side / 8;
    if (std::abs(X) > h + module || std::abs(Y) > h + module) return (black + white) / 2;
    if (std::abs(X) > h || std::abs(Y) > h) return white;
    const int col = std::min(7, static_cast<int>((X + h) / module));
    const int row = std::min(7, static_cast<int>((h - Y) / module));
    return isBlack(m.code, row, col) ? black : white;
  };
}

/// Dim scenes: printed black and white only 40 grey levels apart (camera
/// image in a dark room), noise 3, at 1-3 m.
void testLowContrast(const std::vector<uint64_t>& codes, C3MarkerDetector* d) {
  Rng rng{1234};
  int found = 0, expected = 0, wrong = 0;
  Stats corners;
  for (const double distance : {1.0, 2.0, 3.0}) {
    for (const double tilt : {0.0, 40.0}) {
      const int id = 200 + static_cast<int>(distance * 10 + tilt);
      MarkerScene m = makeMarker(distance, tilt, 12, id % 250, codes[id % 250], rng);
      Truth truth{};
      double H[9];
      const auto img = renderMarker(kLive, m, arucoInk(m, 70, 110), 1.0, 3.0, rng.s, truth, H);
      const Outcome o = evaluate(d, img, kLive.width, kLive.height, {truth}, corners, nullptr);
      found += o.found;
      wrong += o.wrongIds;
      expected++;
    }
  }
  std::printf("low contrast (40 grey levels, noise 3, 1-3 m): detected %d of %d, wrong ids %d, corner RMS %.3f px\n",
              found, expected, wrong, corners.rms());
  check(found == expected && wrong == 0, "low-contrast markers detected", expected - found);
}

double rotationErrorDeg(const double* R, const float* rvec) {
  double rv[3] = {rvec[0], rvec[1], rvec[2]}, E[9], Rt[9], D[9];
  rodrigues(rv, E);
  for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) Rt[3 * i + j] = R[3 * j + i];
  }
  mul3(E, Rt, D);
  const double c = std::max(-1.0, std::min(1.0, (D[0] + D[4] + D[8] - 1) / 2));
  return std::acos(c) * 180 / kPi;
}

void testRangeGrid(const std::vector<uint64_t>& codes, C3MarkerDetector* d) {
  const double distances[] = {0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0};
  const double tilts[] = {0, 30, 45, 60};
  const double blurs[] = {0.7, 1.2};
  Rng rng{42};
  Stats corners, refined, translation, rotation;
  int expectedNear = 0, foundNear = 0, expectedAll = 0, foundAll = 0, wrong = 0;
  std::printf("range grid (10 cm marker, 1920x1440, f 1440 px, noise 2.5):\n  distance m:");
  for (double dist : distances) std::printf(" %5.1f", dist);
  std::printf("\n");
  c3md_set_camera(d, kLive.f, kLive.f, kLive.cx, kLive.cy, nullptr);
  c3md_set_marker_size(d, 0.1);
  for (double tilt : tilts) {
    std::printf("  tilt %2.0f deg: ", tilt);
    for (double dist : distances) {
      int found = 0, expected = 0;
      for (double blur : blurs) {
        const int id = static_cast<int>(rng.uniform() * codes.size()) % static_cast<int>(codes.size());
        MarkerScene m = makeMarker(dist, tilt, (rng.uniform() - 0.5) * 60, id, codes[id], rng);
        Truth truth{};
        double H[9];
        const auto img = renderMarker(kLive, m, arucoInk(m), blur, 2.5, rng.s, truth, H);
        const Outcome o = evaluate(d, img, kLive.width, kLive.height, {truth}, corners, &refined);
        wrong += o.wrongIds;
        expected++;
        found += o.found;
        if (o.found == 1) {
          check(c3md_estimate_pose(d) == 1, "pose of the detected marker");
          const C3MarkerDetectionRecord& r = c3md_records(d)[0];
          const double dt = std::hypot(std::hypot(r.tvec_camera_from_marker_m[0] - m.t[0],
                                                  r.tvec_camera_from_marker_m[1] - m.t[1]),
                                       r.tvec_camera_from_marker_m[2] - m.t[2]);
          translation.add(dt / dist * 100);
          rotation.add(rotationErrorDeg(m.R, r.rvec_camera_from_marker));
        }
      }
      std::printf(" %d/%d  ", found, expected);
      expectedAll += expected;
      foundAll += found;
      if (dist <= 2.0 && tilt <= 45) {
        expectedNear += expected;
        foundNear += found;
      }
    }
    std::printf("\n");
  }
  std::printf("range grid: detected %d of %d (%.1f %%), within 2 m and 45 deg %d of %d; wrong ids %d\n", foundAll,
              expectedAll, 100.0 * foundAll / expectedAll, foundNear, expectedNear, wrong);
  std::printf("  corner error: detector RMS %.3f px (p95 %.3f, max %.3f); edge-line refined RMS %.3f px (p95 %.3f)\n",
              corners.rms(), corners.quantile(0.95), corners.max(), refined.rms(), refined.quantile(0.95));
  std::printf("  pose error: translation median %.2f %% of distance (p95 %.2f %%), rotation median %.2f deg (p95 %.2f)\n",
              translation.quantile(0.5), translation.quantile(0.95), rotation.quantile(0.5), rotation.quantile(0.95));
  check(foundNear == expectedNear, "every marker within 2 m and 45 deg detected", expectedNear - foundNear);
  check(foundAll >= 0.8 * expectedAll, "at least 80 % of the range grid detected", foundAll);
  check(wrong == 0, "no wrong ids in the range grid", wrong);
  check(corners.rms() < 0.35, "range grid corner RMS below 0.35 px", corners.rms());
  check(translation.quantile(0.5) < 2.0, "median translation error below 2 % of the distance",
        translation.quantile(0.5));
}

// ---- other dictionaries and false positives ---------------------------------

void testOtherDictionaries() {
  check(c3md_create(C3MD_DICTIONARY_4X4_50) == nullptr, "4X4_50 refused on the AprilTag path");
  check(c3md_create(C3MD_DICTIONARY_6X6_250) == nullptr, "6X6_250 refused on the AprilTag path");
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_APRILTAG_36H11);
  check(d != nullptr, "AprilTag 36h11 detector created");
  if (d == nullptr) return;
  apriltag_family_t* family = tag36h11_create();
  const int id = 7;
  const uint64_t code = family->codes[id];
  // Cell (x, y) of the face, border included: bit i sits at bit_x[i], bit_y[i].
  bool white[8][8] = {};
  for (uint32_t i = 0; i < family->nbits; i++) {
    white[family->bit_y[i]][family->bit_x[i]] = (code >> (family->nbits - 1 - i)) & 1;
  }
  tag36h11_destroy(family);
  Rng rng{9};
  MarkerScene m = makeMarker(0.8, 25, 20, id, 0, rng);
  auto ink = [&](double X, double Y) -> int {
    const double h = m.side / 2, module = m.side / 8;
    if (std::abs(X) > h + module || std::abs(Y) > h + module) return 128;
    if (std::abs(X) > h || std::abs(Y) > h) return 215;
    const int col = std::min(7, static_cast<int>((X + h) / module));
    const int row = std::min(7, static_cast<int>((h - Y) / module));
    return white[row][col] ? 215 : 35;
  };
  Truth truth{};
  double H[9];
  const auto img = renderMarker(kLive, m, ink, 0.8, 2.0, 5, truth, H);
  Stats corners;
  const Outcome o = evaluate(d, img, kLive.width, kLive.height, {truth}, corners, nullptr);
  std::printf("AprilTag 36h11: id %d decoded %d of 1, corner error max %.3f px\n", id, o.found, corners.max());
  check(o.found == 1 && o.wrongIds == 0, "36h11 tag decodes with aruco corner order");
  c3md_destroy(d);
}

void testNoFalsePositives(C3MarkerDetector* d) {
  Rng rng{77};
  std::vector<uint8_t> noise(static_cast<size_t>(kLive.width) * kLive.height);
  for (auto& p : noise) p = static_cast<uint8_t>(rng.uniform() * 256);
  int detections = c3md_detect(d, noise.data(), kLive.width, kLive.height, kLive.width);
  // Random 8 x 8 module blocks with black borders: valid quads with random bits.
  std::vector<uint8_t> blocks(static_cast<size_t>(kLive.width) * kLive.height, 215);
  int quads = 0;
  for (int by = 0; by + 90 < kLive.height; by += 96) {
    for (int bx = 0; bx + 90 < kLive.width; bx += 96) {
      const uint64_t bits = static_cast<uint64_t>(rng.uniform() * (1ull << 36));
      for (int y = 0; y < 80; y++) {
        for (int x = 0; x < 80; x++) {
          blocks[static_cast<size_t>(by + y + 8) * kLive.width + bx + x + 8] = isBlack(bits, y / 10, x / 10) ? 35 : 215;
        }
      }
      quads++;
    }
  }
  gaussianBlur(blocks, kLive.width, kLive.height, 0.8);
  const int blockDetections = c3md_detect(d, blocks.data(), kLive.width, kLive.height, kLive.width);
  std::printf("false positives: %d on noise, %d on %d random-bit quads (a random code is within 2 bits of one of "
              "250 x 4 codes with probability %.1e)\n",
              detections, blockDetections, quads, 1000.0 * 667 / std::pow(2.0, 36));
  check(detections == 0, "no detections on noise", detections);
  check(blockDetections == 0, "no detections on random-bit quads", blockDetections);
}

// ---- timings and tie points -------------------------------------------------

double medianMs(const std::vector<double>& v) {
  std::vector<double> s(v);
  std::sort(s.begin(), s.end());
  return s[s.size() / 2];
}

void testTimings(const std::string& sheetsDir, const std::vector<uint64_t>& codes, C3MarkerDetector* d) {
  Sheet sheet;
  parseSheet(sheetsDir + "/long_tape_page_01.svg", sheet);
  double R[9], t[3], S[9], H[9];
  const SheetView view{"oblique", 20, 25, 5, 0.8, 2.0};
  sheetPose(kLive, sheet, view, 0.9, R, t, S);
  homography(kLive, R, t, S, H);
  const auto page = renderSheet(kLive, sheet, H, view.blur, view.noise, 3);
  Rng rng{5};
  MarkerScene m = makeMarker(1.5, 20, 10, 3, codes[3], rng);
  Truth truth{};
  const auto single = renderMarker(kLive, m, arucoInk(m), 0.8, 2.5, 4, truth, H);
  std::vector<uint8_t> empty(static_cast<size_t>(kLive.width) * kLive.height, 128);
  addNoise(empty, 2.5, 6);
  c3md_set_camera(d, kLive.f, kLive.f, kLive.cx, kLive.cy, nullptr);
  struct Case {
    const char* name;
    const std::vector<uint8_t>* img;
  } cases[] = {{"no marker", &empty}, {"one marker", &single}, {"tape sheet", &page}};
  std::printf("timings per 1920x1440 frame (host, one thread, median of 15):\n");
  for (const Case& c : cases) {
    std::vector<double> detect, pose;
    int n = 0;
    for (int i = 0; i < 15; i++) {
      const double t0 = nowMs();
      n = c3md_detect(d, c.img->data(), kLive.width, kLive.height, kLive.width);
      const double t1 = nowMs();
      c3md_estimate_pose(d);
      pose.push_back(nowMs() - t1);
      detect.push_back(t1 - t0);
    }
    std::printf("  %-11s %d markers: detect %.1f ms, pose %.3f ms\n", c.name, n, medianMs(detect), medianMs(pose));
    check(medianMs(detect) < 40, "detection below 40 ms per 1920x1440 frame on the host", medianMs(detect));
  }
}

void testTiePoints(const std::vector<uint64_t>& codes) {
  const Cam still{4032, 3024, 2900, 2015.5, 1511.5};
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  c3md_set_camera(d, still.f, still.f, still.cx, still.cy, nullptr);
  c3md_set_marker_size(d, 0.1);
  Stats detector, refined;
  Rng rng{31};
  std::vector<double> times[2];
  int found = 0, expected = 0;
  for (const double distance : {1.0, 2.0, 3.0}) {
    const int id = 10 + static_cast<int>(distance);
    MarkerScene m = makeMarker(distance, 35, 15, id, codes[id], rng);
    Truth truth{};
    double H[9];
    const auto img = renderMarker(still, m, arucoInk(m), 0.9, 3.0, 50 + id, truth, H);
    for (int f = 1; f <= 2; f++) {
      const double t0 = nowMs();
      const int n = c3md_detect_tiepoints(d, img.data(), still.width, still.height, still.width, f, C3MD_REFINE_EDGE_LINES);
      times[f - 1].push_back(nowMs() - t0);
      expected++;
      if (n != 1 || c3md_tiepoints(d)[0].marker_id != id) continue;
      found++;
      const C3MarkerTiePoint& tp = c3md_tiepoints(d)[0];
      check(tp.flags & C3MD_TIE_SUBPIXEL_REFINED, "tie point refined");
      for (int k = 0; k < 4; k++) {
        refined.add(std::hypot(tp.corners_px[2 * k] - truth.c[2 * k], tp.corners_px[2 * k + 1] - truth.c[2 * k + 1]));
      }
      const int coarse = c3md_detect_tiepoints(d, img.data(), still.width, still.height, still.width, f,
                                               C3MD_REFINE_CORNER_SUBPIX);
      if (coarse == 1) {
        const C3MarkerTiePoint& c = c3md_tiepoints(d)[0];
        for (int k = 0; k < 4; k++) {
          detector.add(std::hypot(c.corners_px[2 * k] - truth.c[2 * k], c.corners_px[2 * k + 1] - truth.c[2 * k + 1]));
        }
      }
    }
  }
  std::printf("tie points 4032x3024 (1-3 m, 35 deg): found %d of %d; corner RMS AprilTag-refined %.3f px, edge lines "
              "%.3f px (max %.3f); time %.0f ms full resolution, %.0f ms with search downscale 2\n",
              found, expected, detector.rms(), refined.rms(), refined.max(), medianMs(times[0]), medianMs(times[1]));
  check(found == expected, "every tie point marker found", expected - found);
  check(refined.max() < 0.3, "tie point corner error below 0.3 px", refined.max());
  c3md_destroy(d);
}

/// c3md_solve_points without OpenCV: a tape of eight 55 mm markers (32
/// coplanar corners) and a non-planar point set, 0.2 px image noise, with
/// lens distortion.
void testSolvePoints() {
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  const double distortion[5] = {0.08, -0.05, 0.0005, -0.0003, 0};
  c3md_set_camera(d, kLive.f, kLive.f, kLive.cx, kLive.cy, distortion);
  Rng rng{17};
  for (int planar = 1; planar >= 0; planar--) {
    std::vector<double> object;
    if (planar) {
      for (int m = 0; m < 8; m++) {
        const double x0 = m * 0.07, s = 0.055;
        const double c[8] = {x0, s, x0 + s, s, x0 + s, 0, x0, 0};
        for (int k = 0; k < 4; k++) object.insert(object.end(), {c[2 * k], c[2 * k + 1], 0.0});
      }
    } else {
      for (int i = 0; i < 12; i++) object.insert(object.end(), {rng.uniform() * 0.5, rng.uniform() * 0.4, rng.uniform() * 0.3});
    }
    const int n = static_cast<int>(object.size() / 3);
    double R[9], facing[9];
    const double rf[3] = {kPi, 0, 0};
    rodrigues(rf, facing);
    orient(facing, 15, 30, 10, R);
    const double t[3] = {-0.25, 0.1, 1.2};
    std::vector<float> image(2 * n);
    for (int i = 0; i < n; i++) {
      const double* X = &object[3 * i];
      double p[3];
      for (int r = 0; r < 3; r++) p[r] = R[3 * r] * X[0] + R[3 * r + 1] * X[1] + R[3 * r + 2] * X[2] + t[r];
      double x = p[0] / p[2], y = p[1] / p[2];
      c3md_distort_normalised(distortion, &x, &y);
      image[2 * i] = static_cast<float>(kLive.f * x + kLive.cx + rng.gauss() * 0.2);
      image[2 * i + 1] = static_cast<float>(kLive.f * y + kLive.cy + rng.gauss() * 0.2);
    }
    double out[7];
    const int status = c3md_solve_points(d, n, object.data(), image.data(), C3MD_POSE_IPPE_SQUARE, out);
    const double dt = std::sqrt((out[3] - t[0]) * (out[3] - t[0]) + (out[4] - t[1]) * (out[4] - t[1]) +
                                (out[5] - t[2]) * (out[5] - t[2]));
    const float rv[3] = {static_cast<float>(out[0]), static_cast<float>(out[1]), static_cast<float>(out[2])};
    std::printf("solve points (%s, %d points): translation error %.2f mm, rotation %.3f deg, RMS %.3f px\n",
                planar ? "tape, planar" : "non-planar", n, dt * 1000, rotationErrorDeg(R, rv), out[6]);
    check(status == C3MD_OK, "solve points succeeds", status);
    check(dt < 0.003 && out[6] < 0.4, "solve points recovers the pose", dt);
  }
  c3md_destroy(d);
}

// ---- recorded stills and goldens -------------------------------------------

void testStills(const std::string& dir) {
  const auto files = listFiles(dir, "", ".bmp");
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  int total = 0;
  std::vector<double> times;
  for (const std::string& f : files) {
    std::vector<uint8_t> grey;
    int w = 0, h = 0;
    if (!readBmpGrey(f, grey, w, h)) {
      std::printf("  %s: unreadable\n", f.c_str());
      continue;
    }
    c3md_set_camera(d, 1522.65, 1522.65, w / 2.0 - 0.5, h / 2.0 - 0.5, nullptr);
    const double t0 = nowMs();
    const int n = c3md_detect_tiepoints(d, grey.data(), w, h, w, 1, C3MD_REFINE_EDGE_LINES);
    times.push_back(nowMs() - t0);
    total += std::max(0, n);
    if (n > 0) {
      std::printf("  %s: %d markers, ids", f.substr(f.find_last_of('/') + 1).c_str(), n);
      for (int i = 0; i < n; i++) std::printf(" %d", c3md_tiepoints(d)[i].marker_id);
      std::printf("\n");
    }
  }
  std::printf("recorded stills: %zu images, %d markers found, %.0f ms median per still\n", files.size(), total,
              times.empty() ? 0.0 : medianMs(times));
  c3md_destroy(d);
}

void put32(std::string& s, uint32_t v) {
  for (int k = 3; k >= 0; k--) s.push_back(static_cast<char>((v >> (8 * k)) & 0xff));
}

void chunk(std::string& png, const char* type, const std::string& data) {
  put32(png, static_cast<uint32_t>(data.size()));
  std::string body(type, 4);
  body += data;
  png += body;
  put32(png, static_cast<uint32_t>(crc32(0, reinterpret_cast<const Bytef*>(body.data()), static_cast<uInt>(body.size()))));
}

bool writePngGrey(const std::string& path, const std::vector<uint8_t>& img, int w, int h) {
  std::string raw;
  for (int y = 0; y < h; y++) {
    raw.push_back(0);
    raw.append(reinterpret_cast<const char*>(img.data() + static_cast<size_t>(y) * w), w);
  }
  uLongf size = compressBound(raw.size());
  std::string z(size, '\0');
  if (compress2(reinterpret_cast<Bytef*>(&z[0]), &size, reinterpret_cast<const Bytef*>(raw.data()), raw.size(), 9) != Z_OK) {
    return false;
  }
  z.resize(size);
  std::string png("\x89PNG\r\n\x1a\n", 8), ihdr;
  put32(ihdr, w);
  put32(ihdr, h);
  ihdr += std::string("\x08\x00\x00\x00\x00", 5);  // 8-bit grey, no interlace
  chunk(png, "IHDR", ihdr);
  chunk(png, "IDAT", z);
  chunk(png, "IEND", "");
  FILE* f = std::fopen(path.c_str(), "wb");
  if (f == nullptr) return false;
  std::fwrite(png.data(), 1, png.size(), f);
  std::fclose(f);
  return true;
}

/// Golden images of the iOS XCTest: rendered sheets with their known corners
/// and this detector's records (ids, corners, poses).
void writeGoldens(const std::string& sheetsDir, const std::string& out) {
  const Cam cam{1280, 960, 960, 639.5, 479.5};
  struct Golden {
    const char* sheet;
    const char* name;
    SheetView view;
    double markerSize;
  } goldens[] = {{"page_01.svg", "markers_free_frontal", {"frontal", 8, -10, 3, 0.8, 0}, 0.1},
                 {"long_tape_page_01.svg", "markers_tape_oblique", {"oblique", 25, 35, -6, 0.9, 0}, 0.055}};
  std::string json = "{\n  \"camera\": {\"width\": 1280, \"height\": 960, \"fx\": 960, \"fy\": 960, \"cx\": 639.5, "
                     "\"cy\": 479.5},\n  \"images\": [\n";
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  c3md_set_camera(d, cam.f, cam.f, cam.cx, cam.cy, nullptr);
  for (size_t g = 0; g < 2; g++) {
    Sheet sheet;
    check(parseSheet(sheetsDir + "/" + goldens[g].sheet, sheet), "golden sheet parses");
    double R[9], t[3], S[9], H[9];
    sheetPose(cam, sheet, goldens[g].view, 0.9, R, t, S);
    homography(cam, R, t, S, H);
    const auto img = renderSheet(cam, sheet, H, goldens[g].view.blur, 0, 1);
    const std::string file = std::string(goldens[g].name) + ".png";
    check(writePngGrey(out + "/" + file, img, cam.width, cam.height), "golden PNG written");
    c3md_set_marker_size(d, goldens[g].markerSize);
    const int n = c3md_detect(d, img.data(), cam.width, cam.height, cam.width);
    c3md_estimate_pose(d);
    const auto truth = sheetTruth(sheet, H);
    char buf[512];
    std::snprintf(buf, sizeof buf, "    {\"file\": \"%s\", \"sheet\": \"%s\", \"markerSizeMetres\": %.3f, \"markers\": [\n",
                  file.c_str(), goldens[g].sheet, goldens[g].markerSize);
    json += buf;
    for (int i = 0; i < n; i++) {
      const C3MarkerDetectionRecord& r = c3md_records(d)[i];
      const Truth* tr = nullptr;
      for (const Truth& x : truth) {
        if (x.id == r.marker_id) tr = &x;
      }
      check(tr != nullptr, "golden detection has a printed marker", r.marker_id);
      if (tr == nullptr) continue;
      json += "      {\"id\": " + std::to_string(r.marker_id) + ", \"truth\": [";
      for (int k = 0; k < 8; k++) {
        std::snprintf(buf, sizeof buf, "%s%.4f", k ? ", " : "", tr->c[k]);
        json += buf;
      }
      json += "], \"detected\": [";
      for (int k = 0; k < 8; k++) {
        std::snprintf(buf, sizeof buf, "%s%.4f", k ? ", " : "", r.corners_px[k]);
        json += buf;
      }
      std::snprintf(buf, sizeof buf, "], \"tvec\": [%.5f, %.5f, %.5f]}%s\n", r.tvec_camera_from_marker_m[0],
                    r.tvec_camera_from_marker_m[1], r.tvec_camera_from_marker_m[2], i + 1 < n ? "," : "");
      json += buf;
    }
    check(n == static_cast<int>(truth.size()), "golden image decodes every marker", n);
    json += std::string("    ]}") + (g == 0 ? "," : "") + "\n";
  }
  json += "  ]\n}\n";
  FILE* f = std::fopen((out + "/marker_goldens.json").c_str(), "wb");
  check(f != nullptr, "golden JSON written");
  if (f != nullptr) {
    std::fwrite(json.data(), 1, json.size(), f);
    std::fclose(f);
  }
  c3md_destroy(d);
  std::printf("goldens written to %s\n", out.c_str());
}

}  // namespace

int main(int argc, char** argv) {
  std::string codesPath, sheets, stills, golden;
  for (int i = 1; i + 1 < argc; i += 2) {
    const std::string key = argv[i];
    if (key == "--codes") codesPath = argv[i + 1];
    if (key == "--sheets") sheets = argv[i + 1];
    if (key == "--stills") stills = argv[i + 1];
    if (key == "--write-golden") golden = argv[i + 1];
  }
  if (codesPath.empty() || sheets.empty()) {
    std::printf("usage: %s --codes <aruco_mip_36h12.json> --sheets <print folder> [--stills <bmp folder>] "
                "[--write-golden <dir>]\n", argv[0]);
    return 2;
  }
  const auto codes = readCodes(codesPath);
  check(codes.size() == 250, "250 codes read", static_cast<double>(codes.size()));
  if (codes.size() != 250) return 1;
  check(c3md_detector_available() == 1, "AprilTag build detects");
  C3MarkerDetector* d = c3md_create(C3MD_DICTIONARY_ARUCO_MIP_36H12);
  check(d != nullptr, "ARUCO_MIP_36h12 detector created");
  if (d == nullptr) return 1;
  const double t0 = nowMs();
  testPrintedSheets(sheets, codes, d);
  testRangeGrid(codes, d);
  testLowContrast(codes, d);
  testOtherDictionaries();
  testNoFalsePositives(d);
  testTimings(sheets, codes, d);
  testTiePoints(codes);
  testSolvePoints();
  if (!stills.empty()) testStills(stills);
  if (!golden.empty()) writeGoldens(sheets, golden);
  c3md_destroy(d);
  std::printf("accuracy test: %.1f s\n", (nowMs() - t0) / 1000);
  if (failures == 0) std::printf("marker detector accuracy test: all checks passed\n");
  return failures == 0 ? 0 : 1;
}
