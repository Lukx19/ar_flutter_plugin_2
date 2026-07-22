package com.uhg0.ar_flutter_plugin_2.sceneview

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.github.sceneview.node.MeshNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fixed-capacity cube geometry. Each occupied voxel is represented by a
 * colored cube whose center is the Dart-owned voxel centroid.
 */
internal class CoverageCubeMeshResources(
    private val engine: Engine,
    override val capacity: Int,
    voxelSizeMeters: Float,
) : CoverageVoxelMeshResources {
    private val halfSize = voxelSizeMeters / 2f
    private val vertexCapacity = capacity * VERTICES_PER_VOXEL
    private val indexCapacity = capacity * INDICES_PER_VOXEL

    override val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .bufferCount(2)
        .vertexCount(vertexCapacity)
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
        .indexCount(indexCapacity)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    override val primitiveType: RenderableManager.PrimitiveType =
        RenderableManager.PrimitiveType.TRIANGLES

    private val uploadCoordinator = CoverageCubeUploadCoordinator(
        capacity = capacity,
        halfSize = halfSize,
        uploader = FilamentCoverageCubeVertexUploader(engine, vertexBuffer),
    )
    private var indexStaging: java.nio.IntBuffer? = null
    private var lastRevision = Long.MIN_VALUE
    private var destroyed = false

    init {
        val indices = ByteBuffer.allocateDirect(indexCapacity * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        repeat(capacity) { voxel ->
            val vertexOffset = voxel * VERTICES_PER_VOXEL
            CUBE_INDICES.forEach { index -> indices.put(vertexOffset + index) }
        }
        indices.flip()
        indexStaging = indices
        indexBuffer.setBuffer(
            engine,
            indices,
            0,
            indexCapacity,
            Handler(Looper.getMainLooper()),
        ) { indexStaging = null }
    }

    override fun update(
        node: MeshNode,
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
            val instance = engine.renderableManager.getInstance(node.entity)
            engine.renderableManager.setGeometryAt(
                instance,
                0,
                primitiveType,
                vertexBuffer,
                indexBuffer,
                0,
                snapshot.count * INDICES_PER_VOXEL,
            )
            lastRevision = snapshot.revision
        }
        node.isVisible = snapshot.enabled && snapshot.count > 0
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        uploadCoordinator.destroy()
        indexStaging = null
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
    }

    internal companion object {
        const val POSITION_BUFFER_INDEX = 0
        const val COLOR_BUFFER_INDEX = 1
        const val POSITION_COMPONENTS = 3
        const val COLOR_COMPONENTS = 4
        const val VERTICES_PER_VOXEL = 8
        const val INDICES_PER_VOXEL = 36

        // Two triangles per cube face, using the eight corners in the order:
        // (-,-,-), (+,-,-), (+,+,-), (-,+,-), (-,-,+), (+,-,+), (+,+,+), (-,+,+).
        private val CUBE_INDICES = intArrayOf(
            0, 2, 1, 0, 3, 2,
            4, 5, 6, 4, 6, 7,
            0, 4, 7, 0, 7, 3,
            1, 6, 5, 1, 2, 6,
            0, 1, 5, 0, 5, 4,
            3, 6, 2, 3, 7, 6,
        )

        private val CUBE_CORNERS = floatArrayOf(
            -1f, -1f, -1f,
            1f, -1f, -1f,
            1f, 1f, -1f,
            -1f, 1f, -1f,
            -1f, -1f, 1f,
            1f, -1f, 1f,
            1f, 1f, 1f,
            -1f, 1f, 1f,
        )

        val DEFAULT_BOUNDING_BOX = Box(
            0f,
            0f,
            0f,
            1_000f,
            1_000f,
            1_000f,
        )

        internal fun cubeIndices(): IntArray = CUBE_INDICES.copyOf()

        internal fun cubeCorners(): FloatArray = CUBE_CORNERS.copyOf()
    }

    internal class CoverageCubeUploadCoordinator(
        capacity: Int,
        private val halfSize: Float,
        private val uploader: CoverageCubeVertexUploader,
    ) {
        private val positionBuffer = ByteBuffer.allocateDirect(
            capacity * VERTICES_PER_VOXEL * POSITION_COMPONENTS * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val colorBuffer = ByteBuffer.allocateDirect(
            capacity * VERTICES_PER_VOXEL * COLOR_COMPONENTS,
        ).order(ByteOrder.nativeOrder())
        private var pendingSnapshot: CoveragePointRenderSnapshot? = null
        private var uploadBusy = false
        private var consumedCallbackMask = 0
        private var activeUploadId = 0L
        private var destroyed = false

        fun submit(snapshot: CoveragePointRenderSnapshot) {
            if (destroyed) return
            pendingSnapshot = snapshot
            drain()
        }

        fun destroy() {
            destroyed = true
            pendingSnapshot = null
        }

        private fun drain() {
            if (destroyed || uploadBusy) return
            val snapshot = pendingSnapshot ?: return
            pendingSnapshot = null
            write(snapshot)
            uploadBusy = true
            consumedCallbackMask = 0
            val uploadId = ++activeUploadId
            val vertexCount = snapshot.count * VERTICES_PER_VOXEL
            uploader.uploadPositions(positionBuffer, vertexCount * POSITION_COMPONENTS) {
                consumed(uploadId, POSITION_CALLBACK)
            }
            uploader.uploadColors(colorBuffer, vertexCount * COLOR_COMPONENTS) {
                consumed(uploadId, COLOR_CALLBACK)
            }
        }

        private fun write(snapshot: CoveragePointRenderSnapshot) {
            positionBuffer.clear()
            colorBuffer.clear()
            for (voxel in 0 until snapshot.count) {
                val sourceOffset = voxel * POSITION_COMPONENTS
                val x = snapshot.positions[sourceOffset]
                val y = snapshot.positions[sourceOffset + 1]
                val z = snapshot.positions[sourceOffset + 2]
                val color = snapshot.colors[voxel]
                repeat(VERTICES_PER_VOXEL) { corner ->
                    val cornerOffset = corner * POSITION_COMPONENTS
                    positionBuffer.put(x + CUBE_CORNERS[cornerOffset] * halfSize)
                    positionBuffer.put(y + CUBE_CORNERS[cornerOffset + 1] * halfSize)
                    positionBuffer.put(z + CUBE_CORNERS[cornerOffset + 2] * halfSize)
                    colorBuffer.put((color shr 16 and 0xFF).toByte())
                    colorBuffer.put((color shr 8 and 0xFF).toByte())
                    colorBuffer.put((color and 0xFF).toByte())
                    colorBuffer.put((color ushr 24 and 0xFF).toByte())
                }
            }
            positionBuffer.flip()
            colorBuffer.flip()
        }

        private fun consumed(uploadId: Long, callbackBit: Int) {
            if (destroyed || !uploadBusy || uploadId != activeUploadId) return
            if (consumedCallbackMask and callbackBit != 0) return
            consumedCallbackMask = consumedCallbackMask or callbackBit
            if (consumedCallbackMask == BOTH_CALLBACKS) {
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

    internal interface CoverageCubeVertexUploader {
        fun uploadPositions(buffer: FloatBuffer, elementCount: Int, onConsumed: () -> Unit)
        fun uploadColors(buffer: ByteBuffer, byteCount: Int, onConsumed: () -> Unit)
    }

    private class FilamentCoverageCubeVertexUploader(
        private val engine: Engine,
        private val vertexBuffer: VertexBuffer,
    ) : CoverageCubeVertexUploader {
        private val callbackHandler = Handler(Looper.getMainLooper())

        override fun uploadPositions(
            buffer: FloatBuffer,
            elementCount: Int,
            onConsumed: () -> Unit,
        ) {
            vertexBuffer.setBufferAt(
                engine,
                POSITION_BUFFER_INDEX,
                buffer,
                0,
                elementCount,
                callbackHandler,
                Runnable(onConsumed),
            )
        }

        override fun uploadColors(
            buffer: ByteBuffer,
            byteCount: Int,
            onConsumed: () -> Unit,
        ) {
            vertexBuffer.setBufferAt(
                engine,
                COLOR_BUFFER_INDEX,
                buffer,
                0,
                byteCount,
                callbackHandler,
                Runnable(onConsumed),
            )
        }
    }
}
