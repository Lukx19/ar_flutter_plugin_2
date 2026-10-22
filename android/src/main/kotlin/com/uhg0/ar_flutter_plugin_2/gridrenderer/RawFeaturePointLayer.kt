package com.uhg0.ar_flutter_plugin_2.gridrenderer

import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Latest raw ARCore feature points, written by the frame callback and copied
 * by the render thread. The frame side copies only while [wanted] (the
 * display mode carries [GridDisplayCodes.OVERLAY_RAW_FEATURE_POINTS]) and only
 * a point cloud with a new timestamp; it never keeps the ARCore object.
 */
internal class RawFeaturePointBuffer(
    private val capacity: Int = MAX_POINTS,
    private val log: ((String) -> Unit)? = null,
) {
    private val lock = Any()
    private val xyz = FloatArray(capacity * 3)
    private var count = 0
    private var cloudTimestampNs = Long.MIN_VALUE

    /** Set by the render thread from the current display mode. */
    @Volatile var wanted = false

    /** Bumped by every accepted cloud; the render thread copies on change. */
    @Volatile var version = 0L
        private set

    /** Point clouds copied, points offered and kept, for the telemetry line. */
    var clouds = 0L
        private set
    var pointsIn = 0L
        private set
    var pointsKept = 0L
        private set
    var lastCount = 0
        private set
    private var lastLogNs = Long.MIN_VALUE

    /**
     * Copies the frame's point cloud while [wanted]; never throws (a failed
     * acquisition keeps the previous points).
     */
    fun offer(frame: Frame) {
        if (!wanted) return
        try {
            frame.acquirePointCloud().use { cloud ->
                val timestamp = cloud.timestamp
                if (timestamp == cloudTimestampNs) return
                val points = cloud.points
                offer(points, points.remaining() / FLOATS_PER_POINT, timestamp, frame.timestamp)
            }
        } catch (e: RuntimeException) {
            // NotYetAvailable, a paused session or a released frame: nothing new to draw.
        }
    }

    /**
     * Copies up to [capacity] finite points of [points] (x, y, z, confidence
     * per point, world frame) for [timestampNs]; logs counts at most every
     * [LOG_INTERVAL_NS] of frame time [frameNs].
     */
    fun offer(points: FloatBuffer, pointCount: Int, timestampNs: Long, frameNs: Long) {
        if (!wanted || timestampNs == cloudTimestampNs) return
        synchronized(lock) {
            // Drawing may have been switched off (and cleared) meanwhile.
            if (!wanted) return
            var kept = 0
            val base = points.position()
            var i = 0
            while (i < pointCount && kept < capacity) {
                val at = base + i * FLOATS_PER_POINT
                val x = points.get(at)
                val y = points.get(at + 1)
                val z = points.get(at + 2)
                i++
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                xyz[kept * 3] = x
                xyz[kept * 3 + 1] = y
                xyz[kept * 3 + 2] = z
                kept++
            }
            count = kept
            cloudTimestampNs = timestampNs
            clouds++
            pointsIn += pointCount
            pointsKept += kept
            lastCount = kept
            version++
        }
        val sink = log ?: return
        if (lastLogNs == Long.MIN_VALUE || frameNs - lastLogNs >= LOG_INTERVAL_NS) {
            lastLogNs = frameNs
            sink("[grid-raw-points] clouds=$clouds pointsIn=$pointsIn pointsKept=$pointsKept last=$lastCount")
        }
    }

    /** Forgets the points and stops copying (drawing turned off or the context went away). */
    fun clear() = synchronized(lock) {
        wanted = false
        count = 0
        cloudTimestampNs = Long.MIN_VALUE
        version++
    }

    /** Copies the points into [out] (cleared first); returns their count. */
    fun copyTo(out: FloatBuffer): Int = synchronized(lock) {
        out.clear()
        out.put(xyz, 0, count * 3)
        out.flip()
        count
    }

    companion object {
        /** ARCore reports a few hundred points; more are never drawn. */
        const val MAX_POINTS = 2048
        const val FLOATS_PER_POINT = 4
        const val LOG_INTERVAL_NS = 2_000_000_000L
    }
}

/**
 * Draws [points] as round screen-sized dots after the grid styles while the
 * display mode carries [GridDisplayCodes.OVERLAY_RAW_FEATURE_POINTS], so a
 * device without depth shows its tracking points as soon as ARCore reports
 * them (the grid needs several frames and views before it shows surfaces).
 * Drawn without the depth test: the points lie on the surfaces they
 * describe.
 */
internal class RawFeaturePointLayer(
    val points: RawFeaturePointBuffer,
    private val pxPerDp: () -> Float,
) : GridOverlayLayer {
    private var program = 0
    private var viewProj = -1
    private var pointPx = -1
    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(RawFeaturePointBuffer.MAX_POINTS * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private var uploadedVersion = -1L
    private var uploadedCount = 0

    override fun attach(context: GridOverlayContext) {
        program = linkProgram(VS, FS)
        viewProj = GLES30.glGetUniformLocation(program, "u_viewProj")
        pointPx = GLES30.glGetUniformLocation(program, "u_pointPx")
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        uploadedVersion = -1L
        uploadedCount = 0
    }

    override fun draw(frame: GridOverlayFrame) {
        val wanted = frame.overlayBits and GridDisplayCodes.OVERLAY_RAW_FEATURE_POINTS != 0
        if (points.wanted != wanted) {
            if (wanted) points.wanted = true else points.clear()
        }
        if (!wanted || program == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val version = points.version
        if (version != uploadedVersion) {
            uploadedCount = points.copyTo(vertices)
            uploadedVersion = version
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, uploadedCount * 3 * 4, vertices, GLES30.GL_STREAM_DRAW)
        }
        if (uploadedCount > 0) {
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(viewProj, 1, false, frame.viewProjection, 0)
            GLES30.glUniform1f(pointPx, POINT_DP * pxPerDp())
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribDivisor(0, 0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, uploadedCount)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(0)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    override fun detach() {
        points.clear()
        if (program != 0) GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        program = 0
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v)
        GLES30.glAttachShader(id, f)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        if (ok[0] != GLES30.GL_TRUE) {
            // No raw points rather than a failed grid render thread.
            Log.e(TAG, "raw feature point program link failed: ${GLES30.glGetProgramInfoLog(id)}")
            GLES30.glDeleteProgram(id)
            return 0
        }
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        return s
    }

    companion object {
        private const val TAG = "GridRenderer"

        /** Dot diameter in dp. */
        const val POINT_DP = 5f

        const val VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 a_pos;
uniform mat4 u_viewProj;
uniform float u_pointPx;
void main() {
  gl_Position = u_viewProj * vec4(a_pos, 1.0);
  gl_PointSize = u_pointPx;
}
"""

        const val FS = """#version 300 es
precision mediump float;
out vec4 o_color;
void main() {
  vec2 d = gl_PointCoord * 2.0 - 1.0;
  float r = dot(d, d);
  if (r > 1.0) discard;
  // Yellow dot with a dark rim, readable on bright and dark scenes.
  o_color = r > 0.55 ? vec4(0.1, 0.1, 0.1, 0.9) : vec4(1.0, 0.86, 0.1, 1.0);
}
"""
    }
}
