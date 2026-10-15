// Geometry helpers of the marker detector (no detection backend): edge-line
// corner refinement, diagonal centre, edge sharpness, viewing angle, lens
// distortion, half floats and record encoding. Ported from the measured
// benchmark (benchmarks/visibility_grid, `aruco_range` suite).

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "marker_detector.h"
#include "marker_geometry.h"

namespace c3md {

namespace {

float bilinear(const uint8_t* grey, int width, int height, int stride, double x, double y) {
  x = std::min(std::max(x, 0.0), width - 1.001);
  y = std::min(std::max(y, 0.0), height - 1.001);
  const int x0 = static_cast<int>(x), y0 = static_cast<int>(y);
  const double fx = x - x0, fy = y - y0;
  const uint8_t* r0 = grey + static_cast<size_t>(y0) * stride;
  const uint8_t* r1 = r0 + stride;
  return static_cast<float>((r0[x0] * (1 - fx) + r0[x0 + 1] * fx) * (1 - fy) +
                            (r1[x0] * (1 - fx) + r1[x0 + 1] * fx) * fy);
}

struct Line {
  double px, py, ux, uy;  // point and unit direction
};

// Total-least-squares line with two rounds of robust trimming (3 sigma of the
// median absolute residual). `xs`, `ys` are reordered in place.
bool fitLine(std::vector<double>& xs, std::vector<double>& ys, std::vector<double>& scratch, Line& line) {
  size_t n = xs.size();
  for (int round = 0; round < 3; round++) {
    if (n < 3) return false;
    double mx = 0, my = 0;
    for (size_t i = 0; i < n; i++) {
      mx += xs[i];
      my += ys[i];
    }
    mx /= n;
    my /= n;
    double sxx = 0, sxy = 0, syy = 0;
    for (size_t i = 0; i < n; i++) {
      const double dx = xs[i] - mx, dy = ys[i] - my;
      sxx += dx * dx;
      sxy += dx * dy;
      syy += dy * dy;
    }
    const double angle = 0.5 * std::atan2(2 * sxy, sxx - syy);
    line = {mx, my, std::cos(angle), std::sin(angle)};
    if (round == 2) break;
    scratch.resize(n);
    for (size_t i = 0; i < n; i++) {
      scratch[i] = std::abs(-(xs[i] - mx) * line.uy + (ys[i] - my) * line.ux);
    }
    std::vector<double> sorted(scratch.begin(), scratch.begin() + n);
    std::nth_element(sorted.begin(), sorted.begin() + n / 2, sorted.end());
    const double gate = std::max(0.05, 3.0 * 1.4826 * sorted[n / 2]);
    size_t kept = 0;
    for (size_t i = 0; i < n; i++) {
      if (scratch[i] <= gate) {
        xs[kept] = xs[i];
        ys[kept] = ys[i];
        kept++;
      }
    }
    if (kept == n) break;
    n = kept;
    xs.resize(n);
    ys.resize(n);
  }
  return true;
}

bool intersect(const Line& a, const Line& b, double& x, double& y) {
  const double den = a.ux * b.uy - a.uy * b.ux;
  if (std::abs(den) < 1e-9) return false;
  const double t = ((b.px - a.px) * b.uy - (b.py - a.py) * b.ux) / den;
  x = a.px + t * a.ux;
  y = a.py + t * a.uy;
  return true;
}

}  // namespace

void distortNormalised(const double* d, double& x, double& y) {
  const double k1 = d[0], k2 = d[1], p1 = d[2], p2 = d[3], k3 = d[4];
  const double r2 = x * x + y * y;
  const double radial = 1 + r2 * (k1 + r2 * (k2 + r2 * k3));
  const double xd = x * radial + 2 * p1 * x * y + p2 * (r2 + 2 * x * x);
  const double yd = y * radial + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y;
  x = xd;
  y = yd;
}

void undistortNormalised(const double* d, double& x, double& y) {
  const double xd = x, yd = y;
  double xu = xd, yu = yd;
  for (int it = 0; it < 30; it++) {
    double xp = xu, yp = yu;
    distortNormalised(d, xp, yp);
    const double ex = xp - xd, ey = yp - yd;
    xu -= ex;
    yu -= ey;
    if (ex * ex + ey * ey < 1e-24) break;
  }
  x = xu;
  y = yu;
}

void pixelToPinhole(const Camera& c, double& x, double& y) {
  if (!c.distorted) return;
  double nx = (x - c.cx) / c.fx, ny = (y - c.cy) / c.fy;
  undistortNormalised(c.distortion, nx, ny);
  x = nx * c.fx + c.cx;
  y = ny * c.fy + c.cy;
}

void pinholeToPixel(const Camera& c, double& x, double& y) {
  if (!c.distorted) return;
  double nx = (x - c.cx) / c.fx, ny = (y - c.cy) / c.fy;
  distortNormalised(c.distortion, nx, ny);
  x = nx * c.fx + c.cx;
  y = ny * c.fy + c.cy;
}

bool refineEdges(const uint8_t* grey, int width, int height, int stride, const float* coarse, int modules,
                 const Camera& camera, EdgeScratch& s, double* pinhole8) {
  double side = 0;
  for (int k = 0; k < 4; k++) {
    const int n = (k + 1) % 4;
    side += std::hypot(coarse[2 * n] - coarse[2 * k], coarse[2 * n + 1] - coarse[2 * k + 1]) / 4;
  }
  const double module = side / std::max(1, modules);
  const double range = std::max(1.5, std::min(8.0, 0.45 * module));
  const int steps = static_cast<int>(range / 0.25);
  const int m = 2 * steps + 1;
  s.profile.resize(m);
  Line lines[4];
  for (int e = 0; e < 4; e++) {
    const double ax = coarse[2 * e], ay = coarse[2 * e + 1];
    const double dx = coarse[2 * ((e + 1) % 4)] - ax, dy = coarse[2 * ((e + 1) % 4) + 1] - ay;
    const double len = std::hypot(dx, dy);
    if (len < 6) return false;
    const double nx = -dy / len, ny = dx / len;
    const int n = std::max(6, std::min(120, static_cast<int>(len / 1.5)));
    s.xs.clear();
    s.ys.clear();
    for (int i = 0; i < n; i++) {
      const double t = 0.12 + 0.76 * i / (n - 1);
      const double px = ax + dx * t, py = ay + dy * t;
      float lo = 255, hi = 0;
      for (int k = 0; k < m; k++) {
        const double o = (k - steps) * 0.25;
        const float v = bilinear(grey, width, height, stride, px + o * nx, py + o * ny);
        s.profile[k] = v;
        lo = std::min(lo, v);
        hi = std::max(hi, v);
      }
      if (hi - lo < 20) continue;
      int best = -1;
      double bestG = 0;
      for (int k = 1; k + 1 < m; k++) {
        const double g = std::abs(s.profile[k + 1] - s.profile[k - 1]);
        if (g > bestG) {
          bestG = g;
          best = k;
        }
      }
      if (best < 2 || best > m - 3) continue;  // step at the search limit
      const double g0 = std::abs(s.profile[best] - s.profile[best - 2]), g1 = bestG,
                   g2 = std::abs(s.profile[best + 2] - s.profile[best]);
      const double den = g0 - 2 * g1 + g2;
      const double off = std::abs(den) < 1e-9 ? 0 : 0.5 * (g0 - g2) / den;
      const double o = (best - steps + std::max(-1.0, std::min(1.0, off))) * 0.25;
      double x = px + o * nx, y = py + o * ny;
      pixelToPinhole(camera, x, y);
      s.xs.push_back(x);
      s.ys.push_back(y);
    }
    if (!fitLine(s.xs, s.ys, s.residuals, lines[e])) return false;
  }
  for (int c = 0; c < 4; c++) {
    if (!intersect(lines[(c + 3) % 4], lines[c], pinhole8[2 * c], pinhole8[2 * c + 1])) return false;
  }
  return true;
}

void diagonalCentre(const double* c, double& x, double& y) {
  const double d1x = c[4] - c[0], d1y = c[5] - c[1];
  const double d2x = c[6] - c[2], d2y = c[7] - c[3];
  const double w0x = c[2] - c[0], w0y = c[3] - c[1];
  const double den = d1x * d2y - d1y * d2x;
  const double u = std::abs(den) < 1e-12 ? 0.5 : (w0x * d2y - w0y * d2x) / den;
  x = c[0] + u * d1x;
  y = c[1] + u * d1y;
}

double edgeSharpness(const uint8_t* grey, int width, int height, int stride, const float* c) {
  double sum = 0;
  int count = 0;
  for (int e = 0; e < 4; e++) {
    const double ax = c[2 * e], ay = c[2 * e + 1];
    const double dx = c[2 * ((e + 1) % 4)] - ax, dy = c[2 * ((e + 1) % 4) + 1] - ay;
    const double len = std::hypot(dx, dy);
    if (len < 4) continue;
    const double nx = -dy / len, ny = dx / len;
    for (const double t : {0.3, 0.5, 0.7}) {
      const double px = ax + dx * t, py = ay + dy * t;
      float lo = 255, hi = 0, steep = 0;
      float prev = bilinear(grey, width, height, stride, px - 4 * nx, py - 4 * ny);
      for (int k = -7; k <= 8; k++) {
        const float v = bilinear(grey, width, height, stride, px + 0.5 * k * nx, py + 0.5 * k * ny);
        lo = std::min(lo, v);
        hi = std::max(hi, v);
        steep = std::max(steep, std::abs(v - prev) / 0.5f);
        prev = v;
      }
      if (hi - lo > 10) {
        sum += steep / (hi - lo);
        count++;
      }
    }
  }
  return count == 0 ? 0 : sum / count;
}

void rodrigues(const double* r, double* R) {
  const double a = std::sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
  if (a < 1e-12) {
    const double id[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    std::memcpy(R, id, sizeof id);
    return;
  }
  const double x = r[0] / a, y = r[1] / a, z = r[2] / a;
  const double c = std::cos(a), s = std::sin(a), t = 1 - c;
  R[0] = t * x * x + c;
  R[1] = t * x * y - s * z;
  R[2] = t * x * z + s * y;
  R[3] = t * x * y + s * z;
  R[4] = t * y * y + c;
  R[5] = t * y * z - s * x;
  R[6] = t * x * z - s * y;
  R[7] = t * y * z + s * x;
  R[8] = t * z * z + c;
}

double viewingAngleDeg(const double* rvec, const double* tvec) {
  double R[9];
  rodrigues(rvec, R);
  const double tl = std::sqrt(tvec[0] * tvec[0] + tvec[1] * tvec[1] + tvec[2] * tvec[2]);
  if (tl < 1e-12) return 0;
  // Marker normal (third column) against the direction marker -> camera.
  const double cosA = -(R[2] * tvec[0] + R[5] * tvec[1] + R[8] * tvec[2]) / tl;
  return std::acos(std::max(-1.0, std::min(1.0, cosA))) * 180.0 / kPi;
}

uint16_t f16FromFloat(float value) {
  uint32_t f;
  std::memcpy(&f, &value, 4);
  const uint32_t sign = (f >> 16) & 0x8000u;
  const int32_t exponent = static_cast<int32_t>((f >> 23) & 0xff);
  uint32_t mantissa = f & 0x7fffffu;
  if (exponent == 0xff) return static_cast<uint16_t>(sign | 0x7c00u | (mantissa ? 0x200u : 0));
  int32_t e = exponent - 127 + 15;
  if (e >= 0x1f) return static_cast<uint16_t>(sign | 0x7c00u);
  if (e <= 0) {
    if (e < -10) return static_cast<uint16_t>(sign);
    mantissa |= 0x800000u;
    const int shift = 14 - e;
    uint32_t half = mantissa >> shift;
    const uint32_t rest = mantissa & ((1u << shift) - 1);
    const uint32_t halfway = 1u << (shift - 1);
    if (rest > halfway || (rest == halfway && (half & 1u))) half++;
    return static_cast<uint16_t>(sign | half);
  }
  uint32_t half = (static_cast<uint32_t>(e) << 10) | (mantissa >> 13);
  const uint32_t rest = mantissa & 0x1fffu;
  if (rest > 0x1000u || (rest == 0x1000u && (half & 1u))) half++;  // may carry into the exponent
  return static_cast<uint16_t>(sign | half);
}

float f16ToFloat(uint16_t h) {
  const uint32_t sign = (h & 0x8000u) << 16;
  const uint32_t exponent = (h >> 10) & 0x1fu;
  const uint32_t mantissa = h & 0x3ffu;
  float value;
  if (exponent == 0) {
    value = std::ldexp(static_cast<float>(mantissa), -24);
  } else if (exponent == 0x1f) {
    value = mantissa ? NAN : INFINITY;
  } else {
    value = std::ldexp(static_cast<float>(mantissa | 0x400u), static_cast<int>(exponent) - 25);
  }
  uint32_t bits;
  std::memcpy(&bits, &value, 4);
  bits |= sign;
  std::memcpy(&value, &bits, 4);
  return value;
}

void encodeRecord(uint16_t markerId, uint8_t dictionary, uint8_t flags, const float* corners8, const double* rvec3,
                  const double* tvec3, float reprojectionErrorPx, float viewingAngleDeg, float sharpness,
                  C3MarkerDetectionRecord& out) {
  std::memset(&out, 0, sizeof out);
  out.marker_id = markerId;
  out.dictionary = dictionary;
  out.flags = flags;
  for (int k = 0; k < 8; k++) out.corners_px[k] = corners8[k];
  for (int k = 0; k < 3; k++) {
    out.rvec_camera_from_marker[k] = static_cast<float>(rvec3[k]);
    out.tvec_camera_from_marker_m[k] = static_cast<float>(tvec3[k]);
  }
  out.reprojection_error_px_f16 = f16FromFloat(reprojectionErrorPx);
  const float angle = std::isfinite(viewingAngleDeg) ? viewingAngleDeg : 255.0f;
  out.viewing_angle_deg = static_cast<uint8_t>(std::lround(std::max(0.0f, std::min(255.0f, angle))));
  const float s = std::isfinite(sharpness) ? sharpness * 255.0f : 0.0f;
  out.sharpness = static_cast<uint8_t>(std::lround(std::max(0.0f, std::min(255.0f, s))));
}

}  // namespace c3md

// ---- C ABI of the helpers ------------------------------------------------

extern "C" {

C3MD_API int32_t c3md_refine_edges(const uint8_t* grey, int32_t width, int32_t height, int32_t stride,
                                   const float* coarse8, int32_t modules, const double* camera4,
                                   const double* distortion5, float* out8) {
  if (grey == nullptr || coarse8 == nullptr || out8 == nullptr || width < 2 || height < 2 || stride < width) {
    return C3MD_ERROR_ARGUMENT;
  }
  c3md::Camera camera;
  if (camera4 != nullptr) camera.set(camera4[0], camera4[1], camera4[2], camera4[3], distortion5);
  c3md::EdgeScratch scratch;
  double pinhole[8];
  if (!c3md::refineEdges(grey, width, height, stride, coarse8, modules, camera, scratch, pinhole)) {
    return C3MD_ERROR_ARGUMENT;
  }
  for (int c = 0; c < 4; c++) {
    double x = pinhole[2 * c], y = pinhole[2 * c + 1];
    c3md::pinholeToPixel(camera, x, y);
    out8[2 * c] = static_cast<float>(x);
    out8[2 * c + 1] = static_cast<float>(y);
  }
  return C3MD_OK;
}

C3MD_API int32_t c3md_diagonal_centre(const float* corners8, float* out2) {
  if (corners8 == nullptr || out2 == nullptr) return C3MD_ERROR_ARGUMENT;
  double c[8];
  for (int k = 0; k < 8; k++) c[k] = corners8[k];
  double x, y;
  c3md::diagonalCentre(c, x, y);
  out2[0] = static_cast<float>(x);
  out2[1] = static_cast<float>(y);
  return C3MD_OK;
}

C3MD_API float c3md_edge_sharpness(const uint8_t* grey, int32_t width, int32_t height, int32_t stride,
                                   const float* corners8) {
  if (grey == nullptr || corners8 == nullptr) return 0;
  return static_cast<float>(c3md::edgeSharpness(grey, width, height, stride, corners8));
}

C3MD_API float c3md_viewing_angle_deg(const double* rvec3, const double* tvec3) {
  return static_cast<float>(c3md::viewingAngleDeg(rvec3, tvec3));
}

C3MD_API uint16_t c3md_f16_from_float(float value) { return c3md::f16FromFloat(value); }
C3MD_API float c3md_f16_to_float(uint16_t bits) { return c3md::f16ToFloat(bits); }

C3MD_API void c3md_distort_normalised(const double* distortion5, double* x, double* y) {
  c3md::distortNormalised(distortion5, *x, *y);
}

C3MD_API void c3md_undistort_normalised(const double* distortion5, double* x, double* y) {
  c3md::undistortNormalised(distortion5, *x, *y);
}

C3MD_API void c3md_encode_record(uint16_t marker_id, uint8_t dictionary, uint8_t flags, const float* corners8,
                                 const double* rvec3, const double* tvec3, float reprojection_error_px,
                                 float viewing_angle_deg, float sharpness, C3MarkerDetectionRecord* out) {
  c3md::encodeRecord(marker_id, dictionary, flags, corners8, rvec3, tvec3, reprojection_error_px, viewing_angle_deg,
                     sharpness, *out);
}

}  // extern "C"
