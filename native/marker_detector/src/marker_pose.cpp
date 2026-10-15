// Perspective-n-point of the marker detector (see marker_pose.h).
// Everything works on fixed-size stack buffers; nothing is allocated.

#include "marker_pose.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace c3md {

namespace {

void mul3(const double* A, const double* B, double* C) {
  double out[9];
  for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) {
      out[3 * i + j] = A[3 * i] * B[j] + A[3 * i + 1] * B[3 + j] + A[3 * i + 2] * B[6 + j];
    }
  }
  std::memcpy(C, out, sizeof out);
}

void cross(const double* a, const double* b, double* out) {
  out[0] = a[1] * b[2] - a[2] * b[1];
  out[1] = a[2] * b[0] - a[0] * b[2];
  out[2] = a[0] * b[1] - a[1] * b[0];
}

double dot3(const double* a, const double* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

/// Cyclic Jacobi eigen-decomposition of the symmetric n x n matrix `A`
/// (row-major, destroyed). Eigenvalues in `values`, eigenvectors as the
/// columns of `V` (row-major n x n).
void jacobiEigen(double* A, int n, double* values, double* V) {
  for (int i = 0; i < n; i++) {
    for (int j = 0; j < n; j++) V[i * n + j] = i == j ? 1 : 0;
  }
  for (int sweep = 0; sweep < 60; sweep++) {
    double off = 0, diag = 0;
    for (int i = 0; i < n; i++) {
      diag += A[i * n + i] * A[i * n + i];
      for (int j = i + 1; j < n; j++) off += A[i * n + j] * A[i * n + j];
    }
    if (off <= 1e-30 * std::max(diag, 1e-300)) break;
    for (int p = 0; p < n; p++) {
      for (int q = p + 1; q < n; q++) {
        const double apq = A[p * n + q];
        if (std::abs(apq) < 1e-300) continue;
        const double theta = (A[q * n + q] - A[p * n + p]) / (2 * apq);
        const double t = (theta >= 0 ? 1 : -1) / (std::abs(theta) + std::sqrt(theta * theta + 1));
        const double c = 1 / std::sqrt(t * t + 1), s = t * c;
        for (int k = 0; k < n; k++) {
          const double akp = A[k * n + p], akq = A[k * n + q];
          A[k * n + p] = c * akp - s * akq;
          A[k * n + q] = s * akp + c * akq;
        }
        for (int k = 0; k < n; k++) {
          const double apk = A[p * n + k], aqk = A[q * n + k];
          A[p * n + k] = c * apk - s * aqk;
          A[q * n + k] = s * apk + c * aqk;
        }
        for (int k = 0; k < n; k++) {
          const double vkp = V[k * n + p], vkq = V[k * n + q];
          V[k * n + p] = c * vkp - s * vkq;
          V[k * n + q] = s * vkp + c * vkq;
        }
      }
    }
  }
  for (int i = 0; i < n; i++) values[i] = A[i * n + i];
}

/// Eigenvector of the smallest eigenvalue of the symmetric n x n `A`.
void smallestEigenvector(double* A, int n, double* out) {
  double values[12], V[144];
  jacobiEigen(A, n, values, V);
  int best = 0;
  for (int i = 1; i < n; i++) {
    if (values[i] < values[best]) best = i;
  }
  for (int i = 0; i < n; i++) out[i] = V[i * n + best];
}

/// Solves the n x n system A x = b in place (partial pivoting); false when singular.
bool solveLinear(double* A, double* b, int n) {
  for (int c = 0; c < n; c++) {
    int pivot = c;
    for (int r = c + 1; r < n; r++) {
      if (std::abs(A[r * n + c]) > std::abs(A[pivot * n + c])) pivot = r;
    }
    if (std::abs(A[pivot * n + c]) < 1e-300) return false;
    if (pivot != c) {
      for (int k = 0; k < n; k++) std::swap(A[c * n + k], A[pivot * n + k]);
      std::swap(b[c], b[pivot]);
    }
    for (int r = c + 1; r < n; r++) {
      const double f = A[r * n + c] / A[c * n + c];
      for (int k = c; k < n; k++) A[r * n + k] -= f * A[c * n + k];
      b[r] -= f * b[c];
    }
  }
  for (int r = n - 1; r >= 0; r--) {
    double s = b[r];
    for (int k = r + 1; k < n; k++) s -= A[r * n + k] * b[k];
    b[r] = s / A[r * n + r];
  }
  return true;
}

/// Nearest rotation (polar factor) of the 3 x 3 `M`; false for a reflection.
bool nearestRotation(const double* M, double* R) {
  double MtM[9];
  for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) MtM[3 * i + j] = M[i] * M[j] + M[3 + i] * M[3 + j] + M[6 + i] * M[6 + j];
  }
  double values[3], V[9];
  jacobiEigen(MtM, 3, values, V);
  double inv[9] = {};
  for (int k = 0; k < 3; k++) {
    if (!(values[k] > 1e-300)) return false;
    const double s = 1 / std::sqrt(values[k]);
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) inv[3 * i + j] += s * V[3 * i + k] * V[3 * j + k];
    }
  }
  mul3(M, inv, R);
  const double det = R[0] * (R[4] * R[8] - R[5] * R[7]) - R[1] * (R[3] * R[8] - R[5] * R[6]) +
                     R[2] * (R[3] * R[7] - R[4] * R[6]);
  return det > 0;
}

/// Hartley normalisation of 2-D points: centroid to the origin, mean distance sqrt 2.
void normaliser2(int n, const double* xy, double* T) {
  double mx = 0, my = 0;
  for (int i = 0; i < n; i++) {
    mx += xy[2 * i];
    my += xy[2 * i + 1];
  }
  mx /= n;
  my /= n;
  double d = 0;
  for (int i = 0; i < n; i++) d += std::hypot(xy[2 * i] - mx, xy[2 * i + 1] - my);
  d /= n;
  const double s = d > 1e-300 ? std::sqrt(2.0) / d : 1;
  const double t[9] = {s, 0, -s * mx, 0, s, -s * my, 0, 0, 1};
  std::memcpy(T, t, sizeof t);
}

/// Translation of rotation `R` that best maps `n` object points onto the
/// normalised image rays (linear least squares).
bool translationFor(const double* R, int n, const double* objectXyz, const double* rays, double* t) {
  double A[9] = {}, b[3] = {};
  for (int i = 0; i < n; i++) {
    double P[3];
    for (int r = 0; r < 3; r++) P[r] = dot3(R + 3 * r, objectXyz + 3 * i);
    const double u = rays[2 * i], v = rays[2 * i + 1];
    // rows (1, 0, -u) t = u Pz - Px and (0, 1, -v) t = v Pz - Py
    const double rows[2][4] = {{1, 0, -u, u * P[2] - P[0]}, {0, 1, -v, v * P[2] - P[1]}};
    for (const auto& row : rows) {
      for (int a = 0; a < 3; a++) {
        for (int c = 0; c < 3; c++) A[3 * a + c] += row[a] * row[c];
        b[a] += row[a] * row[3];
      }
    }
  }
  if (!solveLinear(A, b, 3)) return false;
  std::memcpy(t, b, sizeof b);
  return true;
}

double squaredError(const Camera& camera, int n, const double* objectXyz, const double* imageXy, const double* R,
                    const double* t) {
  double sq = 0;
  for (int i = 0; i < n; i++) {
    double u, v;
    projectPoint(camera, R, t, objectXyz + 3 * i, u, v);
    const double du = u - imageXy[2 * i], dv = v - imageXy[2 * i + 1];
    sq += du * du + dv * dv;
  }
  return sq;
}

/// Levenberg-Marquardt on the pixel reprojection error (forward-difference
/// Jacobian, accumulated without storing it).
void refineLm(const Camera& camera, int n, const double* objectXyz, const double* imageXy, double* rvec,
              double* tvec) {
  double x[6] = {rvec[0], rvec[1], rvec[2], tvec[0], tvec[1], tvec[2]};
  double R[9];
  rodrigues(x, R);
  double cost = squaredError(camera, n, objectXyz, imageXy, R, x + 3);
  double lambda = 1e-3;
  for (int it = 0; it < 40 && cost > 1e-24; it++) {
    double Rp[3][9];
    const double eps = 1e-7;
    for (int k = 0; k < 3; k++) {
      double r[3] = {x[0], x[1], x[2]};
      r[k] += eps;
      rodrigues(r, Rp[k]);
    }
    double JtJ[36] = {}, Jtr[6] = {};
    for (int i = 0; i < n; i++) {
      const double* X = objectXyz + 3 * i;
      double u0, v0;
      projectPoint(camera, R, x + 3, X, u0, v0);
      const double ru = u0 - imageXy[2 * i], rv = v0 - imageXy[2 * i + 1];
      double Ju[6], Jv[6];
      for (int k = 0; k < 6; k++) {
        double u, v;
        if (k < 3) {
          projectPoint(camera, Rp[k], x + 3, X, u, v);
        } else {
          double t[3] = {x[3], x[4], x[5]};
          t[k - 3] += eps;
          projectPoint(camera, R, t, X, u, v);
        }
        Ju[k] = (u - u0) / eps;
        Jv[k] = (v - v0) / eps;
      }
      for (int a = 0; a < 6; a++) {
        for (int b = 0; b < 6; b++) JtJ[6 * a + b] += Ju[a] * Ju[b] + Jv[a] * Jv[b];
        Jtr[a] += Ju[a] * ru + Jv[a] * rv;
      }
    }
    bool improved = false;
    while (lambda < 1e10) {
      double A[36], step[6];
      std::memcpy(A, JtJ, sizeof A);
      for (int a = 0; a < 6; a++) {
        A[7 * a] += lambda * std::max(JtJ[7 * a], 1e-12);
        step[a] = -Jtr[a];
      }
      if (!solveLinear(A, step, 6)) {
        lambda *= 10;
        continue;
      }
      double y[6];
      for (int a = 0; a < 6; a++) y[a] = x[a] + step[a];
      double Ry[9];
      rodrigues(y, Ry);
      const double c = squaredError(camera, n, objectXyz, imageXy, Ry, y + 3);
      if (c < cost) {
        const double gain = cost - c;
        std::memcpy(x, y, sizeof x);
        std::memcpy(R, Ry, sizeof R);
        cost = c;
        lambda = std::max(lambda / 10, 1e-12);
        improved = gain > 1e-14 * std::max(cost, 1e-300) && gain > 1e-24;
        break;
      }
      lambda *= 10;
    }
    if (!improved) break;
  }
  std::memcpy(rvec, x, 3 * sizeof(double));
  std::memcpy(tvec, x + 3, 3 * sizeof(double));
}

/// The two IPPE rotations of a plane whose homography (model plane z = 0,
/// centred at the origin, to normalised image coordinates, H[8] = 1) is `H`.
bool ippeRotations(const double* H, double* R1, double* R2) {
  const double p = H[2], q = H[5];
  const double J00 = H[0] - H[6] * p, J01 = H[1] - H[7] * p;
  const double J10 = H[3] - H[6] * q, J11 = H[4] - H[7] * q;
  // Rv rotates the optical axis onto the ray of the plane origin.
  const double s = std::sqrt(p * p + q * q + 1);
  const double vn[3] = {p / s, q / s, 1 / s};
  double Rv[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  const double sinT = std::sqrt(vn[0] * vn[0] + vn[1] * vn[1]);
  if (sinT > 1e-12) {
    const double axis[3] = {-vn[1] / sinT * std::asin(std::min(1.0, sinT)), vn[0] / sinT * std::asin(std::min(1.0, sinT)),
                            0};
    rodrigues(axis, Rv);
  }
  // B = [I2 | -(p, q)] Rv[:, 0:2]
  const double B00 = Rv[0] - p * Rv[6], B01 = Rv[1] - p * Rv[7];
  const double B10 = Rv[3] - q * Rv[6], B11 = Rv[4] - q * Rv[7];
  const double det = B00 * B11 - B01 * B10;
  if (std::abs(det) < 1e-300) return false;
  const double Bi00 = B11 / det, Bi01 = -B01 / det, Bi10 = -B10 / det, Bi11 = B00 / det;
  const double a00 = Bi00 * J00 + Bi01 * J10, a01 = Bi00 * J01 + Bi01 * J11;
  const double a10 = Bi10 * J00 + Bi11 * J10, a11 = Bi10 * J01 + Bi11 * J11;
  const double ata00 = a00 * a00 + a10 * a10, ata01 = a00 * a01 + a10 * a11, ata11 = a01 * a01 + a11 * a11;
  const double gamma =
      std::sqrt(0.5 * (ata00 + ata11 + std::sqrt((ata00 - ata11) * (ata00 - ata11) + 4 * ata01 * ata01)));
  if (!(gamma > 1e-300)) return false;
  const double r00 = a00 / gamma, r01 = a01 / gamma, r10 = a10 / gamma, r11 = a11 / gamma;
  const double b0 = std::sqrt(std::max(0.0, 1 - r00 * r00 - r10 * r10));
  double b1 = std::sqrt(std::max(0.0, 1 - r01 * r01 - r11 * r11));
  if (-r00 * r01 - r10 * r11 < 0) b1 = -b1;
  for (int sign = 0; sign < 2; sign++) {
    const double sb = sign == 0 ? 1 : -1;
    const double c0[3] = {r00, r10, sb * b0}, c1[3] = {r01, r11, sb * b1};
    double c2[3];
    cross(c0, c1, c2);
    const double Rt[9] = {c0[0], c1[0], c2[0], c0[1], c1[1], c2[1], c0[2], c1[2], c2[2]};
    mul3(Rv, Rt, sign == 0 ? R1 : R2);
  }
  return true;
}

/// Homography from centred plane coordinates to normalised image points (DLT).
bool planeHomography(int n, const double* plane, const double* rays, double* H) {
  double Tm[9], Ti[9];
  normaliser2(n, plane, Tm);
  normaliser2(n, rays, Ti);
  double A[81] = {};
  for (int i = 0; i < n; i++) {
    const double x = Tm[0] * plane[2 * i] + Tm[2], y = Tm[4] * plane[2 * i + 1] + Tm[5];
    const double u = Ti[0] * rays[2 * i] + Ti[2], v = Ti[4] * rays[2 * i + 1] + Ti[5];
    const double rows[2][9] = {{x, y, 1, 0, 0, 0, -u * x, -u * y, -u}, {0, 0, 0, x, y, 1, -v * x, -v * y, -v}};
    for (const auto& row : rows) {
      for (int a = 0; a < 9; a++) {
        for (int b = 0; b < 9; b++) A[9 * a + b] += row[a] * row[b];
      }
    }
  }
  double h[9];
  smallestEigenvector(A, 9, h);
  // H = Ti^-1 Hn Tm
  const double Tii[9] = {1 / Ti[0], 0, -Ti[2] / Ti[0], 0, 1 / Ti[4], -Ti[5] / Ti[4], 0, 0, 1};
  double tmp[9];
  mul3(Tii, h, tmp);
  mul3(tmp, Tm, H);
  if (std::abs(H[8]) < 1e-300) return false;
  const double s = 1 / H[8];
  for (int k = 0; k < 9; k++) H[k] *= s;
  return true;
}

/// Pose of non-planar points from the normalised DLT projection matrix.
bool dltPose(int n, const double* objectXyz, const double* rays, double* R, double* t) {
  double c[3] = {}, d = 0;
  for (int i = 0; i < n; i++) {
    for (int k = 0; k < 3; k++) c[k] += objectXyz[3 * i + k] / n;
  }
  for (int i = 0; i < n; i++) {
    d += std::sqrt((objectXyz[3 * i] - c[0]) * (objectXyz[3 * i] - c[0]) +
                   (objectXyz[3 * i + 1] - c[1]) * (objectXyz[3 * i + 1] - c[1]) +
                   (objectXyz[3 * i + 2] - c[2]) * (objectXyz[3 * i + 2] - c[2]));
  }
  d /= n;
  const double so = d > 1e-300 ? std::sqrt(3.0) / d : 1;
  double Ti[9];
  normaliser2(n, rays, Ti);
  double A[144] = {};
  for (int i = 0; i < n; i++) {
    const double X = so * (objectXyz[3 * i] - c[0]), Y = so * (objectXyz[3 * i + 1] - c[1]),
                 Z = so * (objectXyz[3 * i + 2] - c[2]);
    const double u = Ti[0] * rays[2 * i] + Ti[2], v = Ti[4] * rays[2 * i + 1] + Ti[5];
    const double rows[2][12] = {{X, Y, Z, 1, 0, 0, 0, 0, -u * X, -u * Y, -u * Z, -u},
                                {0, 0, 0, 0, X, Y, Z, 1, -v * X, -v * Y, -v * Z, -v}};
    for (const auto& row : rows) {
      for (int a = 0; a < 12; a++) {
        for (int b = 0; b < 12; b++) A[12 * a + b] += row[a] * row[b];
      }
    }
  }
  double p[12];
  smallestEigenvector(A, 12, p);
  // Undo the image normalisation: P = Ti^-1 Pn (rows), then the object one.
  double P[12];
  const double ix = 1 / Ti[0], iy = 1 / Ti[4];
  for (int k = 0; k < 4; k++) {
    P[k] = ix * (p[k] - Ti[2] * p[8 + k]);
    P[4 + k] = iy * (p[4 + k] - Ti[5] * p[8 + k]);
    P[8 + k] = p[8 + k];
  }
  // Object normalisation: X' = so (X - c), so P X' = [so M | p4 - so M c] X.
  double M[9], p4[3];
  for (int r = 0; r < 3; r++) {
    for (int k = 0; k < 3; k++) M[3 * r + k] = so * P[4 * r + k];
    p4[r] = P[4 * r + 3] - dot3(M + 3 * r, c);
  }
  // Positive depth at the centroid.
  if (dot3(M + 6, c) + p4[2] < 0) {
    for (auto& m : M) m = -m;
    for (auto& q : p4) q = -q;
  }
  const double lambda = std::sqrt(dot3(M + 6, M + 6));
  if (!(lambda > 1e-300)) return false;
  for (auto& m : M) m /= lambda;
  if (!nearestRotation(M, R)) return false;
  for (int k = 0; k < 3; k++) t[k] = p4[k] / lambda;
  return true;
}

}  // namespace

void projectPoint(const Camera& camera, const double* R, const double* t, const double* X, double& u, double& v) {
  const double x = R[0] * X[0] + R[1] * X[1] + R[2] * X[2] + t[0];
  const double y = R[3] * X[0] + R[4] * X[1] + R[5] * X[2] + t[1];
  const double z = R[6] * X[0] + R[7] * X[1] + R[8] * X[2] + t[2];
  double nx = x / z, ny = y / z;
  if (camera.distorted) distortNormalised(camera.distortion, nx, ny);
  u = camera.fx * nx + camera.cx;
  v = camera.fy * ny + camera.cy;
}

void rvecFromRotation(const double* R, double* rvec) {
  const double c = std::max(-1.0, std::min(1.0, (R[0] + R[4] + R[8] - 1) / 2));
  const double angle = std::acos(c);
  const double w[3] = {R[7] - R[5], R[2] - R[6], R[3] - R[1]};
  const double s = std::sqrt(dot3(w, w)) / 2;
  if (s > 1e-6) {
    const double k = angle / (2 * s);
    for (int i = 0; i < 3; i++) rvec[i] = w[i] * k;
    return;
  }
  if (c > 0) {  // near identity
    for (int i = 0; i < 3; i++) rvec[i] = w[i] / 2;
    return;
  }
  // Near pi: axis from the diagonal of (R + I) / 2.
  double axis[3] = {std::sqrt(std::max(0.0, (R[0] + 1) / 2)), std::sqrt(std::max(0.0, (R[4] + 1) / 2)),
                    std::sqrt(std::max(0.0, (R[8] + 1) / 2))};
  if (R[1] + R[3] < 0) axis[1] = -axis[1];
  if (R[2] + R[6] < 0) axis[2] = -axis[2];
  if (axis[0] < 1e-6) {
    axis[0] = 0;
    if (R[5] + R[7] < 0) axis[2] = -std::abs(axis[2]);
  }
  const double norm = std::sqrt(dot3(axis, axis));
  for (int i = 0; i < 3; i++) rvec[i] = axis[i] / std::max(norm, 1e-300) * angle;
}

double reprojectionRms(const Camera& camera, int n, const double* objectXyz, const double* imageXy, const double* rvec,
                       const double* tvec) {
  if (n <= 0) return NAN;
  double R[9];
  rodrigues(rvec, R);
  return std::sqrt(squaredError(camera, n, objectXyz, imageXy, R, tvec) / n);
}

bool solvePose(const Camera& camera, int n, const double* objectXyz, const double* imageXy, double* rvec,
               double* tvec, double& rms) {
  if (n < 4 || n > kMaxPosePoints) return false;
  double rays[2 * kMaxPosePoints];
  for (int i = 0; i < n; i++) {
    double x = (imageXy[2 * i] - camera.cx) / camera.fx, y = (imageXy[2 * i + 1] - camera.cy) / camera.fy;
    if (camera.distorted) undistortNormalised(camera.distortion, x, y);
    rays[2 * i] = x;
    rays[2 * i + 1] = y;
  }
  // Plane of the object points (principal axes).
  double c[3] = {};
  for (int i = 0; i < n; i++) {
    for (int k = 0; k < 3; k++) c[k] += objectXyz[3 * i + k] / n;
  }
  double cov[9] = {};
  for (int i = 0; i < n; i++) {
    const double d[3] = {objectXyz[3 * i] - c[0], objectXyz[3 * i + 1] - c[1], objectXyz[3 * i + 2] - c[2]};
    for (int a = 0; a < 3; a++) {
      for (int b = 0; b < 3; b++) cov[3 * a + b] += d[a] * d[b];
    }
  }
  double values[3], V[9];
  jacobiEigen(cov, 3, values, V);
  int order[3] = {0, 1, 2};
  std::sort(order, order + 3, [&](int a, int b) { return values[a] > values[b]; });
  const bool planar = values[order[2]] <= 1e-10 * std::max(values[order[0]], 1e-300);

  double best = INFINITY;
  double candidates[2][9], translations[2][3];
  int count = 0;
  if (planar) {
    double e1[3] = {V[order[0]], V[3 + order[0]], V[6 + order[0]]};
    double e2[3] = {V[order[1]], V[3 + order[1]], V[6 + order[1]]};
    double e3[3];
    cross(e1, e2, e3);
    double plane[2 * kMaxPosePoints];
    for (int i = 0; i < n; i++) {
      const double d[3] = {objectXyz[3 * i] - c[0], objectXyz[3 * i + 1] - c[1], objectXyz[3 * i + 2] - c[2]};
      plane[2 * i] = dot3(d, e1);
      plane[2 * i + 1] = dot3(d, e2);
    }
    double H[9], Rp[2][9];
    if (!planeHomography(n, plane, rays, H) || !ippeRotations(H, Rp[0], Rp[1])) return false;
    // Object frame: X_plane = E^T (X - c), so R = Rp E^T and t = tp - R c.
    const double Et[9] = {e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], e3[0], e3[1], e3[2]};
    for (int s = 0; s < 2; s++) {
      double R[9];
      mul3(Rp[s], Et, R);
      double t[3];
      if (!translationFor(R, n, objectXyz, rays, t)) continue;
      std::memcpy(candidates[count], R, sizeof R);
      std::memcpy(translations[count], t, sizeof t);
      count++;
    }
  } else {
    if (n < 6) return false;
    if (dltPose(n, objectXyz, rays, candidates[0], translations[0])) count = 1;
  }
  for (int s = 0; s < count; s++) {
    double r[3], t[3];
    rvecFromRotation(candidates[s], r);
    std::memcpy(t, translations[s], sizeof t);
    // Points in front of the camera.
    double R[9];
    rodrigues(r, R);
    if (dot3(R + 6, c) + t[2] <= 0) continue;
    refineLm(camera, n, objectXyz, imageXy, r, t);
    const double e = reprojectionRms(camera, n, objectXyz, imageXy, r, t);
    if (e < best) {
      best = e;
      std::memcpy(rvec, r, sizeof r);
      std::memcpy(tvec, t, sizeof t);
    }
  }
  if (!std::isfinite(best)) return false;
  rms = best;
  return true;
}

bool solveSquarePose(const Camera& camera, double halfSide, const float* corners8, double* rvec, double* tvec,
                     double& rms) {
  const double h = halfSide;
  const double object[12] = {-h, h, 0, h, h, 0, h, -h, 0, -h, -h, 0};
  double image[8];
  for (int k = 0; k < 8; k++) image[k] = corners8[k];
  return solvePose(camera, 4, object, image, rvec, tvec, rms);
}

}  // namespace c3md
