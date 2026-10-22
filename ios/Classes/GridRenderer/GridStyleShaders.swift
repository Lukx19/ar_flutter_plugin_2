import Foundation

/// Metal programs of the grid renderer: the GLSL ES 3.00 programs of the
/// Android `GridRendererShaders.kt` ported to Metal along the lines of the
/// iPhone-measured benchmark ports (`benchmarks/visibility_grid/ios/Runner/
/// Bench/PrimitiveShaders.swift`, all presets 60 fps), adapted to the
/// contract record layout.
///
/// Instance data is read from buffers by index (no vertex descriptors);
/// runs re-point buffer 0 by offset, so `vertex_id` and `instance_id` start
/// at 0 per draw, as the Android attribute pointers do. Surface record
/// words: meta0 = size f16 | source << 16 | flags << 24, meta1 = coverage |
/// completion << 8 | confidence << 16 | age << 24, bins 3 x u32. Colour
/// tables are one 256 x 5 RGBA8 texture, row = contract colour attribute
/// code. Metal clip depth is 0..1 (the ARKit projection targets it).
///
/// Quality threshold: `minQuality` (display mode byte 20) discards surface
/// instances whose confidence byte is below it (points, splats, spike balls
/// and their spikes) and voxels whose confidence (colour high byte, capped
/// at 254 by the exporter) is below min(minQuality, 254).
///
/// Compiled at run time like the camera background (`CaptureMetalShaders`):
/// the plugin is a static pod whose `.metal` files would land in a library
/// the main bundle does not see.
enum GridStyleShaders {
  static let source = """
  #include <metal_stdlib>
  using namespace metal;

  struct Surface { packed_float3 pos; short2 oct; uint meta0; uint meta1; uint bins[3]; uint stableId; };
  struct Summary { short4 a; uint2 b; };
  struct LineVertex { packed_float3 pos; uchar4 rgba; };
  struct GridUniforms {
    float4x4 viewProj;
    float4 camPos;
    float focalPx; float fixedPx; int colorAttr; int sizeRule;
    int selectedOnly; int grey; int minQuality; int sphere;
    float scale; int summaryKind; int residencyFilter; float alpha;
  };
  struct VOut { float4 pos [[position]]; float4 color; float2 uv; float light; };
  struct POut { float4 pos [[position]]; float psize [[point_size]]; float4 color; };

  constant float2 CORNERS[4] = { float2(-1, -1), float2(1, -1), float2(-1, 1), float2(1, 1) };
  constant float3 STRIP[14] = {
    float3(-1, 1, 1), float3(1, 1, 1), float3(-1, -1, 1), float3(1, -1, 1),
    float3(1, -1, -1), float3(1, 1, 1), float3(1, 1, -1), float3(-1, 1, 1),
    float3(-1, 1, -1), float3(-1, -1, 1), float3(-1, -1, -1), float3(1, -1, -1),
    float3(-1, 1, -1), float3(1, 1, -1) };
  constant int EDGES[24] = { 0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7 };
  constant float4 OFFSCREEN = float4(2, 2, 2, 1);

  static float3 octDecode(float2 e) {
    float3 n = float3(e.x, e.y, 1.0 - abs(e.x) - abs(e.y));
    float t = max(-n.z, 0.0);
    n.x += n.x >= 0.0 ? -t : t;
    n.y += n.y >= 0.0 ? -t : t;
    return normalize(n);
  }
  static float3 surfaceNormal(Surface s) { return octDecode(max(float2(s.oct) / 32767.0, -1.0)); }
  static bool surfaceValid(Surface s, constant GridUniforms& u) {
    bool published = ((s.meta0 >> 24) & 1u) != 0u;
    bool selected = ((s.meta0 >> 26) & 1u) != 0u;
    bool good = int((s.meta1 >> 16) & 255u) >= u.minQuality;
    return published && good && (u.selectedOnly == 0 || selected);
  }
  static float surfaceSize(Surface s) { return float(as_type<half2>(s.meta0).x); }
  static float coverage(Surface s) { return float(s.meta1 & 255u) / 255.0; }
  static float confidence(Surface s) { return float((s.meta1 >> 16) & 255u) / 255.0; }
  static uint attributeByte(Surface s, int a) {
    if (a == 1) return (s.meta1 >> 16) & 255u;
    if (a == 2) return (s.meta1 >> 24) & 255u;
    if (a == 3) return (s.meta0 >> 16) & 255u;
    if (a == 4) return (s.meta1 >> 8) & 255u;
    return s.meta1 & 255u;
  }
  static float sizeFactor(Surface s, int rule) {
    if (rule == 1) return 1.6 - coverage(s);
    if (rule == 2) return 0.6 + 0.8 * confidence(s);
    return 1.0;
  }
  static float4 greyed(float4 c) { return float4(float3(dot(c.rgb, float3(0.3, 0.59, 0.11)) * 0.5), c.a); }
  // Out-of-scope surfaces (guided capture zone) draw desaturated and dimmed.
  static float4 tableColor(texture2d<float, access::read> t, uint value, int table, int grey) {
    float4 c = t.read(uint2(min(value, 255u), uint(table)));
    return grey == 0 ? c : greyed(c);
  }
  // The grid's 24 direction bins: three elevation bands of eight azimuths.
  static float3 binDirection(int bin) {
    int band = bin / 8;
    float el = (band == 0 ? 41.8 : (band == 1 ? 0.0 : -41.8)) * M_PI_F / 180.0;
    float az = (float(bin % 8) * 45.0 + 22.5) * M_PI_F / 180.0;
    return float3(cos(el) * cos(az), sin(el), cos(el) * sin(az));
  }

  // Point sprites, size from the size rule; optional sphere shading.
  vertex POut grid_points_vs(uint vid [[vertex_id]], const device Surface* s [[buffer(0)]],
                             constant GridUniforms& u [[buffer(1)]], texture2d<float, access::read> colors [[texture(0)]]) {
    Surface r = s[vid];
    POut o;
    if (!surfaceValid(r, u)) { o.pos = OFFSCREEN; o.psize = 1; o.color = float4(0); return o; }
    o.pos = u.viewProj * float4(float3(r.pos), 1);
    float px = u.fixedPx;
    if (u.sizeRule != 0) {
      px = clamp(surfaceSize(r) * sizeFactor(r, u.sizeRule) * u.focalPx / max(o.pos.w, 0.05), 1.0, 64.0);
    }
    o.psize = px;
    o.color = tableColor(colors, attributeByte(r, u.colorAttr), u.colorAttr, u.grey);
    return o;
  }

  fragment float4 grid_points_fs(POut in [[stage_in]], float2 pc [[point_coord]], constant GridUniforms& u [[buffer(1)]]) {
    if (u.sphere == 0) return float4(in.color.rgb, 1.0);
    float2 p = pc * 2.0 - 1.0;
    float r2 = dot(p, p);
    if (r2 > 1.0) discard_fragment();
    float3 n = float3(p.x, -p.y, sqrt(1.0 - r2));
    float light = 0.35 + 0.65 * max(dot(n, float3(0.42, 0.57, 0.70)), 0.0);
    return float4(in.color.rgb * light, 1.0);
  }

  // Instanced quad oriented by the surface normal, soft edge.
  vertex VOut grid_splat_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device Surface* s [[buffer(0)]],
                            constant GridUniforms& u [[buffer(1)]], texture2d<float, access::read> colors [[texture(0)]]) {
    Surface r = s[iid];
    VOut o;
    if (!surfaceValid(r, u)) { o.pos = OFFSCREEN; o.color = float4(0); o.uv = float2(0); o.light = 0; return o; }
    float3 a = float3(r.pos);
    float3 n = surfaceNormal(r);
    float3 helper = abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0);
    float3 t = normalize(cross(helper, n));
    float3 b = cross(n, t);
    float2 c = CORNERS[vid];
    float radius = surfaceSize(r) * 0.8 * (u.sizeRule == 0 ? 1.0 : sizeFactor(r, u.sizeRule));
    o.pos = u.viewProj * float4(a + (t * c.x + b * c.y) * radius, 1);
    o.uv = c;
    o.color = tableColor(colors, attributeByte(r, u.colorAttr), u.colorAttr, u.grey);
    o.light = 0.4 + 0.6 * abs(dot(n, normalize(u.camPos.xyz - a)));
    return o;
  }

  fragment float4 grid_splat_fs(VOut in [[stage_in]]) {
    float r2 = dot(in.uv, in.uv);
    if (r2 > 1.0) discard_fragment();
    return float4(in.color.rgb * in.light, 1.0 - smoothstep(0.55, 1.0, r2));
  }

  // Directional spikes: instance = surface x 24 + bin. Only bins facing the
  // normal are drawn; a low quality gives a long spike (needs photos).
  vertex VOut grid_spike_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device Surface* s [[buffer(0)]],
                            constant GridUniforms& u [[buffer(1)]], texture2d<float, access::read> colors [[texture(0)]]) {
    Surface r = s[iid / 24];
    VOut o; o.uv = float2(0); o.light = 1;
    int bin = int(iid % 24);
    float3 dir = binDirection(bin);
    float3 n = surfaceNormal(r);
    if (!surfaceValid(r, u) || dot(n, dir) < 0.25) { o.pos = OFFSCREEN; o.color = float4(0); return o; }
    uint q = (r.bins[bin / 8] >> uint(4 * (bin % 8))) & 15u;
    float len = 0.03 + 0.09 * (1.0 - float(q) / 15.0);
    float2 c = CORNERS[vid];
    float along = c.y * 0.5 + 0.5;
    float3 a = float3(r.pos);
    float3 side = normalize(cross(dir, a - u.camPos.xyz) + float3(1e-5)) * 0.004 * (1.0 - along);
    o.pos = u.viewProj * float4(a + dir * len * along + side * c.x, 1);
    o.color = tableColor(colors, q * 17u, 0, 0);
    return o;
  }

  fragment float4 grid_flat_fs(VOut in [[stage_in]]) { return float4(in.color.rgb, 1.0); }

  // Occupancy voxels from the compact 8 B per voxel list; padding entries
  // (every byte 0xFF) collapse.
  vertex VOut grid_voxel_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device short4* v [[buffer(0)]],
                            constant GridUniforms& u [[buffer(1)]], texture2d<float, access::read> colors [[texture(0)]]) {
    short4 e = v[iid];
    VOut o; o.uv = float2(0); o.light = 1;
    if (all(e == short4(-1))) { o.pos = OFFSCREEN; o.color = float4(0); return o; }
    uint colour = uint(ushort(e.w));
    if (int((colour >> 8) & 255u) < min(u.minQuality, 254)) { o.pos = OFFSCREEN; o.color = float4(0); return o; }
    bool byConfidence = u.colorAttr == 1;
    uint value = byConfidence ? (colour >> 8) & 255u : colour & 255u;
    float3 corner = STRIP[vid];
    o.pos = u.viewProj * float4((float3(e.xyz) + 0.5) * 0.1 + corner * 0.045, 1);
    float4 c = colors.read(uint2(value, byConfidence ? 1u : 0u));
    if (u.grey != 0) c = greyed(c);
    o.color = c * (0.65 + 0.35 * (corner.y * 0.5 + 0.5));
    return o;
  }

  // Brick: a = origin xyz, surface count; b.x = complete | mean coverage << 16 | residency << 24.
  // Region: a = key xyz, brick count; b = surface count, completion | residency << 8.
  static bool boxHidden(Summary s, constant GridUniforms& u) {
    bool empty = u.summaryKind == 0 ? s.a.w == 0 : s.b.x == 0u;
    uint residency = u.summaryKind == 0 ? (s.b.x >> 24) & 255u : (s.b.y >> 8) & 255u;
    return empty || (u.residencyFilter >= 0 && int(residency) != u.residencyFilter);
  }
  static uint boxColourValue(Summary s, constant GridUniforms& u) {
    return u.summaryKind == 0 ? (s.b.x >> 16) & 255u : s.b.y & 255u;
  }

  // Wire box per brick or region (line list, 24 vertices).
  vertex VOut grid_box_wire_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device Summary* b [[buffer(0)]],
                               constant GridUniforms& u [[buffer(1)]], texture2d<float, access::read> colors [[texture(0)]]) {
    Summary s = b[iid];
    VOut o; o.uv = float2(0); o.light = 1;
    if (boxHidden(s, u)) { o.pos = OFFSCREEN; o.color = float4(0); return o; }
    int c = EDGES[vid];
    float3 corner = float3(float(c & 1), float((c >> 1) & 1), float((c >> 2) & 1));
    o.pos = u.viewProj * float4((float3(s.a.xyz) + corner) * u.scale, 1);
    o.color = colors.read(uint2(boxColourValue(s, u), 0));
    return o;
  }

  // Filled box per brick or region (14-vertex strip), drawn translucent.
  vertex VOut grid_box_fill_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device Summary* b [[buffer(0)]],
                               constant GridUniforms& u [[buffer(1)]], texture2d<float, access::read> colors [[texture(0)]]) {
    Summary s = b[iid];
    VOut o; o.uv = float2(0); o.light = 1;
    if (boxHidden(s, u)) { o.pos = OFFSCREEN; o.color = float4(0); return o; }
    float3 corner = STRIP[vid] * 0.5 + 0.5;
    o.pos = u.viewProj * float4((float3(s.a.xyz) + corner) * u.scale, 1);
    o.color = colors.read(uint2(boxColourValue(s, u), 0));
    return o;
  }

  fragment float4 grid_box_fs(VOut in [[stage_in]], constant GridUniforms& u [[buffer(1)]]) {
    return float4(in.color.rgb, u.alpha);
  }

  // Overlay line list (guided capture, markers, pose frusta): position and RGBA8 per vertex.
  vertex VOut grid_line_vs(uint vid [[vertex_id]], const device LineVertex* v [[buffer(0)]],
                           constant GridUniforms& u [[buffer(1)]]) {
    VOut o; o.uv = float2(0); o.light = 1;
    o.pos = u.viewProj * float4(float3(v[vid].pos), 1);
    o.color = float4(v[vid].rgba) / 255.0;
    return o;
  }

  fragment float4 grid_line_fs(VOut in [[stage_in]]) { return in.color; }

  // Raw ARKit feature points: screen-sized round dots (fixedPx), yellow with
  // a dark rim, drawn on top while the grid samples tracking points only.
  vertex POut grid_raw_points_vs(uint vid [[vertex_id]], const device packed_float3* p [[buffer(0)]],
                                 constant GridUniforms& u [[buffer(1)]]) {
    POut o;
    o.pos = u.viewProj * float4(float3(p[vid]), 1);
    o.psize = u.fixedPx;
    o.color = float4(1.0, 0.86, 0.1, 1.0);
    return o;
  }

  fragment float4 grid_raw_points_fs(POut in [[stage_in]], float2 pc [[point_coord]]) {
    float2 d = pc * 2.0 - 1.0;
    float r = dot(d, d);
    if (r > 1.0) discard_fragment();
    return r > 0.55 ? float4(0.1, 0.1, 0.1, 0.9) : in.color;
  }

  // Planner targets: an arrow from the standpoint along the view direction
  // (line list, 6 vertices per target).
  vertex VOut grid_target_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant float4* targets [[buffer(0)]],
                             constant GridUniforms& u [[buffer(1)]]) {
    float4 a = targets[iid * 2];
    float3 dir = normalize(targets[iid * 2 + 1].xyz + float3(1e-6));
    float3 side = normalize(cross(dir, abs(dir.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0)));
    float3 tip = a.xyz + dir * 0.4;
    float3 p = a.xyz;
    if (vid == 1 || vid == 2 || vid == 4) p = tip;
    if (vid == 3) p = tip - dir * 0.1 + side * 0.06;
    if (vid == 5) p = tip - dir * 0.1 - side * 0.06;
    VOut o; o.uv = float2(0); o.light = 1;
    o.pos = u.viewProj * float4(p, 1);
    o.color = float4(0.2, 0.9, 1.0, 1.0) * (0.6 + 0.4 * clamp(a.w, 0.0, 1.0));
    return o;
  }
  """
}
