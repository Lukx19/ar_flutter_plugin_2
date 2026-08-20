package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import android.os.Handler
import android.os.Looper

/** Fixed-capacity Filament buffers used by the live coverage point renderer. */
internal class CoveragePointMeshResources(
    private val engine: Engine,
    override val capacity: Int,
    private val telemetry: RendererTelemetry? = null,
    private val telemetryOwner: String = "coverage-points",
) : CoverageVoxelMeshResources {
    override val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .bufferCount(2)
        .vertexCount(capacity)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            POSITION_BUFFER_INDEX,
            VertexBuffer.AttributeType.FLOAT3,
        )
        .attribute(
            VertexBuffer.VertexAttribute.COLOR,
            COLOR_BUFFER_INDEX,
            VertexBuffer.AttributeType.UBYTE4,
        )
        .normalized(VertexBuffer.VertexAttribute.COLOR)
        .build(engine)

    override val indexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(capacity)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    private var lastRevision = Long.MIN_VALUE
    private var destroyed = false
    private val uploadCoordinator = CoveragePointUploadCoordinator(
        capacity = capacity,
        uploader = FilamentCoveragePointVertexUploader(engine, vertexBuffer),
        onUploadSubmitted = { bytes -> telemetry?.recordUpload(bytes) },
        onUploadCallback = { telemetry?.recordUploadCallback() },
        onUploadCompleted = { elapsedNanos -> telemetry?.recordUploadCompletion(elapsedNanos) },
    )
    private var indexStaging: java.nio.IntBuffer? = null

    override val primitiveType: RenderableManager.PrimitiveType =
        RenderableManager.PrimitiveType.POINTS

    init {
        // Position/color GPU buffers, index buffer, and retained direct upload
        // staging are renderer-owned. The transient index staging is excluded
        // here because it is released after the initial upload callback.
        telemetry?.setOwnedBufferBytes(telemetryOwner, capacity * OWNED_BYTES_PER_ROW)
        val indices = ByteBuffer.allocateDirect(capacity * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        repeat(capacity) { indices.put(it) }
        indices.flip()
        indexStaging = indices
        indexBuffer.setBuffer(
            engine,
            indices,
            0,
            capacity,
            Handler(Looper.getMainLooper()),
        ) { indexStaging = null }
    }

    override fun update(
        node: Node,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    ) {
        check(snapshot.capacity == capacity)
        check(snapshot.count in 0..capacity)
        check(snapshot.positions.size == snapshot.count * POSITION_COMPONENTS)
        check(snapshot.colors.size == snapshot.count)
        if (snapshot.revision != lastRevision) {
            if (snapshot.count > 0) {
                uploadCoordinator.submit(snapshot)
            }
            lastRevision = snapshot.revision
        }
        setDrawCount(
            node,
            if (snapshot.enabled) snapshot.count else 0,
        )
        materialInstance.setParameter("pointSize", pointSizePx)
        node.isVisible = snapshot.enabled && snapshot.count > 0
    }

    override fun hide(node: Node) {
        setDrawCount(node, 0)
        node.isVisible = false
    }

    private fun setDrawCount(node: Node, count: Int) {
        val renderableManager = engine.renderableManager
        val instance = renderableManager.getInstance(node.entity)
        renderableManager.setGeometryAt(
            instance,
            0,
            primitiveType,
            vertexBuffer,
            indexBuffer,
            0,
            count,
        )
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        uploadCoordinator.destroy()
        indexStaging = null
        telemetry?.removeOwner(telemetryOwner)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
    }

    internal companion object {
        // ARCore world coordinates for a capture workspace are expected to stay
        // well inside this range. This bound is required before the first point
        // arrives because Filament rejects an empty derived AABB.
        val DEFAULT_BOUNDING_BOX = Box(
            0f,
            0f,
            0f,
            1_000f,
            1_000f,
            1_000f,
        )
        const val POSITION_BUFFER_INDEX = 0
        const val COLOR_BUFFER_INDEX = 1
        const val POSITION_COMPONENTS = 3
        const val COLOR_COMPONENTS = 4
        const val OWNED_BYTES_PER_ROW =
            POSITION_COMPONENTS * Float.SIZE_BYTES + COLOR_COMPONENTS +
                Int.SIZE_BYTES + POSITION_COMPONENTS * Float.SIZE_BYTES + COLOR_COMPONENTS

        // VertexBuffer.setBufferAt sizes a typed FloatBuffer in float elements,
        // while the color upload below uses a raw ByteBuffer and therefore uses
        // bytes. Passing position bytes here overflows the FloatBuffer on the
        // first non-empty point-cloud snapshot.
        internal fun positionBufferElementCount(pointCount: Int): Int =
            pointCount * POSITION_COMPONENTS

        fun rgbaBytes(argbColors: IntArray): ByteBuffer {
            val bytes = ByteBuffer.allocateDirect(argbColors.size * COLOR_COMPONENTS)
            argbColors.forEach { color ->
                bytes.put((color shr 16 and 0xFF).toByte())
                bytes.put((color shr 8 and 0xFF).toByte())
                bytes.put((color and 0xFF).toByte())
                bytes.put((color ushr 24 and 0xFF).toByte())
            }
            bytes.flip()
            return bytes
        }
    }
}

internal interface CoveragePointVertexUploader {
    fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    )

    fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    )
}

internal class CoveragePointUploadCoordinator(
    capacity: Int,
    private val uploader: CoveragePointVertexUploader,
    private val onUploadSubmitted: (Int) -> Unit = {},
    private val onUploadCallback: () -> Unit = {},
    private val onUploadCompleted: (Long) -> Unit = {},
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val buffers = CoveragePointUploadBuffers(capacity)
    private var uploadBusy = false
    private var consumedCallbackMask = 0
    private var activeUploadId = 0L
    private var pendingSnapshot: CoveragePointRenderSnapshot? = null
    private var hasUploadedSnapshot = false
    private var destroyed = false
    private var activeUploadStartedNanos = 0L

    fun submit(snapshot: CoveragePointRenderSnapshot) {
        if (destroyed) return
        pendingSnapshot =
            if (uploadBusy && snapshot.update?.reset == false) {
                snapshot.copy(update = snapshot.update.copy(reset = true))
            } else {
                snapshot
            }
        drain()
    }

    fun stagingBuffers(): CoveragePointUploadBuffers = buffers

    fun destroy() {
        destroyed = true
        pendingSnapshot = null
    }

    private fun drain() {
        if (destroyed || uploadBusy) return
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        val update = snapshot.update
        val spans = update?.spans.orEmpty()
        val fullUpload = !hasUploadedSnapshot || update == null || update.reset
        if (!fullUpload && spans.isEmpty()) {
            return
        }
        val startSlot = if (fullUpload) {
            0
        } else {
            spans.minOf { it.startSlot }
        }
        val endSlot = if (fullUpload) {
            snapshot.count
        } else {
            spans.maxOf { it.startSlot + it.colors.size }
        }
        buffers.writeRange(snapshot.positions, snapshot.colors, startSlot, endSlot)
        onUploadSubmitted(
            (endSlot - startSlot) *
                (CoveragePointMeshResources.POSITION_COMPONENTS * Float.SIZE_BYTES +
                    CoveragePointMeshResources.COLOR_COMPONENTS),
        )
        hasUploadedSnapshot = true
        uploadBusy = true
        consumedCallbackMask = 0
        activeUploadStartedNanos = clockNanos()
        val uploadId = ++activeUploadId
        uploader.uploadPositions(
            buffers.positionBuffer,
            startSlot * CoveragePointMeshResources.POSITION_COMPONENTS * Float.SIZE_BYTES,
            CoveragePointMeshResources.positionBufferElementCount(endSlot - startSlot),
        ) { consumed(uploadId, POSITION_CALLBACK) }
        uploader.uploadColors(
            buffers.colorBuffer,
            startSlot * CoveragePointMeshResources.COLOR_COMPONENTS,
            (endSlot - startSlot) * CoveragePointMeshResources.COLOR_COMPONENTS,
        ) { consumed(uploadId, COLOR_CALLBACK) }
    }

    private fun consumed(uploadId: Long, callbackBit: Int) {
        if (destroyed || !uploadBusy || uploadId != activeUploadId) return
        if (consumedCallbackMask and callbackBit != 0) return
        onUploadCallback()
        consumedCallbackMask = consumedCallbackMask or callbackBit
        if (consumedCallbackMask == BOTH_CALLBACKS) {
            onUploadCompleted((clockNanos() - activeUploadStartedNanos).coerceAtLeast(0L))
            uploadBusy = false
            drain()
        }
    }

    private companion object {
        const val POSITION_CALLBACK = 1
        const val COLOR_CALLBACK = 2
        const val BOTH_CALLBACKS = POSITION_CALLBACK or COLOR_CALLBACK
    }
}

private class FilamentCoveragePointVertexUploader(
    private val engine: Engine,
    private val vertexBuffer: VertexBuffer,
) : CoveragePointVertexUploader {
    private val callbackHandler = Handler(Looper.getMainLooper())

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        vertexBuffer.setBufferAt(
            engine,
            CoveragePointMeshResources.POSITION_BUFFER_INDEX,
            buffer,
            destOffsetBytes,
            elementCount,
            callbackHandler,
            Runnable(onConsumed),
        )
    }

    override fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    ) {
        vertexBuffer.setBufferAt(
            engine,
            CoveragePointMeshResources.COLOR_BUFFER_INDEX,
            buffer,
            destOffsetBytes,
            byteCount,
            callbackHandler,
            Runnable(onConsumed),
        )
    }
}
