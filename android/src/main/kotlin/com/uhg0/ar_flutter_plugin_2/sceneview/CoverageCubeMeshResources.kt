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
import io.github.sceneview.node.Node
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
    private val telemetry: RendererTelemetry? = null,
    private val telemetryOwner: String = "coverage-cubes",
) : CoverageVoxelMeshResources {
    private val halfSize = voxelSizeMeters / 2f
    private val vertexCapacity = capacity * VERTICES_PER_VOXEL
    private val indexCapacity = capacity * INDICES_PER_VOXEL
    private val outlineIndexCapacity = capacity * OUTLINE_INDICES_PER_VOXEL

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

    val outlineIndexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(outlineIndexCapacity)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    override val primitiveType: RenderableManager.PrimitiveType =
        RenderableManager.PrimitiveType.TRIANGLES

    private val uploadCoordinator = CoverageCubeUploadCoordinator(
        capacity = capacity,
        halfSize = halfSize,
        uploader = FilamentCoverageCubeVertexUploader(engine, vertexBuffer),
        onUploadSubmitted = { bytes -> telemetry?.recordUpload(bytes) },
        onUploadCallback = { telemetry?.recordUploadCallback() },
        onUploadCompleted = { elapsedNanos -> telemetry?.recordUploadCompletion(elapsedNanos) },
    )
    private var indexStaging: java.nio.IntBuffer? = null
    private var outlineIndexStaging: java.nio.IntBuffer? = null
    private val allocationLedger = telemetry?.let(::CoverageRendererAllocationLedger)
    private var lastRevision = Long.MIN_VALUE
    private var retainedSnapshotUploadRequired = true
    private var destroyed = false
    private val presentationSelector = CoveragePresentationSelector(capacity)

    init {
        // The two direct index buffers remain live until their independent
        // Filament callbacks. Account for them as startup owners rather than
        // folding a transient allocation into the steady mesh owner.
        allocationLedger?.installCubeResources(telemetryOwner, capacity)
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
        ) {
            indexStaging = null
            allocationLedger?.completeCubeTriangleStartup(telemetryOwner)
        }

        val outlineIndices = ByteBuffer
            .allocateDirect(outlineIndexCapacity * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        repeat(capacity) { voxel ->
            val vertexOffset = voxel * VERTICES_PER_VOXEL
            CUBE_OUTLINE_INDICES.forEach { index ->
                outlineIndices.put(vertexOffset + index)
            }
        }
        outlineIndices.flip()
        outlineIndexStaging = outlineIndices
        outlineIndexBuffer.setBuffer(
            engine,
            outlineIndices,
            0,
            outlineIndexCapacity,
            Handler(Looper.getMainLooper()),
        ) {
            outlineIndexStaging = null
            allocationLedger?.completeCubeOutlineStartup(telemetryOwner)
        }
    }

    override fun update(
        node: Node,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    ) {
        val presentation = presentationSelector.select(snapshot)
        check(presentation.capacity == capacity)
        check(presentation.count in 0..capacity)
        check(presentation.positions.size == presentation.count * POSITION_COMPONENTS)
        check(presentation.colors.size == presentation.count)
        if (presentation.revision != lastRevision || retainedSnapshotUploadRequired) {
            if (presentation.count > 0) {
                if (retainedSnapshotUploadRequired) {
                    uploadCoordinator.submitForResourceGeneration(presentation)
                } else {
                    uploadCoordinator.submit(presentation)
                }
            }
            lastRevision = presentation.revision
            retainedSnapshotUploadRequired = false
        }
        setDrawCount(
            node,
            if (presentation.enabled) presentation.count else 0,
        )
        node.isVisible = presentation.enabled && presentation.count > 0
    }

    override fun hide(node: Node) {
        setDrawCount(node, 0)
        node.isVisible = false
    }

    override fun requireRetainedSnapshotUpload() {
        retainedSnapshotUploadRequired = true
    }

    override fun onRendererFrame() = uploadCoordinator.onRendererFrame()

    private fun setDrawCount(node: Node, voxelCount: Int) {
        val instance = engine.renderableManager.getInstance(node.entity)
        engine.renderableManager.setGeometryAt(
            instance,
            0,
            primitiveType,
            vertexBuffer,
            indexBuffer,
            0,
            voxelCount * INDICES_PER_VOXEL,
        )
        engine.renderableManager.setGeometryAt(
            instance,
            OUTLINE_PRIMITIVE_INDEX,
            RenderableManager.PrimitiveType.LINES,
            vertexBuffer,
            outlineIndexBuffer,
            0,
            voxelCount * OUTLINE_INDICES_PER_VOXEL,
        )
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        uploadCoordinator.destroy()
        indexStaging = null
        outlineIndexStaging = null
        allocationLedger?.releaseCubeResources(telemetryOwner)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyIndexBuffer(outlineIndexBuffer)
    }

    internal companion object {
        const val POSITION_BUFFER_INDEX = 0
        const val COLOR_BUFFER_INDEX = 1
        const val POSITION_COMPONENTS = 3
        const val COLOR_COMPONENTS = 4
        const val VERTICES_PER_VOXEL = 8
        const val INDICES_PER_VOXEL = 36
        const val OUTLINE_INDICES_PER_VOXEL = 24
        const val OUTLINE_PRIMITIVE_INDEX = 1
        const val STEADY_OWNED_BYTES_PER_VOXEL = 496
        const val TRIANGLE_INDEX_STAGING_BYTES_PER_VOXEL = INDICES_PER_VOXEL * Int.SIZE_BYTES
        const val OUTLINE_INDEX_STAGING_BYTES_PER_VOXEL =
            OUTLINE_INDICES_PER_VOXEL * Int.SIZE_BYTES
        const val PEAK_OWNED_BYTES_PER_VOXEL =
            STEADY_OWNED_BYTES_PER_VOXEL +
                TRIANGLE_INDEX_STAGING_BYTES_PER_VOXEL +
                OUTLINE_INDEX_STAGING_BYTES_PER_VOXEL

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

        // Twelve cube edges, expressed as line-segment vertex pairs.
        private val CUBE_OUTLINE_INDICES = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 0,
            4, 5, 5, 6, 6, 7, 7, 4,
            0, 4, 1, 5, 2, 6, 3, 7,
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

        internal fun cubeOutlineIndices(): IntArray =
            CUBE_OUTLINE_INDICES.copyOf()
    }

    internal class CoverageCubeUploadCoordinator(
        capacity: Int,
        private val halfSize: Float,
        private val uploader: CoverageCubeVertexUploader,
        private val onUploadSubmitted: (Int) -> Unit = {},
        private val onUploadCallback: () -> Unit = {},
        private val onUploadCompleted: (Long) -> Unit = {},
        private val clockNanos: () -> Long = System::nanoTime,
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
        private var activeUploadStartedNanos = 0L
        private var activeSnapshot: CoveragePointRenderSnapshot? = null
        private val pendingRanges = ArrayDeque<UploadRange>()
        // A completed reset establishes the mesh baseline. Afterwards a
        // coalesced ordinary revision can retain its exact dirty spans.
        private var hasUploadedSnapshot = false
        private var activeFullUpload = false
        // A page is work for an actual renderer frame. Construction and data
        // callbacks never create an implicit frame budget.
        private var frameAvailable = false

        fun submit(snapshot: CoveragePointRenderSnapshot) {
            if (destroyed) return
            pendingSnapshot =
                if (!hasUploadedSnapshot &&
                    (uploadBusy || pendingRanges.isNotEmpty() || pendingSnapshot != null) &&
                    snapshot.update?.reset == false
                ) {
                    snapshot.copy(update = snapshot.update.copy(reset = true))
                } else {
                    snapshot
                }
            if (uploadBusy) pendingRanges.clear()
            drain()
        }

        /** Rehydrates a new Filament resource generation from the retained cut. */
        fun submitForResourceGeneration(snapshot: CoveragePointRenderSnapshot) {
            submit(snapshot.copy(update = snapshot.update?.copy(reset = true)))
        }

        fun destroy() {
            destroyed = true
            pendingSnapshot = null
            activeSnapshot = null
            pendingRanges.clear()
            activeFullUpload = false
        }

        fun onRendererFrame() {
            frameAvailable = true
            drain()
        }

        private fun drain() {
            if (destroyed || uploadBusy || !frameAvailable) return
            if (pendingRanges.isEmpty()) {
                val snapshot = pendingSnapshot ?: return
                pendingSnapshot = null
                val update = snapshot.update
                val fullUpload = !hasUploadedSnapshot || update == null || update.reset
                val spans = update?.spans.orEmpty()
                if (!fullUpload && spans.isEmpty()) return
                activeSnapshot = snapshot
                activeFullUpload = fullUpload
                pendingRanges.addAll(
                    uploadRanges(
                        count = snapshot.count,
                        fullUpload = fullUpload,
                        spans = spans,
                    ),
                )
            }
            val snapshot = checkNotNull(activeSnapshot)
            val range = pendingRanges.removeFirstOrNull() ?: return
            frameAvailable = false
            writeRange(snapshot, range.startSlot, range.endSlotExclusive)
            uploadBusy = true
            consumedCallbackMask = 0
            activeUploadStartedNanos = clockNanos()
            val uploadId = ++activeUploadId
            val startVertex = range.startSlot * VERTICES_PER_VOXEL
            val vertexCount =
                (range.endSlotExclusive - range.startSlot) * VERTICES_PER_VOXEL
            onUploadSubmitted(
                vertexCount * (POSITION_COMPONENTS * Float.SIZE_BYTES + COLOR_COMPONENTS),
            )
            uploader.uploadPositions(
                positionBuffer,
                startVertex * POSITION_COMPONENTS * Float.SIZE_BYTES,
                vertexCount * POSITION_COMPONENTS,
            ) {
                consumed(uploadId, POSITION_CALLBACK)
            }
            uploader.uploadColors(
                colorBuffer,
                startVertex * COLOR_COMPONENTS,
                vertexCount * COLOR_COMPONENTS,
            ) {
                consumed(uploadId, COLOR_CALLBACK)
            }
        }

        private fun writeRange(
            snapshot: CoveragePointRenderSnapshot,
            startSlot: Int,
            endSlotExclusive: Int,
        ) {
            val firstPosition = startSlot * VERTICES_PER_VOXEL * POSITION_COMPONENTS
            val lastPosition =
                endSlotExclusive * VERTICES_PER_VOXEL * POSITION_COMPONENTS
            val positionTarget = positionBuffer.duplicate()
            positionTarget.clear()
            positionTarget.position(firstPosition)
            val firstColor = startSlot * VERTICES_PER_VOXEL * COLOR_COMPONENTS
            val lastColor = endSlotExclusive * VERTICES_PER_VOXEL * COLOR_COMPONENTS
            val colorTarget = colorBuffer.duplicate()
            colorTarget.clear()
            colorTarget.position(firstColor)
            for (voxel in startSlot until endSlotExclusive) {
                val sourceOffset = voxel * POSITION_COMPONENTS
                val x = snapshot.positions[sourceOffset]
                val y = snapshot.positions[sourceOffset + 1]
                val z = snapshot.positions[sourceOffset + 2]
                val color = snapshot.colors[voxel]
                val rotation = snapshot.gridRotationWorld
                repeat(VERTICES_PER_VOXEL) { corner ->
                    val cornerOffset = corner * POSITION_COMPONENTS
                    val localX = CUBE_CORNERS[cornerOffset] * halfSize
                    val localY = CUBE_CORNERS[cornerOffset + 1] * halfSize
                    val localZ = CUBE_CORNERS[cornerOffset + 2] * halfSize
                    positionTarget.put(
                        x + rotation[0] * localX +
                            rotation[3] * localY +
                            rotation[6] * localZ,
                    )
                    positionTarget.put(
                        y + rotation[1] * localX +
                            rotation[4] * localY +
                            rotation[7] * localZ,
                    )
                    positionTarget.put(
                        z + rotation[2] * localX +
                            rotation[5] * localY +
                            rotation[8] * localZ,
                    )
                    colorTarget.put((color shr 16 and 0xFF).toByte())
                    colorTarget.put((color shr 8 and 0xFF).toByte())
                    colorTarget.put((color and 0xFF).toByte())
                    colorTarget.put((color ushr 24 and 0xFF).toByte())
                }
            }
            positionBuffer.clear()
            positionBuffer.position(firstPosition)
            positionBuffer.limit(lastPosition)
            colorBuffer.clear()
            colorBuffer.position(firstColor)
            colorBuffer.limit(lastColor)
        }

        private fun consumed(uploadId: Long, callbackBit: Int) {
            if (destroyed || !uploadBusy || uploadId != activeUploadId) return
            if (consumedCallbackMask and callbackBit != 0) return
            onUploadCallback()
            consumedCallbackMask = consumedCallbackMask or callbackBit
            if (consumedCallbackMask == BOTH_CALLBACKS) {
                onUploadCompleted((clockNanos() - activeUploadStartedNanos).coerceAtLeast(0L))
                uploadBusy = false
                if (pendingRanges.isEmpty()) {
                    if (activeFullUpload) hasUploadedSnapshot = true
                    activeSnapshot = null
                }
                // A completed callback only releases the page. The next page
                // is admitted by a distinct rendered frame.
            }
        }

        private fun uploadRanges(
            count: Int,
            fullUpload: Boolean,
            spans: List<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan>,
        ): List<UploadRange> {
            val sourceRanges =
                if (fullUpload) {
                    listOf(UploadRange(0, count))
                } else {
                    spans.map { span ->
                        UploadRange(span.startSlot, span.startSlot + span.colors.size)
                    }
                }
            return buildList {
                sourceRanges.forEach { range ->
                    var start = range.startSlot
                    while (start < range.endSlotExclusive) {
                        val end = minOf(start + MAX_VOXELS_PER_UPLOAD, range.endSlotExclusive)
                        add(UploadRange(start, end))
                        start = end
                    }
                }
            }
        }

        private data class UploadRange(val startSlot: Int, val endSlotExclusive: Int)

        private companion object {
            const val MAX_VOXELS_PER_UPLOAD =
                RendererTelemetry.ORDINARY_UPLOAD_LIMIT_BYTES /
                    (VERTICES_PER_VOXEL *
                        (POSITION_COMPONENTS * Float.SIZE_BYTES + COLOR_COMPONENTS))
            const val POSITION_CALLBACK = 1
            const val COLOR_CALLBACK = 2
            const val BOTH_CALLBACKS = POSITION_CALLBACK or COLOR_CALLBACK
        }
    }

    internal interface CoverageCubeVertexUploader {
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

    private class FilamentCoverageCubeVertexUploader(
        private val engine: Engine,
        private val vertexBuffer: VertexBuffer,
    ) : CoverageCubeVertexUploader {
        private val callbackHandler = Handler(Looper.getMainLooper())

        override fun uploadPositions(
            buffer: FloatBuffer,
            destOffsetBytes: Int,
            elementCount: Int,
            onConsumed: () -> Unit,
        ) {
            vertexBuffer.setBufferAt(
                engine,
                POSITION_BUFFER_INDEX,
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
                COLOR_BUFFER_INDEX,
                buffer,
                destOffsetBytes,
                byteCount,
                callbackHandler,
                Runnable(onConsumed),
            )
        }
    }
}
