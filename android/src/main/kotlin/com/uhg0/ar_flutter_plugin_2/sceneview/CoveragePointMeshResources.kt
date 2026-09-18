package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointUploadQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.uploadQualifier
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

    private var lastUploadQualifier: CoveragePointUploadQualifier? = null
    private var retainedSnapshotUploadRequired = true
    private var pendingDescriptor: BoundedCoveragePresentation? = null
    private var descriptorCursor = 0
    private var descriptorReset = true
    private var descriptorPageInFlight = false
    private var destroyed = false
    @Volatile private var onUploadPageReleased: () -> Unit = {}
    private val uploadCoordinator = CoveragePointUploadCoordinator(
        capacity = capacity,
        uploader = FilamentCoveragePointVertexUploader(engine, vertexBuffer),
        onUploadAttributed = { bytes, origin -> telemetry?.recordUpload(bytes, origin) },
        onResourceResetScheduled = { telemetry?.recordResourceResetScheduled() },
        onUploadCallbackAttributed = { origin -> telemetry?.recordUploadCallback(origin) },
        onUploadCompletedAttributed = { elapsedNanos, origin ->
            telemetry?.recordUploadCompletion(elapsedNanos, origin)
        },
        onDestroyedUploadCallback = {
            telemetry?.recordFencedDestroyedUploadCallback()
        },
        onUploadPageReleased = {
            descriptorPageInFlight = false
            onUploadPageReleased()
        },
    )
    private val allocationLedger = telemetry?.let(::CoverageRendererAllocationLedger)
    private var indexStaging: java.nio.IntBuffer? = null

    override val primitiveType: RenderableManager.PrimitiveType =
        RenderableManager.PrimitiveType.POINTS

    init {
        // The direct index staging remains live until Filament invokes this
        // upload callback, so it is separately charged for the startup peak.
        allocationLedger?.installPointResources(telemetryOwner, capacity)
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
        ) {
            indexStaging = null
            allocationLedger?.completePointStartup(telemetryOwner)
        }
    }

    override fun update(
        node: Node,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    ) {
        // The native visibility renderer already admits the active mode's
        // bounded stable rows. Re-selecting here would retain a second full
        // primitive selector and duplicate the presentation hand-off.
        val presentation = snapshot
        check(presentation.capacity == capacity)
        check(presentation.count in 0..capacity)
        check(presentation.positions.size == presentation.count * POSITION_COMPONENTS)
        check(presentation.colors.size == presentation.count)
        val uploadQualifier = presentation.uploadQualifier()
        if (uploadQualifier != lastUploadQualifier || retainedSnapshotUploadRequired) {
            if (presentation.count > 0) {
                if (retainedSnapshotUploadRequired) {
                    uploadCoordinator.submitForResourceGeneration(presentation)
                } else {
                    uploadCoordinator.submit(presentation)
                }
            }
            lastUploadQualifier = uploadQualifier
            retainedSnapshotUploadRequired = false
        }
        setDrawCount(
            node,
            if (presentation.enabled) presentation.count else 0,
        )
        materialInstance.setParameter("pointSize", pointSizePx)
        node.isVisible = presentation.enabled && presentation.count > 0
    }

    override fun updatePage(
        node: Node,
        page: CoveragePresentationPage,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
        reset: Boolean,
    ) {
        uploadCoordinator.submitPage(
            page,
            RendererUploadPageOrigin.RESOURCE_GENERATION_RESET.takeIf { reset }
                ?: RendererUploadPageOrigin.ORDINARY,
        )
        descriptorPageInFlight = true
        setDrawCount(node, if (page.totalCount > 0) page.totalCount else 0)
        materialInstance.setParameter("pointSize", pointSizePx)
        node.isVisible = page.totalCount > 0
    }

    internal fun updateDescriptor(
        node: Node,
        descriptor: BoundedCoveragePresentation,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    ) {
        currentNodeForDescriptor = node
        currentMaterialForDescriptor = materialInstance
        currentPointSizeForDescriptor = pointSizePx
        pendingDescriptor = descriptor
        descriptorCursor = 0
        descriptorReset = true
        descriptorPageInFlight = false
        setDrawCount(node, descriptor.count)
        materialInstance.setParameter("pointSize", pointSizePx)
        node.isVisible = descriptor.enabled && descriptor.count > 0
    }

    private fun queueNextDescriptorPage() {
        val descriptor = pendingDescriptor ?: return
        if (descriptorCursor >= descriptor.count) return
        val start = descriptorCursor
        if (!descriptor.withPage(descriptor.qualifier, start, 512) { page ->
                updatePage(
                    node = checkNotNull(currentNodeForDescriptor),
                    page = page,
                    materialInstance = checkNotNull(currentMaterialForDescriptor),
                    pointSizePx = currentPointSizeForDescriptor,
                    reset = descriptorReset,
                )
                descriptorCursor += page.count
                descriptorReset = false
            }
        ) {
            pendingDescriptor = null
        }
    }

    // These short-lived fields exist only while queueNextDescriptorPage runs;
    // they avoid retaining a mesh/node in the descriptor itself.
    private var currentNodeForDescriptor: Node? = null
    private var currentMaterialForDescriptor: MaterialInstance? = null
    private var currentPointSizeForDescriptor = 0f

    override fun hide(node: Node) {
        setDrawCount(node, 0)
        node.isVisible = false
    }

    override fun requireRetainedSnapshotUpload() {
        retainedSnapshotUploadRequired = true
    }

    override fun onRendererFrame() {
        uploadCoordinator.onRendererFrame()
        if (!descriptorPageInFlight) queueNextDescriptorPage()
    }

    override fun setOnUploadPageReleased(listener: () -> Unit) {
        onUploadPageReleased = listener
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
        onUploadPageReleased = {}
        uploadCoordinator.destroy()
        indexStaging = null
        pendingDescriptor = null
        descriptorPageInFlight = false
        currentNodeForDescriptor = null
        currentMaterialForDescriptor = null
        allocationLedger?.releasePointResources(telemetryOwner)
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
        const val STEADY_OWNED_BYTES_PER_ROW =
            POSITION_COMPONENTS * Float.SIZE_BYTES + COLOR_COMPONENTS +
                Int.SIZE_BYTES + POSITION_COMPONENTS * Float.SIZE_BYTES + COLOR_COMPONENTS
        const val STARTUP_INDEX_STAGING_BYTES_PER_ROW = Int.SIZE_BYTES
        const val PEAK_OWNED_BYTES_PER_ROW =
            STEADY_OWNED_BYTES_PER_ROW + STARTUP_INDEX_STAGING_BYTES_PER_ROW

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

    /**
     * Completes submission of this bounded paired page to Filament's command
     * stream. The ownership callbacks remain the only release/completion
     * signal; this fence merely guarantees the driver has consumed the queued
     * transfer commands instead of waiting on an incidental later draw.
     */
    fun completeSubmissionFence() = Unit
}

internal class CoveragePointUploadCoordinator(
    capacity: Int,
    private val uploader: CoveragePointVertexUploader,
    private val onUploadSubmitted: (Int) -> Unit = {},
    private val onUploadAttributed: (Int, RendererUploadPageOrigin) -> Unit = { _, _ -> },
    private val onResourceResetScheduled: () -> Unit = {},
    private val onUploadCallback: () -> Unit = {},
    private val onUploadCallbackAttributed: (RendererUploadPageOrigin) -> Unit = {},
    private val onUploadCompleted: (Long) -> Unit = {},
    private val onUploadCompletedAttributed: (Long, RendererUploadPageOrigin) -> Unit = { _, _ -> },
    private val onDestroyedUploadCallback: () -> Unit = {},
    private val onUploadPageReleased: () -> Unit = {},
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val buffers = CoveragePointUploadBuffers(capacity)
    private var uploadBusy = false
    private var consumedCallbackMask = 0
    private var activeUploadId = 0L
    private data class PendingUpload(
        val snapshot: CoveragePointRenderSnapshot,
        val origin: RendererUploadPageOrigin,
    )
    private data class PendingPage(
        val page: CoveragePresentationPage,
        val origin: RendererUploadPageOrigin,
    )

    private val pendingUploads = ArrayDeque<PendingUpload>()
    private val pendingPages = ArrayDeque<PendingPage>()
    private var hasUploadedSnapshot = false
    private var destroyed = false
    private var activeUploadStartedNanos = 0L
    private var activeSnapshot: CoveragePointRenderSnapshot? = null
    private var activePage: CoveragePresentationPage? = null
    private var activeOrigin = RendererUploadPageOrigin.ORDINARY
    private val pendingRanges = ArrayDeque<UploadRange>()
    fun submit(snapshot: CoveragePointRenderSnapshot) {
        enqueue(snapshot, RendererUploadPageOrigin.ORDINARY)
    }

    fun submitPage(page: CoveragePresentationPage, origin: RendererUploadPageOrigin) {
        if (destroyed) return
        pendingPages.clear()
        pendingUploads.clear()
        if (uploadBusy) pendingRanges.clear()
        pendingPages.addLast(PendingPage(page, origin))
    }

    private fun enqueue(
        snapshot: CoveragePointRenderSnapshot,
        origin: RendererUploadPageOrigin,
    ) {
        if (destroyed) return
        val normalized =
            if ((uploadBusy || pendingRanges.isNotEmpty() || pendingUploads.isNotEmpty()) &&
                snapshot.update?.reset == false
            ) {
                snapshot.copy(update = snapshot.update.copy(reset = true))
            } else {
                snapshot
            }
        if (origin == RendererUploadPageOrigin.ORDINARY &&
            pendingUploads.lastOrNull()?.origin == RendererUploadPageOrigin.ORDINARY
        ) {
            pendingUploads.removeLast()
        }
        pendingUploads.addLast(PendingUpload(normalized, origin))
        // Do not mutate the direct buffers while Filament still owns the
        // current range. Once its two callbacks return, a newer revision
        // supersedes every remaining chunk from the old snapshot.
        if (uploadBusy) pendingRanges.clear()
    }

    /** Rehydrates a new Filament resource generation from the retained cut. */
    fun submitForResourceGeneration(snapshot: CoveragePointRenderSnapshot) {
        if (destroyed) return
        onResourceResetScheduled()
        enqueue(
            snapshot.copy(update = snapshot.update?.copy(reset = true)),
            RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
        )
    }

    fun stagingBuffers(): CoveragePointUploadBuffers = buffers

    fun onRendererFrame() = drain()

    fun destroy() {
        destroyed = true
        pendingUploads.clear()
        pendingPages.clear()
        activeSnapshot = null
        activePage = null
        pendingRanges.clear()
    }

    private fun drain() {
        if (destroyed || uploadBusy) return
        if (pendingRanges.isEmpty()) {
            val pendingPage = pendingPages.removeFirstOrNull()
            if (pendingPage != null) {
                activePage = pendingPage.page
                activeOrigin = pendingPage.origin
                pendingRanges.addLast(
                    UploadRange(
                        pendingPage.page.startSlot,
                        pendingPage.page.startSlot + pendingPage.page.count,
                    ),
                )
            } else {
                val pending = pendingUploads.removeFirstOrNull() ?: return
                val snapshot = pending.snapshot
                val update = snapshot.update
                val spans = update?.spans.orEmpty()
                val fullUpload = !hasUploadedSnapshot || update == null || update.reset
                if (!fullUpload && spans.isEmpty()) return
                activeSnapshot = snapshot
                activeOrigin = pending.origin
                pendingRanges.addAll(
                    uploadRanges(
                        count = snapshot.count,
                        fullUpload = fullUpload,
                        spans = spans,
                    ),
                )
                hasUploadedSnapshot = true
            }
        }
        val range = pendingRanges.removeFirstOrNull() ?: return
        val startSlot = range.startSlot
        val endSlot = range.endSlotExclusive
        val page = activePage
        if (page != null) {
            buffers.writePage(page.positions, page.colors, page.startSlot)
        } else {
            val snapshot = checkNotNull(activeSnapshot)
            buffers.writeRange(snapshot.positions, snapshot.colors, startSlot, endSlot)
        }
        onUploadSubmitted(
            (endSlot - startSlot) *
                (CoveragePointMeshResources.POSITION_COMPONENTS * Float.SIZE_BYTES +
                    CoveragePointMeshResources.COLOR_COMPONENTS),
        )
        onUploadAttributed(
            (endSlot - startSlot) *
                (CoveragePointMeshResources.POSITION_COMPONENTS * Float.SIZE_BYTES +
                    CoveragePointMeshResources.COLOR_COMPONENTS),
            activeOrigin,
        )
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
        // setBufferAt queues transfer work. Complete this <=64KiB paired page
        // so Filament can dispatch its actual ownership callbacks without
        // depending on an incidental later scene draw.
        uploader.completeSubmissionFence()
    }

    private fun consumed(uploadId: Long, callbackBit: Int) {
        if (destroyed) {
            onDestroyedUploadCallback()
            return
        }
        if (!uploadBusy || uploadId != activeUploadId) return
        if (consumedCallbackMask and callbackBit != 0) return
        onUploadCallback()
        onUploadCallbackAttributed(activeOrigin)
        consumedCallbackMask = consumedCallbackMask or callbackBit
        if (consumedCallbackMask == BOTH_CALLBACKS) {
            val elapsedNanos = (clockNanos() - activeUploadStartedNanos).coerceAtLeast(0L)
            onUploadCompleted(elapsedNanos)
            onUploadCompletedAttributed(
                elapsedNanos,
                activeOrigin,
            )
            uploadBusy = false
            if (pendingRanges.isEmpty()) {
                activeSnapshot = null
                activePage = null
            }
            onUploadPageReleased()
            // The next page is admitted only by onRendererFrame.
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
                    UploadRange(span.startSlot, span.endSlotExclusive)
                }
            }
        return buildList {
            sourceRanges.forEach { range ->
                var start = range.startSlot
                while (start < range.endSlotExclusive) {
                    val end = minOf(start + MAX_ROWS_PER_UPLOAD, range.endSlotExclusive)
                    add(UploadRange(start, end))
                    start = end
                }
            }
        }
    }

    private data class UploadRange(val startSlot: Int, val endSlotExclusive: Int)

    private companion object {
        const val MAX_ROWS_PER_UPLOAD =
            RendererTelemetry.ORDINARY_UPLOAD_LIMIT_BYTES /
                (CoveragePointMeshResources.POSITION_COMPONENTS * Float.SIZE_BYTES +
                    CoveragePointMeshResources.COLOR_COMPONENTS)
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

    override fun completeSubmissionFence() {
        engine.flushAndWait()
    }
}
