package com.uhg0.ar_flutter_plugin_2.sceneview

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.github.sceneview.node.Node

/** Points for unselected rows, plus qualified triangles and selected point fallbacks. */
internal class CoverageAttributedMeshResources(
    private val engine: Engine,
    override val capacity: Int,
    shape: CoveragePresentationMode,
    val selectedCapacity: Int,
    private val token: CoverageResourceToken,
    private val owner: NativeCoverageRendererOwner,
    private val telemetry: RendererTelemetry,
    private val resourceBudget: CoverageShapeResourceBudget,
) : CoverageVoxelMeshResources, CoverageAttributedDrawTarget {
    private val points = CoveragePointMeshResources(engine, capacity, telemetry, "coverage-attributed-points-${token.epoch}")
    private val balls = shape == CoveragePresentationMode.DIRECTIONAL_BALLS
    private val shapeCapacity = selectedCapacity.also { require(it in 1..if (balls) 256 else 4096) }
    val shapeVertexBuffer: VertexBuffer = VertexBuffer.Builder().bufferCount(1)
        .vertexCount(shapeCapacity * (if (balls) 127 else CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH + 1))
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 16)
        .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.UBYTE4, 12, 16)
        .normalized(VertexBuffer.VertexAttribute.COLOR).build(engine)
    val shapeIndexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(shapeCapacity * (if (balls) 457 else CoveragePlanarPatchGeometry.MAX_INDICES_PER_PATCH + 1))
        .bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
    override val vertexBuffer: VertexBuffer get() = points.vertexBuffer
    override val indexBuffer: IndexBuffer get() = points.indexBuffer
    override val primitiveType = RenderableManager.PrimitiveType.POINTS
    private var descriptor: PresentationDescriptor? = null
    private var node: Node? = null
    private var closed = false
    private var destroyed = false
    private var drawEnabled = false
    private var onReleased: () -> Unit = {}
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var submittedTicket = 0L
    private var submittedCompletion: CoverageShapePageCompletion? = null
    private var verticesDone = false
    private var indicesDone = false
    private val vertexConsumed = Runnable { verticesDone = true; completeSubmittedPage() }
    private val indicesConsumed = Runnable { indicesDone = true; completeSubmittedPage() }
    private val pageUploader = object : CoverageShapePageUploader {
        override fun upload(vertices: java.nio.ByteBuffer, vertexStart: Int, vertexCount: Int,
            indices: java.nio.ByteBuffer, indexStart: Int, indexCount: Int,
            ticket: Long, completed: CoverageShapePageCompletion) {
            check(submittedCompletion == null)
            submittedTicket = ticket; submittedCompletion = completed
            verticesDone = vertexCount == 0; indicesDone = indexCount == 0
            val bytes = vertexCount * 16 + indexCount * 4
            check(bytes <= CoverageShapeResourceBudget.PAGE_BYTES)
            telemetry.recordUpload(bytes)
            // Both lanes are reserved before the first attempt. A throwing
            // attempt is unknown; its callback remains necessary for drain.
            if (vertexCount > 0) shapeVertexBuffer.setBufferAt(engine, 0, vertices,
                vertexStart * 16, vertexCount * 16, callbackHandler, vertexConsumed)
            if (indexCount > 0) shapeIndexBuffer.setBuffer(engine, indices,
                indexStart * 4, indexCount * 4, callbackHandler, indicesConsumed)
            completeSubmittedPage()
        }
    }
    private val uploads = CoverageShapeUploadOwner(
        uploader = pageUploader,
        current = { mesh -> !closed && owner.acceptsResourceToken(token) &&
            owner.presentationDescriptor()?.shapes === mesh },
        onCompleted = ::shapeUploadCompleted,
        onPageReleased = { if (closed) finishDestroy() else onReleased() },
    )

    init { resourceBudget.attach(token, this); owner.recordShapeResourceCreated(token) }

    private fun shapeUploadCompleted(mesh: PreparedCoverageShapeReference) {
        owner.recordCompletedShapeUpload(token, mesh, uploads.completionWasReuse)
        applyDrawRanges()
    }

    private fun completeSubmittedPage() {
        if (!verticesDone || !indicesDone) return
        val completion = submittedCompletion ?: return
        val ticket = submittedTicket
        submittedCompletion = null
        completion.complete(ticket)
    }

    fun updateDescriptor(node: Node, descriptor: PresentationDescriptor, material: MaterialInstance, pointSize: Float) {
        this.node = node; this.descriptor = descriptor
        drawEnabled = descriptor.enabled
        points.updateDescriptor(node, descriptor, material, pointSize)
        uploads.offer(descriptor.shapes.takeIf { descriptor.enabled })
        applyDrawRanges()
    }

    private fun applyDrawRanges() {
        val node = node
        val instance = if (node == null) 0 else engine.renderableManager.getInstance(node.entity)
        val shapeVisible = CoverageAttributedDraw.apply(
            this, owner, token, descriptor, uploads.completedMesh, !closed && drawEnabled,
            instance, node?.parent?.isVisible != false,
        )
        owner.recordShapeDrawState(token, shapeVisible)
    }

    override fun applyDrawState(pointStart: Int, pointCount: Int, triangleCount: Int,
        fallbackStart: Int, fallbackCount: Int, visibleIntent: Boolean,
        renderableInstance: Int): Boolean {
        val node = node ?: return false
        val manager = engine.renderableManager
        val instance = renderableInstance
        if (instance == 0) return false
        manager.setGeometryAt(instance, 0, primitiveType, vertexBuffer, indexBuffer, pointStart, pointCount)
        manager.setGeometryAt(instance, 1, RenderableManager.PrimitiveType.TRIANGLES,
            shapeVertexBuffer, shapeIndexBuffer, 0, triangleCount)
        manager.setGeometryAt(instance, 2, RenderableManager.PrimitiveType.POINTS,
            shapeVertexBuffer, shapeIndexBuffer, fallbackStart, fallbackCount)
        node.isVisible = visibleIntent
        return node.isVisible
    }

    override fun update(node: Node, snapshot: CoveragePointRenderSnapshot, materialInstance: MaterialInstance, pointSizePx: Float) =
        points.update(node, snapshot, materialInstance, pointSizePx)
    override fun hide(node: Node) {
        drawEnabled = false; owner.recordShapeDrawState(token, false)
        points.hide(node); this.node = node; uploads.offer(null); applyDrawRanges()
    }
    override fun requireRetainedSnapshotUpload() { points.requireRetainedSnapshotUpload(); uploads.offer(null) }
    override fun onRendererFrame() {
        if (closed) return
        val current = owner.presentationDescriptor()
        if (current !== descriptor) {
            descriptor = current; drawEnabled = current?.enabled == true; uploads.offer(current?.shapes.takeIf { current?.enabled == true })
        }
        points.onRendererFrame(); uploads.onFrame(); applyDrawRanges()
    }
    override fun setOnUploadPageReleased(listener: () -> Unit) { onReleased = listener; points.setOnUploadPageReleased(listener) }
    override fun destroy() {
        if (closed) return
        closed = true; onReleased = {}; uploads.close(); points.destroy()
        owner.recordShapeResourceUnmounted(token)
        descriptor = null; node = null
        if (!uploads.inFlight) finishDestroy()
    }
    private fun finishDestroy() {
        if (destroyed || !closed || uploads.inFlight) return
        destroyed = true
        engine.destroyVertexBuffer(shapeVertexBuffer); engine.destroyIndexBuffer(shapeIndexBuffer)
        resourceBudget.drained(token)
    }
}
