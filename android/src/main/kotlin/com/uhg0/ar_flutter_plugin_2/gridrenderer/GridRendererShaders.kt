package com.uhg0.ar_flutter_plugin_2.gridrenderer

/**
 * GLSL ES 3.00 programs of the grid renderer, ported from the measured
 * renderer prototype (`benchmarks/visibility_grid/.../RendererShaders.kt`) and
 * adapted to the contract record layout and colour attribute codes.
 *
 * Attribute locations: 0-4 surface record (position, octahedral normal,
 * meta0 = size f16 | source << 16 | flags << 24, meta1 = coverage |
 * completion << 8 | confidence << 16 | age << 24, bins 3 x u32), 5-6 brick or
 * region summary, 11 compact voxel (i16 x, y, z, colour u16). Colour tables
 * are one 256 x 5 RGBA8 texture, row = contract colour attribute code.
 */
internal object GridRendererShaders {
    private const val SURFACE_HEADER = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_oct;
layout(location = 2) in uint a_meta0;
layout(location = 3) in uint a_meta1;
layout(location = 4) in uvec3 a_bins;
uniform mat4 u_viewProj;
uniform vec3 u_camPos;
uniform float u_focalPx;
uniform int u_colorAttr;
uniform int u_sizeRule;
uniform float u_fixedPx;
uniform int u_selectedOnly;
uniform int u_grey;
uniform highp sampler2D u_colors;
out vec4 v_color;
const vec4 OFFSCREEN = vec4(2.0, 2.0, 2.0, 1.0);
const vec2 CORNERS[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));
vec3 octDecode(vec2 e) {
  vec3 n = vec3(e.x, e.y, 1.0 - abs(e.x) - abs(e.y));
  float t = max(-n.z, 0.0);
  n.x += n.x >= 0.0 ? -t : t;
  n.y += n.y >= 0.0 ? -t : t;
  return normalize(n);
}
bool surfaceValid() {
  bool published = ((a_meta0 >> 24u) & 1u) != 0u;
  bool selected = ((a_meta0 >> 26u) & 1u) != 0u;
  return published && (u_selectedOnly == 0 || selected);
}
float surfaceSize() { return unpackHalf2x16(a_meta0 & 0xffffu).x; }
float coverage() { return float(a_meta1 & 255u) / 255.0; }
float confidence() { return float((a_meta1 >> 16u) & 255u) / 255.0; }
uint attributeByte() {
  if (u_colorAttr == 1) return (a_meta1 >> 16u) & 255u;
  if (u_colorAttr == 2) return (a_meta1 >> 24u) & 255u;
  if (u_colorAttr == 3) return (a_meta0 >> 16u) & 255u;
  if (u_colorAttr == 4) return (a_meta1 >> 8u) & 255u;
  return a_meta1 & 255u;
}
float sizeFactor() {
  if (u_sizeRule == 1) return 1.6 - coverage();
  if (u_sizeRule == 2) return 0.6 + 0.8 * confidence();
  return 1.0;
}
// Out-of-scope surfaces (guided capture zone) draw desaturated and dimmed.
vec4 tableColor(uint value, int table) {
  vec4 c = texelFetch(u_colors, ivec2(int(value), table), 0);
  return u_grey == 0 ? c : vec4(vec3(dot(c.rgb, vec3(0.3, 0.59, 0.11)) * 0.5), c.a);
}
"""

    private const val SUMMARY_HEADER = """#version 300 es
precision highp float;
precision highp int;
layout(location = 5) in vec4 a_box0;
layout(location = 6) in uvec2 a_box1;
uniform mat4 u_viewProj;
uniform highp sampler2D u_colors;
uniform float u_scale;
uniform int u_summaryKind;
uniform int u_residencyFilter;
out vec4 v_color;
const vec4 OFFSCREEN = vec4(2.0, 2.0, 2.0, 1.0);
const vec3 STRIP[14] = vec3[14](
  vec3(-1.0, 1.0, 1.0), vec3(1.0, 1.0, 1.0), vec3(-1.0, -1.0, 1.0), vec3(1.0, -1.0, 1.0),
  vec3(1.0, -1.0, -1.0), vec3(1.0, 1.0, 1.0), vec3(1.0, 1.0, -1.0), vec3(-1.0, 1.0, 1.0),
  vec3(-1.0, 1.0, -1.0), vec3(-1.0, -1.0, 1.0), vec3(-1.0, -1.0, -1.0), vec3(1.0, -1.0, -1.0),
  vec3(-1.0, 1.0, -1.0), vec3(1.0, 1.0, -1.0));
// Brick: a_box0 = origin xyz, surface count; a_box1.x = complete | mean coverage << 16 | residency << 24.
// Region: a_box0 = key xyz, brick count; a_box1 = surface count, completion | residency << 8.
bool boxEmpty() { return u_summaryKind == 0 ? a_box0.w == 0.0 : a_box1.x == 0u; }
uint residency() { return u_summaryKind == 0 ? (a_box1.x >> 24u) & 255u : (a_box1.y >> 8u) & 255u; }
uint colourValue() { return u_summaryKind == 0 ? (a_box1.x >> 16u) & 255u : a_box1.y & 255u; }
bool boxHidden() { return boxEmpty() || (u_residencyFilter >= 0 && int(residency()) != u_residencyFilter); }
"""

    /** Point sprites, size from the size rule; optional sphere shading in the fragment shader. */
    const val POINTS_VS = SURFACE_HEADER + """
void main() {
  if (!surfaceValid()) { gl_Position = OFFSCREEN; gl_PointSize = 1.0; v_color = vec4(0.0); return; }
  gl_Position = u_viewProj * vec4(a_pos, 1.0);
  float px = u_fixedPx;
  if (u_sizeRule != 0) {
    px = clamp(surfaceSize() * sizeFactor() * u_focalPx / max(gl_Position.w, 0.05), 1.0, 64.0);
  }
  gl_PointSize = px;
  v_color = tableColor(attributeByte(), u_colorAttr);
}
"""

    const val POINTS_FS = """#version 300 es
precision mediump float;
uniform int u_sphere;
in vec4 v_color;
out vec4 o_color;
void main() {
  if (u_sphere == 0) { o_color = vec4(v_color.rgb, 1.0); return; }
  vec2 p = gl_PointCoord * 2.0 - 1.0;
  float r2 = dot(p, p);
  if (r2 > 1.0) discard;
  vec3 n = vec3(p.x, -p.y, sqrt(1.0 - r2));
  float light = 0.35 + 0.65 * max(dot(n, vec3(0.42, 0.57, 0.70)), 0.0);
  o_color = vec4(v_color.rgb * light, 1.0);
}
"""

    /** Instanced quad oriented by the surface normal, soft edge. */
    const val SPLAT_VS = SURFACE_HEADER + """
out vec2 v_uv;
out float v_light;
void main() {
  if (!surfaceValid()) { gl_Position = OFFSCREEN; v_uv = vec2(0.0); v_color = vec4(0.0); v_light = 0.0; return; }
  vec3 n = octDecode(a_oct);
  vec3 helper = abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
  vec3 t = normalize(cross(helper, n));
  vec3 b = cross(n, t);
  vec2 c = CORNERS[gl_VertexID];
  float radius = surfaceSize() * 0.8 * (u_sizeRule == 0 ? 1.0 : sizeFactor());
  vec3 p = a_pos + (t * c.x + b * c.y) * radius;
  gl_Position = u_viewProj * vec4(p, 1.0);
  v_uv = c;
  v_color = tableColor(attributeByte(), u_colorAttr);
  v_light = 0.4 + 0.6 * abs(dot(n, normalize(u_camPos - a_pos)));
}
"""

    const val SPLAT_FS = """#version 300 es
precision mediump float;
in vec2 v_uv;
in vec4 v_color;
in float v_light;
out vec4 o_color;
void main() {
  float r2 = dot(v_uv, v_uv);
  if (r2 > 1.0) discard;
  o_color = vec4(v_color.rgb * v_light, 1.0 - smoothstep(0.55, 1.0, r2));
}
"""

    /**
     * Directional spikes: surface attributes advance every 24 instances
     * (divisor 24), so instance = surface x 24 + bin. Only bins facing the
     * normal are drawn; a low quality gives a long spike (needs photos).
     */
    const val SPIKE_VS = SURFACE_HEADER + """
uniform vec3 u_binDirs[24];
void main() {
  int bin = gl_InstanceID % 24;
  vec3 dir = u_binDirs[bin];
  vec3 n = octDecode(a_oct);
  if (!surfaceValid() || dot(n, dir) < 0.25) { gl_Position = OFFSCREEN; v_color = vec4(0.0); return; }
  uint q = (a_bins[bin / 8] >> uint(4 * (bin % 8))) & 15u;
  float len = 0.03 + 0.09 * (1.0 - float(q) / 15.0);
  vec2 c = CORNERS[gl_VertexID];
  float along = c.y * 0.5 + 0.5;
  vec3 side = normalize(cross(dir, a_pos - u_camPos) + vec3(1e-5)) * 0.004 * (1.0 - along);
  gl_Position = u_viewProj * vec4(a_pos + dir * len * along + side * c.x, 1.0);
  v_color = tableColor(q * 17u, 0);
}
"""

    const val FLAT_FS = """#version 300 es
precision mediump float;
in vec4 v_color;
out vec4 o_color;
void main() { o_color = vec4(v_color.rgb, 1.0); }
"""

    /** Occupancy voxels from the compact 8 B per voxel list; padding entries (all bytes 0xFF) collapse. */
    const val VOXEL_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 11) in ivec4 a_voxel;
uniform mat4 u_viewProj;
uniform int u_colorAttr;
uniform int u_grey;
uniform highp sampler2D u_colors;
out vec4 v_color;
const vec3 STRIP[14] = vec3[14](
  vec3(-1.0, 1.0, 1.0), vec3(1.0, 1.0, 1.0), vec3(-1.0, -1.0, 1.0), vec3(1.0, -1.0, 1.0),
  vec3(1.0, -1.0, -1.0), vec3(1.0, 1.0, 1.0), vec3(1.0, 1.0, -1.0), vec3(-1.0, 1.0, 1.0),
  vec3(-1.0, 1.0, -1.0), vec3(-1.0, -1.0, 1.0), vec3(-1.0, -1.0, -1.0), vec3(1.0, -1.0, -1.0),
  vec3(-1.0, 1.0, -1.0), vec3(1.0, 1.0, -1.0));
void main() {
  if (a_voxel == ivec4(-1)) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); v_color = vec4(0.0); return; }
  uint colour = uint(a_voxel.w) & 0xffffu;
  bool byConfidence = u_colorAttr == 1;
  uint value = byConfidence ? (colour >> 8u) & 255u : colour & 255u;
  vec3 corner = STRIP[gl_VertexID];
  gl_Position = u_viewProj * vec4((vec3(a_voxel.xyz) + 0.5) * 0.1 + corner * 0.045, 1.0);
  vec4 c = texelFetch(u_colors, ivec2(int(value), byConfidence ? 1 : 0), 0);
  if (u_grey != 0) c = vec4(vec3(dot(c.rgb, vec3(0.3, 0.59, 0.11)) * 0.5), c.a);
  v_color = c * (0.65 + 0.35 * (corner.y * 0.5 + 0.5));
}
"""

    /** Wire box per brick or region (GL_LINES, 24 vertices). */
    const val BOX_WIRE_VS = SUMMARY_HEADER + """
const int EDGES[24] = int[24](0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7);
void main() {
  if (boxHidden()) { gl_Position = OFFSCREEN; v_color = vec4(0.0); return; }
  int c = EDGES[gl_VertexID];
  vec3 corner = vec3(float(c & 1), float((c >> 1) & 1), float((c >> 2) & 1));
  gl_Position = u_viewProj * vec4((a_box0.xyz + corner) * u_scale, 1.0);
  v_color = texelFetch(u_colors, ivec2(int(colourValue()), 0), 0);
}
"""

    /** Filled box per brick or region (14-vertex strip), drawn translucent. */
    const val BOX_FILL_VS = SUMMARY_HEADER + """
void main() {
  if (boxHidden()) { gl_Position = OFFSCREEN; v_color = vec4(0.0); return; }
  vec3 corner = STRIP[gl_VertexID] * 0.5 + 0.5;
  gl_Position = u_viewProj * vec4((a_box0.xyz + corner) * u_scale, 1.0);
  v_color = texelFetch(u_colors, ivec2(int(colourValue()), 0), 0);
}
"""

    const val BOX_FS = """#version 300 es
precision mediump float;
uniform float u_alpha;
in vec4 v_color;
out vec4 o_color;
void main() { o_color = vec4(v_color.rgb, u_alpha); }
"""

    /** Overlay line list (guided capture): position and RGBA8 colour per vertex. */
    const val LINE_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec4 a_rgba;
uniform mat4 u_viewProj;
out vec4 v_color;
void main() {
  gl_Position = u_viewProj * vec4(a_pos, 1.0);
  v_color = a_rgba;
}
"""

    const val LINE_FS = """#version 300 es
precision mediump float;
in vec4 v_color;
out vec4 o_color;
void main() { o_color = v_color; }
"""

    /** Planner targets: an arrow from the standpoint along the view direction (GL_LINES, 6 vertices per target). */
    const val TARGET_VS = """#version 300 es
precision highp float;
uniform mat4 u_viewProj;
uniform vec4 u_targets[32];
out vec4 v_color;
void main() {
  vec4 a = u_targets[gl_InstanceID * 2];
  vec3 dir = normalize(u_targets[gl_InstanceID * 2 + 1].xyz + vec3(1e-6));
  vec3 side = normalize(cross(dir, abs(dir.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
  vec3 tip = a.xyz + dir * 0.4;
  vec3 p = a.xyz;
  if (gl_VertexID == 1 || gl_VertexID == 2 || gl_VertexID == 4) p = tip;
  if (gl_VertexID == 3) p = tip - dir * 0.1 + side * 0.06;
  if (gl_VertexID == 5) p = tip - dir * 0.1 - side * 0.06;
  gl_Position = u_viewProj * vec4(p, 1.0);
  v_color = vec4(0.2, 0.9, 1.0, 1.0) * (0.6 + 0.4 * clamp(a.w, 0.0, 1.0));
}
"""
}
