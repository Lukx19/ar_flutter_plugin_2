// Rendering support of the marker accuracy test: printed sheet parsing
// (SVG pages of benchmarks/marker_detection/print), the ARUCO_MIP_36h12 code
// table, plane-to-image rendering with supersampling, blur and noise, and
// small image file readers and writers (BMP in, PNG out with zlib).
#ifndef CAPTURE3D_MARKER_TEST_RENDER_H
#define CAPTURE3D_MARKER_TEST_RENDER_H

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace marker_test {

constexpr double kPi = 3.14159265358979323846;

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

inline bool readFile(const std::string& path, std::string& out) {
  FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) return false;
  char buf[65536];
  size_t n;
  out.clear();
  while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) out.append(buf, n);
  std::fclose(f);
  return true;
}

/// The 250 row-major 36-bit codes of aruco_mip_36h12.json (1 is white).
inline std::vector<uint64_t> readCodes(const std::string& jsonPath) {
  std::string text;
  std::vector<uint64_t> codes;
  if (!readFile(jsonPath, text)) return codes;
  size_t at = text.find("\"codes\"");
  if (at == std::string::npos) return codes;
  const size_t end = text.find(']', at);
  size_t p = text.find('[', at);
  while (p < end) {
    const size_t a = text.find('"', p);
    if (a == std::string::npos || a > end) break;
    const size_t b = text.find('"', a + 1);
    codes.push_back(std::strtoull(text.substr(a + 1, b - a - 1).c_str(), nullptr, 16));
    p = b + 1;
  }
  return codes;
}

/// Black cell of the printed face (row, col in 0..7 including the border).
inline bool isBlack(uint64_t code, int row, int col) {
  if (row == 0 || col == 0 || row == 7 || col == 7) return true;
  return ((code >> (35 - ((row - 1) * 6 + (col - 1)))) & 1) == 0;
}

struct Rect {
  double x, y, w, h;
};

struct PrintedMarker {
  int id;
  double x, y, size;  // page millimetres, top-left corner, y down
};

/// One SVG sheet: black rectangles and the markers with their printed ids.
struct Sheet {
  std::string name;
  double width = 0, height = 0;  // mm
  std::vector<Rect> rects;
  std::vector<PrintedMarker> markers;
  // Bucket index of the rects, 5 mm cells.
  int bx = 0, by = 0;
  std::vector<std::vector<int>> buckets;

  bool black(double x, double y) const {
    if (x < 0 || y < 0 || x >= width || y >= height) return false;
    const int ix = std::min(bx - 1, static_cast<int>(x / 5)), iy = std::min(by - 1, static_cast<int>(y / 5));
    for (const int r : buckets[iy * bx + ix]) {
      const Rect& q = rects[r];
      if (x >= q.x && x < q.x + q.w && y >= q.y && y < q.y + q.h) return true;
    }
    return false;
  }
};

inline double attr(const std::string& tag, const char* name) {
  const std::string key = std::string(" ") + name + "=\"";
  const size_t a = tag.find(key);
  if (a == std::string::npos) return NAN;
  return std::strtod(tag.c_str() + a + key.size(), nullptr);
}

/// Parses rectangles and "id N" labels; a marker is a full-width top border
/// row (8 modules wide, one high) with a matching bottom row 7 modules lower;
/// its id is the nearest "id N" label below it at the same x.
inline bool parseSheet(const std::string& path, Sheet& sheet) {
  std::string svg;
  if (!readFile(path, svg)) return false;
  sheet.name = path.substr(path.find_last_of('/') + 1);
  const size_t vb = svg.find("viewBox=\"");
  if (vb == std::string::npos) return false;
  double x0, y0;
  if (std::sscanf(svg.c_str() + vb + 9, "%lf %lf %lf %lf", &x0, &y0, &sheet.width, &sheet.height) != 4) return false;
  struct Label {
    double x, y;
    int id;
  };
  std::vector<Label> labels;
  size_t p = 0;
  while ((p = svg.find('<', p)) != std::string::npos) {
    const size_t e = svg.find('>', p);
    const std::string tag = svg.substr(p, e - p + 1);
    if (tag.rfind("<rect", 0) == 0 && tag.find("fill=\"#000\"") != std::string::npos) {
      sheet.rects.push_back({attr(tag, "x"), attr(tag, "y"), attr(tag, "width"), attr(tag, "height")});
    } else if (tag.rfind("<text", 0) == 0) {
      const size_t close = svg.find("</text>", e);
      const std::string body = svg.substr(e + 1, close - e - 1);
      if (body.rfind("id ", 0) == 0) labels.push_back({attr(tag, "x"), attr(tag, "y"), std::atoi(body.c_str() + 3)});
    }
    p = e;
  }
  for (const Rect& a : sheet.rects) {
    if (std::abs(a.w - 8 * a.h) > 1e-3) continue;
    for (const Rect& b : sheet.rects) {
      if (std::abs(b.x - a.x) < 1e-3 && std::abs(b.w - a.w) < 1e-3 && std::abs(b.h - a.h) < 1e-3 &&
          std::abs(b.y - (a.y + 7 * a.h)) < 1e-3) {
        const Label* best = nullptr;
        for (const Label& l : labels) {
          if (std::abs(l.x - a.x) < 1.0 && l.y > a.y + a.w && (best == nullptr || l.y < best->y)) best = &l;
        }
        sheet.markers.push_back({best == nullptr ? -1 : best->id, a.x, a.y, a.w});
      }
    }
  }
  sheet.bx = static_cast<int>(std::ceil(sheet.width / 5));
  sheet.by = static_cast<int>(std::ceil(sheet.height / 5));
  sheet.buckets.assign(static_cast<size_t>(sheet.bx) * sheet.by, {});
  for (int r = 0; r < static_cast<int>(sheet.rects.size()); r++) {
    const Rect& q = sheet.rects[r];
    for (int iy = std::max(0, static_cast<int>(q.y / 5)); iy <= std::min(sheet.by - 1, static_cast<int>((q.y + q.h) / 5)); iy++) {
      for (int ix = std::max(0, static_cast<int>(q.x / 5)); ix <= std::min(sheet.bx - 1, static_cast<int>((q.x + q.w) / 5)); ix++) {
        sheet.buckets[iy * sheet.bx + ix].push_back(r);
      }
    }
  }
  return true;
}

/// 3 x 3 homography (row-major) applied to (x, y).
inline bool applyH(const double* H, double x, double y, double& u, double& v) {
  const double w = H[6] * x + H[7] * y + H[8];
  if (std::abs(w) < 1e-15) return false;
  u = (H[0] * x + H[1] * y + H[2]) / w;
  v = (H[3] * x + H[4] * y + H[5]) / w;
  return true;
}

inline void invert3(const double* m, double* out) {
  const double a = m[4] * m[8] - m[5] * m[7], b = m[5] * m[6] - m[3] * m[8], c = m[3] * m[7] - m[4] * m[6];
  const double det = m[0] * a + m[1] * b + m[2] * c;
  out[0] = a / det;
  out[1] = (m[2] * m[7] - m[1] * m[8]) / det;
  out[2] = (m[1] * m[5] - m[2] * m[4]) / det;
  out[3] = b / det;
  out[4] = (m[0] * m[8] - m[2] * m[6]) / det;
  out[5] = (m[2] * m[3] - m[0] * m[5]) / det;
  out[6] = c / det;
  out[7] = (m[1] * m[6] - m[0] * m[7]) / det;
  out[8] = (m[0] * m[4] - m[1] * m[3]) / det;
}

/// Grey image of a plane seen through `H` (plane units to pixels, pixel
/// centres at integers): `ink(x, y)` gives the plane's grey value, `outside`
/// is used where the plane is not visible. `ss` x `ss` supersampling inside
/// the pixel window [x0, x1) x [y0, y1) (the whole image by default).
template <typename Ink>
std::vector<uint8_t> renderPlane(int width, int height, const double* H, Ink ink, uint8_t background, int ss = 4,
                                 int x0 = 0, int y0 = 0, int x1 = -1, int y1 = -1) {
  std::vector<uint8_t> img(static_cast<size_t>(width) * height, background);
  double Hi[9];
  invert3(H, Hi);
  if (x1 < 0) x1 = width;
  if (y1 < 0) y1 = height;
  for (int y = std::max(0, y0); y < std::min(height, y1); y++) {
    for (int x = std::max(0, x0); x < std::min(width, x1); x++) {
      int acc = 0;
      for (int j = 0; j < ss; j++) {
        for (int i = 0; i < ss; i++) {
          double px, py;
          const double u = x - 0.5 + (i + 0.5) / ss, v = y - 0.5 + (j + 0.5) / ss;
          acc += applyH(Hi, u, v, px, py) ? ink(px, py) : background;
        }
      }
      img[static_cast<size_t>(y) * width + x] = static_cast<uint8_t>((acc + ss * ss / 2) / (ss * ss));
    }
  }
  return img;
}

inline void gaussianBlur(std::vector<uint8_t>& img, int w, int h, double sigma) {
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

inline void addNoise(std::vector<uint8_t>& img, double sigma, uint64_t seed) {
  if (sigma <= 0) return;
  Rng rng{seed * 2654435761ull + 7};
  for (auto& p : img) p = static_cast<uint8_t>(std::lround(std::min(255.0, std::max(0.0, p + rng.gauss() * sigma))));
}

/// Uncompressed 24- or 32-bit BMP (as `sips -s format bmp` writes) to grey
/// (Rec. 601 luma, as the Y plane of a camera image).
inline bool readBmpGrey(const std::string& path, std::vector<uint8_t>& grey, int& width, int& height) {
  std::string b;
  if (!readFile(path, b) || b.size() < 54 || b[0] != 'B' || b[1] != 'M') return false;
  auto u32 = [&](size_t o) { return static_cast<uint32_t>(static_cast<uint8_t>(b[o]) | static_cast<uint8_t>(b[o + 1]) << 8 |
                                                      static_cast<uint8_t>(b[o + 2]) << 16 | static_cast<uint8_t>(b[o + 3]) << 24); };
  const uint32_t offset = u32(10);
  width = static_cast<int32_t>(u32(18));
  const int32_t h = static_cast<int32_t>(u32(22));
  height = std::abs(h);
  const int bpp = static_cast<uint8_t>(b[28]) | static_cast<uint8_t>(b[29]) << 8;
  if (bpp != 24 && bpp != 32) return false;
  const size_t row = ((static_cast<size_t>(width) * bpp / 8) + 3) & ~static_cast<size_t>(3);
  if (b.size() < offset + row * height) return false;
  grey.resize(static_cast<size_t>(width) * height);
  for (int y = 0; y < height; y++) {
    const size_t src = offset + row * (h > 0 ? height - 1 - y : y);
    for (int x = 0; x < width; x++) {
      const uint8_t* px = reinterpret_cast<const uint8_t*>(b.data() + src + static_cast<size_t>(x) * (bpp / 8));
      grey[static_cast<size_t>(y) * width + x] =
          static_cast<uint8_t>(std::lround(0.114 * px[0] + 0.587 * px[1] + 0.299 * px[2]));
    }
  }
  return true;
}

}  // namespace marker_test

#endif  // CAPTURE3D_MARKER_TEST_RENDER_H
