package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.RendererStyleCutPayloadV1
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RendererAttributeHostPathTest {
    @Test fun `native palette and canonical attribute digests match locked portable ABI`() {
        assertArrayEquals(intArrayOf(0xffff4545.toInt(), 0xffffbf3f.toInt(), 0xff44cc66.toInt(), 0xff999999.toInt()),
            intArrayOf(RendererAttributeCodec.ZERO_ARGB, RendererAttributeCodec.ONE_ARGB,
                RendererAttributeCodec.TWO_PLUS_ARGB, RendererAttributeCodec.UNAVAILABLE_ARGB))
        val style = ByteArray(116) { if (it < 16) 7 else 0 }
        val data = ByteBuffer.wrap(style).order(ByteOrder.LITTLE_ENDIAN)
        repeat(10) { data.putLong(16 + it * 8, 1) }
        data.putLong(100, -1).putInt(108, -1).putInt(112, 1)
        val header = RendererAttributeCodec.header(style, 1, 1, 2, 1, 1)
        assertEquals("bf25137c50651b8bae60ebec309c779b49247cc130685e5e0f4676ae14c73dd2",
            RendererAttributeCodec.digest(header, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1).array()).hex())
        val row = ByteArray(24); ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).putLong(1)
        row[10] = 200.toByte(); row[11] = 3
        assertEquals("76acc88be2d24ebbfce1f830107ef2d3e27edc8543793915efc41c1eb1573bf6",
            RendererAttributeCodec.digest(header, row).hex())
        val colors = CoverageDirectionalColors.copyOfObservationClasses(longArrayOf(1), longArrayOf(0b11100100),
            RendererAttributeCodec.ZERO_ARGB, RendererAttributeCodec.ONE_ARGB,
            RendererAttributeCodec.TWO_PLUS_ARGB, RendererAttributeCodec.UNAVAILABLE_ARGB)
        assertArrayEquals(intArrayOf(0xffff4545.toInt(), 0xffffbf3f.toInt(), 0xff44cc66.toInt(), 0xff999999.toInt()),
            IntArray(4) { colors.colorAt(0, it) })
    }

    @Test fun `two physical resource generations remain charged until exact drain including unknown submissions`() {
        val telemetry = RendererTelemetry()
        val budget = CoverageShapeResourceBudget({ telemetry.ownedBufferBytesSnapshot().toLong() },
            telemetry::setOwnedBufferBytes, telemetry::removeOwner)
        val first = CoverageResourceToken(1, 1, CoveragePresentationMode.DIRECTIONAL_BALLS)
        val second = first.copy(epoch = 2); val third = first.copy(epoch = 3)
        val bytes = CoverageShapeResourceBudget.bytes(first.mode, 256)
        assertEquals(256 * 3860 + 32768 + 512, bytes)
        assertEquals(256 * 500 + 32768 + 512,
            CoverageShapeResourceBudget.bytes(CoveragePresentationMode.PLANAR_PATCHES, 256))
        assertEquals(854_680L, RendererAttributeOwner.modeledAllowance(RendererAttributeCodec.PATCHES, 256))
        assertTrue(budget.reserve(first, bytes, 0, 0)); budget.attach(first, Any())
        assertTrue(budget.reserve(second, bytes, 0, 0)); budget.attach(second, Any())
        repeat(30) { assertFalse(budget.reserve(third, bytes, 0, 0)) }
        assertEquals(bytes * 2, telemetry.ownedBufferBytesSnapshot())
        budget.drained(first); budget.drained(first)
        assertTrue(budget.contains(second)); assertTrue(budget.reserve(third, bytes, 0, 0))
        assertEquals(2, budget.maximumGenerations)
        assertFalse(budget.reserve(first, bytes, CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES.toLong(), 0))
        budget.drained(second); budget.drained(third)
        assertEquals(0, telemetry.ownedBufferBytesSnapshot())
    }

    @Test fun `frozen shape window excludes late uploads reuse-only completion and late mounted frames`() {
        val window = CoverageShapeUploadMeasurement()
        window.completed(9, 1, 1, 2, 1, false)
        window.begin(10)
        window.frame(11, false); window.frame(12, true)
        window.completed(13, 1, 2, 2, 256, true)
        assertEquals(0L, window.snapshot()["shapeMeasurementUploadCompletions"])
        window.completed(14, 1, 3, 2, 256, false)
        window.freeze { 15 }
        val frozen = window.snapshot()
        window.frame(16, true); window.completed(17, 2, 4, 1, 4096, false)
        assertEquals(frozen, window.snapshot())
        assertEquals(1L, frozen["shapeMeasurementUploadCompletions"])
        assertEquals(1L, frozen["shapeMeasurementMetadataReuses"])
        assertEquals(2L, frozen["shapeMeasurementFrameCount"])
        assertEquals(1L, frozen["shapeMeasurementMountedFrameCount"])
        assertEquals(14L, frozen["shapeMeasurementLastCompletionNs"])
        assertEquals(true, frozen["shapeMeasurementFrameCountComplete"])
    }

    @Test fun `full descriptor path refuses low normal patches and suppresses observation meaning for alternative palettes`() {
        val fixture = Fixture(false, 3, normalConfidence = 63)
        fixture.publish(1, 0)
        assertEquals(0, fixture.reference().shapeCount)
        assertEquals(3, fixture.reference().pointCount)
        assertEquals(false, fixture.owner.rendererGeometryStatus()["basisAvailable"])
        fixture.owner.dispose()
        for (palette in listOf(CoverageRendererPalette.UNIFORM, CoverageRendererPalette.DIRECTION)) {
            val balls = Fixture(true, 2, palette = palette)
            balls.publish(1, 2); balls.uploadUntilComplete()
            assertEquals(false, balls.owner.rendererGeometryStatus()["rendererObservationColorsMeaningful"])
            assertEquals(2, balls.owner.rendererGeometryStatus()["shapeCount"])
            balls.owner.dispose()
        }
    }

    @Test fun `prepared bodies and delayed descriptors cannot hide a third full retained mesh`() {
        val fixture = Fixture(true, 2)
        fixture.publish(1, 0); val first = fixture.reference()
        fixture.publish(2, 1); val second = fixture.reference()
        fixture.publish(3, 2); val third = fixture.reference()
        assertFalse(first.available); assertFalse(second.available); assertTrue(third.available)
        assertTrue(fixture.owner.rendererAttributePortableBytes() < 4096)
        fixture.owner.invalidateRendererAttributes(1)
        assertFalse(third.available)
        assertEquals(false, fixture.owner.rendererGeometryStatus()["basisAvailable"])
        fixture.owner.dispose()
    }

    @Test fun `thirty alternating full host pairs complete current shapes during repeated exact style cuts`() {
        val started = System.nanoTime()
        var populated = 0
        var pages = 0
        repeat(30) { pair ->
            val balls = pair % 2 == 1
            val fixture = Fixture(balls, if (balls) 256 else 4096)
            // Source preparation is shared. Point control keeps the same
            // selected canonical population and carries no attribute bytes.
            fixture.owner.setControls(CoverageRendererControls(true, CoveragePresentationMode.SEMANTIC_CENTROIDS, CoverageRendererPalette.COVERAGE))
            assertEquals(fixture.rows, fixture.owner.presentationDescriptor()!!.count)
            assertEquals("semanticCentroids", fixture.owner.rendererGeometryStatus()["effectiveMode"])
            assertEquals(0L, fixture.owner.rendererAttributeSnapshot()["accepted"])
            fixture.owner.setControls(CoverageRendererControls(true, fixture.mode, CoverageRendererPalette.COVERAGE))
            fixture.bindShapeResource()
            fixture.owner.beginShapeMeasurementWindow()
            var revision = 0L
            repeat(180) { frame ->
                if (frame % 30 == 0) { revision++; fixture.publish(revision, minOf(2, revision.toInt() - 1)) }
                fixture.uploads.onFrame()
                val mounted = fixture.uploads.completedMesh != null
                fixture.owner.recordShapeDrawState(fixture.token, mounted)
                fixture.owner.recordShapeMeasurementFrame(System.nanoTime())
            }
            fixture.owner.endShapeMeasurementWindow()
            val status = fixture.owner.rendererGeometryStatus()
            val window = fixture.owner.shapeMeasurementSnapshot()
            assertEquals(true, status["basisAvailable"]); assertEquals(true, status["currentShapeResourceMounted"])
            assertEquals(if (balls) "directionalBalls" else "planarPatches", status["effectiveMode"])
            assertEquals(status["currentShapeResourceGeneration"], status["completedShapeUploadGeneration"])
            assertEquals(status["currentShapeSelectionGeneration"], status["completedShapeSelectionGeneration"])
            assertTrue((window["shapeMeasurementUploadCompletions"] as Long) > 0)
            assertTrue((window["shapeMeasurementMountedFrameCount"] as Long) > 0)
            assertTrue((window["shapeMeasurementMetadataReuses"] as Long) > 0)
            assertTrue((status["shapeCount"] as Int) > 0)
            assertTrue(fixture.uploads.maxUploadBytes <= 32768)
            populated++; pages += fixture.pages
            fixture.owner.dispose()
        }
        println("RENDERER_ATTRIBUTE_FULL_HOST pairs=$populated pages=$pages wallUs=${(System.nanoTime()-started)/1000} actualHeapMeasured=false")
    }

    @Test fun `exact descriptor replays preserve uploaded patches and balls across hide and show`() {
        for (balls in listOf(false, true)) {
            val fixture = Fixture(balls, 3)
            fixture.publish(1, 2)
            fixture.uploadUntilComplete()
            val reference = fixture.reference()
            val pages = fixture.pages
            repeat(5) { fixture.replay(1); fixture.uploadUntilComplete() }
            assertSame(reference, fixture.reference())
            assertEquals(pages, fixture.pages)
            assertEquals(fixture.mode.wireName, fixture.owner.rendererGeometryStatus()["effectiveMode"])
            fixture.owner.setControls(CoverageRendererControls(false, fixture.mode, fixture.palette))
            fixture.replay(1)
            assertSame(reference, fixture.reference())
            assertFalse(fixture.owner.presentationDescriptor()!!.enabled)
            fixture.owner.setControls(CoverageRendererControls(true, fixture.mode, fixture.palette))
            fixture.replay(1); fixture.uploadUntilComplete()
            assertSame(reference, fixture.reference())
            assertEquals(fixture.mode.wireName, fixture.owner.rendererGeometryStatus()["effectiveMode"])
            fixture.owner.dispose()
        }
    }

    @Test fun `newer descriptor cut cannot reuse an old qualified shape body`() {
        val fixture = Fixture(false, 3)
        fixture.publish(1, 2); fixture.uploadUntilComplete()
        fixture.replay(2)
        assertNull(fixture.owner.presentationDescriptor()?.shapes)
        assertEquals("rawFeatures", fixture.owner.rendererGeometryStatus()["effectiveMode"])
        fixture.owner.dispose()
    }

    @Test fun `shape replay refuses mismatched ownership revisions selection palette mode and coordinates`() {
        val fixture = Fixture(false, 3)
        fixture.publish(1, 2); fixture.uploadUntilComplete()
        val previous = checkNotNull(fixture.owner.presentationDescriptor())
        val plain = fixture.candidate(1)
        assertSame(previous.shapes, plain.withReplayAttributesFrom(previous).shapes)
        val q = previous.qualifier
        for (qualifier in listOf(q.copy(bindingGeneration = 2), q.copy(groupGeneration = 2),
            q.copy(rendererGeneration = 2), q.copy(transactionId = 2),
            q.copy(geometryRevision = 2), q.copy(styleRevision = 2),
            q.copy(transactionId = 0), q.copy(geometryRevision = 0))) {
            assertNull(fixture.candidate(1, qualifier = qualifier).withReplayAttributesFrom(previous).shapes)
        }
        for (candidate in listOf(
            fixture.candidate(1, modeOverride = CoveragePresentationMode.DIRECTIONAL_BALLS),
            fixture.candidate(1, paletteOverride = CoverageRendererPalette.UNIFORM),
            plain.withControls(mode = plain.mode, paletteEpoch = 1),
            fixture.candidate(1, world = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }.also { it[12] = 1.0 }),
            fixture.candidate(1, world = null),
            fixture.candidate(1, ids = longArrayOf(1, 2, 4)),
            fixture.candidate(1, ids = longArrayOf(1, 2)),
            fixture.candidate(1, cellSize = 0.2f))) {
            assertNull(candidate.withReplayAttributesFrom(previous).shapes)
        }
        assertSame(previous.shapes, fixture.owner.presentationDescriptor()?.shapes)
        fixture.owner.dispose()
    }

    @Test fun `attributed hide masks every primitive through renderer frames and delayed completion`() {
        for (balls in listOf(false, true)) {
            val fixture = Fixture(balls, 3, lowNormalRow = 2, deferUploads = true)
            fixture.publish(1, 1)
            val target = DrawTarget()
            val descriptor = fixture.owner.presentationDescriptor() as PresentationDescriptor
            val submittedMesh = fixture.reference()
            assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, null, true))
            assertArrayEquals(intArrayOf(0, 3, 0, 0, 0), target.ranges)
            assertTrue(target.layerVisible)
            fixture.uploads.onFrame()
            assertTrue(fixture.uploads.inFlight)
            fixture.owner.setControls(CoverageRendererControls(false, fixture.mode, fixture.palette))
            fixture.uploads.offer(null)
            repeat(2) {
                fixture.uploads.onFrame()
                assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor,
                    fixture.uploads.completedMesh, false))
                target.assertHidden()
            }
            fixture.completeDelayedUpload()
            assertFalse(fixture.uploads.inFlight)
            assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor,
                submittedMesh, false))
            target.assertHidden()
            fixture.owner.setControls(CoverageRendererControls(true, fixture.mode, fixture.palette))
            fixture.publish(2, 1)
            fixture.uploads.onFrame(); fixture.completeDelayedUpload()
            val current = fixture.owner.presentationDescriptor() as PresentationDescriptor
            val ready = checkNotNull(fixture.uploads.completedMesh)
            assertTrue(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, current, ready, true))
            assertTrue(target.layerVisible)
            assertArrayEquals(intArrayOf(3, 0, ready.indexCount, ready.indexCount, if (balls) 0 else 1), target.ranges)
            assertEquals(if (balls) 3 else 2, ready.shapeCount)
            fixture.uploads.close(); fixture.owner.dispose()
        }
    }

    @Test fun `old attributed generations and hidden mode changes cannot reopen drawable ranges`() {
        val fixture = Fixture(false, 3, lowNormalRow = 2)
        fixture.publish(1, 2); fixture.uploadUntilComplete()
        val target = DrawTarget()
        val oldToken = fixture.token
        val oldDescriptor = fixture.owner.presentationDescriptor() as PresentationDescriptor
        val oldMesh = checkNotNull(fixture.uploads.completedMesh)
        assertTrue(CoverageAttributedDraw.apply(target, fixture.owner, oldToken, oldDescriptor, oldMesh, true))
        fixture.owner.requestResourceReplacement()
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, oldToken, oldDescriptor, oldMesh, true))
        target.assertHidden()
        fixture.owner.setControls(CoverageRendererControls(false, CoveragePresentationMode.DIRECTIONAL_BALLS, fixture.palette))
        val successor = checkNotNull(fixture.owner.issueResourceToken())
        fixture.owner.markResourceMounted(successor)
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, successor,
            fixture.owner.presentationDescriptor() as PresentationDescriptor, oldMesh, true))
        target.assertHidden()
        fixture.owner.setControls(CoverageRendererControls(true, CoveragePresentationMode.DIRECTIONAL_BALLS, fixture.palette))
        val current = fixture.owner.presentationDescriptor() as PresentationDescriptor
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, oldToken, oldDescriptor, oldMesh, true))
        target.assertHidden()
        // A prior patch mesh is never interpreted as current ball geometry.
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, successor, current, oldMesh, true))
        assertTrue(target.layerVisible)
        assertArrayEquals(intArrayOf(0, current.count, 0, 0, 0), target.ranges)
        fixture.owner.dispose()
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, successor, current, oldMesh, true))
        target.assertHidden()
    }

    @Test fun `current draw adapter keeps genuine point fallback and rejects stale descriptor completion`() {
        val fixture = Fixture(false, 3, normalConfidence = 63)
        fixture.publish(1, 0); fixture.uploadUntilComplete()
        val target = DrawTarget()
        val descriptor = fixture.owner.presentationDescriptor() as PresentationDescriptor
        val ready = checkNotNull(fixture.uploads.completedMesh)
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready, true))
        assertTrue(target.layerVisible)
        assertArrayEquals(intArrayOf(3, 0, 0, 0, 3), target.ranges)
        fixture.publish(2, 2)
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready, true))
        target.assertHidden()
        val current = fixture.owner.presentationDescriptor() as PresentationDescriptor
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, current, ready, true))
        assertArrayEquals(intArrayOf(0, 3, 0, 0, 0), target.ranges)
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, current, ready, false))
        target.assertHidden()
        fixture.uploads.close(); fixture.owner.dispose()
    }

    @Test fun `ancestor hidden attributed draw retains own intent without ranges or shape credit`() {
        val fixture = Fixture(false, 3, lowNormalRow = 2)
        fixture.publish(1, 1); fixture.uploadUntilComplete()
        val descriptor = fixture.owner.presentationDescriptor() as PresentationDescriptor
        val ready = checkNotNull(fixture.uploads.completedMesh)
        val target = DrawTarget().also { it.ancestorVisible = false }
        fixture.owner.beginShapeMeasurementWindow()
        val drawn = CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready,
            true, renderableInstance = 1, parentVisible = false)
        fixture.owner.recordShapeDrawState(fixture.token, drawn)
        fixture.owner.recordShapeMeasurementFrame(System.nanoTime())
        assertFalse(drawn)
        assertTrue(target.ownVisible)
        target.assertHidden()
        fixture.owner.endShapeMeasurementWindow()
        assertEquals(0L, fixture.owner.shapeMeasurementSnapshot()["shapeMeasurementMountedFrameCount"])
        target.ancestorVisible = true
        // Hierarchy visibility alone cannot restore geometry before a qualified renderer frame.
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), target.ranges)
        assertTrue(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready,
            true, renderableInstance = 1, parentVisible = true))
        assertTrue(target.layerVisible)
        assertArrayEquals(intArrayOf(3, 0, ready.indexCount, ready.indexCount, 1), target.ranges)
        fixture.uploads.close(); fixture.owner.dispose()
    }

    @Test fun `missing attributed renderable has zero ranges until a qualified mount and cannot credit late stale ownership`() {
        val fixture = Fixture(true, 3)
        fixture.publish(1, 2); fixture.uploadUntilComplete()
        val descriptor = fixture.owner.presentationDescriptor() as PresentationDescriptor
        val ready = checkNotNull(fixture.uploads.completedMesh)
        val target = DrawTarget()
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready,
            true, renderableInstance = 0))
        assertTrue(target.ownVisible)
        target.assertHidden()
        assertTrue(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready,
            true, renderableInstance = 1))
        assertTrue(target.layerVisible)
        assertArrayEquals(intArrayOf(3, 0, ready.indexCount, ready.indexCount, 0), target.ranges)
        fixture.owner.dispose()
        assertFalse(CoverageAttributedDraw.apply(target, fixture.owner, fixture.token, descriptor, ready,
            true, renderableInstance = 1))
        target.assertHidden()
        assertFalse(target.ownVisible)
        fixture.uploads.close()
    }

    private class DrawTarget : CoverageAttributedDrawTarget {
        var ownVisible = false
        var ancestorVisible = true
        private var renderablePresent = false
        val layerVisible: Boolean get() = ownVisible && ancestorVisible && renderablePresent
        var ranges = intArrayOf(0, 0, 0, 0, 0)
        override fun applyDrawState(pointStart: Int, pointCount: Int, triangleCount: Int,
            fallbackStart: Int, fallbackCount: Int, visibleIntent: Boolean,
            renderableInstance: Int): Boolean {
            ranges = intArrayOf(pointStart, pointCount, triangleCount, fallbackStart, fallbackCount)
            ownVisible = visibleIntent
            renderablePresent = renderableInstance != 0
            return layerVisible
        }
        fun assertHidden() {
            assertFalse(layerVisible)
            assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), ranges)
        }
    }

    @Test fun `rejected attribute candidate preserves completed shapes across equivalent descriptor replay`() {
        val fixture = Fixture(false, 3, lowNormalRow = 2)
        fixture.publish(1, 1); fixture.uploadUntilComplete()
        val before = fixture.reference()
        val token = fixture.token
        val generation = fixture.owner.rendererGeometryStatus()["completedShapeSelectionGeneration"]
        val target = DrawTarget()
        var replayed = false
        fixture.afterPageRead = {
            fixture.afterPageRead = null
            fixture.replay(1)
            replayed = true
        }
        fixture.publish(1, 1, expectedCommit = "staleCut")
        assertTrue(replayed)
        assertTrue(before.available)
        assertSame(before, fixture.reference())
        assertSame(before, fixture.uploads.completedMesh)
        assertEquals(token, fixture.owner.currentResourceToken())
        assertEquals(generation, fixture.owner.rendererGeometryStatus()["completedShapeSelectionGeneration"])
        assertEquals(1L, fixture.owner.rendererAttributeSnapshot()["accepted"])
        assertTrue(CoverageAttributedDraw.apply(target, fixture.owner, token,
            fixture.owner.presentationDescriptor() as PresentationDescriptor, before, true))
        assertTrue(target.layerVisible)
        assertArrayEquals(intArrayOf(3, 0, before.indexCount, before.indexCount, 1), target.ranges)
        fixture.uploads.close(); fixture.owner.dispose()
    }

    private class Fixture(val balls: Boolean, val rows: Int, val normalConfidence: Int = 200,
        val palette: CoverageRendererPalette = CoverageRendererPalette.COVERAGE,
        val lowNormalRow: Int = -1, val deferUploads: Boolean = false) {
        val owner = NativeCoverageRendererOwner()
        var style = header(1)
        val mode = if (balls) CoveragePresentationMode.DIRECTIONAL_BALLS else CoveragePresentationMode.PLANAR_PATCHES
        var token: CoverageResourceToken
        var pages = 0
        var afterPageRead: (() -> Unit)? = null
        private var pendingCompletion: CoverageShapePageCompletion? = null
        private var pendingTicket = 0L
        val uploads: CoverageShapeUploadOwner = CoverageShapeUploadOwner(CoverageShapePageUploader { _, _, _, _, _, _, ticket, done ->
            pages++
            if (deferUploads) { pendingCompletion = done; pendingTicket = ticket } else done.complete(ticket)
        }, { owner.presentationDescriptor()?.shapes === it && it.available },
            { owner.recordCompletedShapeUpload(token, it, uploadsReuse()) }, {})
        private fun uploadsReuse(): Boolean = uploads.completionWasReuse
        init {
            owner.setControls(CoverageRendererControls(true, mode, palette))
            install(1)
            owner.configureRendererAttributes(1, { style }, { true })
            token = checkNotNull(owner.issueResourceToken()); owner.recordShapeResourceCreated(token); owner.markResourceMounted(token)
        }
        fun reference() = checkNotNull(owner.presentationDescriptor()?.shapes)
        fun bindShapeResource() {
            owner.recordShapeResourceUnmounted(token)
            token = checkNotNull(owner.issueResourceToken())
            owner.recordShapeResourceCreated(token); owner.markResourceMounted(token)
        }
        private fun header(revision: Long): ByteArray = RendererStyleCutPayloadV1(ByteArray(16) { 7 },
            1, 1, 1, 1, 1, revision, revision, revision, revision, revision, true,
            LongArray(rows) { it + 1L }, ByteArray(rows * 16)).canonicalBytes().copyOf(116)
        fun replay(revision: Long) {
            install(revision)
            owner.presentationDescriptor()?.shapes?.let(uploads::offer)
        }
        private fun install(revision: Long) {
            owner.installPresentation(candidate(revision), PointCloudNativeConfig(enabled = true,
                voxelRenderMode = VoxelRenderMode.CENTROIDS, rendererGeneration = 1))
        }
        fun candidate(revision: Long,
            qualifier: CoverageRowsQualifier = CoverageRowsQualifier(1, 1, 1, 1, 1, revision),
            modeOverride: CoveragePresentationMode = mode,
            paletteOverride: CoverageRendererPalette = palette,
            world: DoubleArray? = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 },
            ids: LongArray = LongArray(rows) { it + 1L },
            cellSize: Float = 0.1f,
        ): PresentationDescriptor {
            val row = CoverageRendererStyleRowV1(palette = paletteOverride).encode()
            val styles = ByteArray(ids.size * 16).also { target -> repeat(ids.size) { row.copyInto(target, it * 16) } }
            return PresentationDescriptor.createOwned(qualifier,
                modeOverride, true, 20000, rows, rows, paletteOverride, 0,
                selectedSurfaceIds = ids, selectedSourceSlots = IntArray(ids.size) { it }, styleRows = styles,
                update = null, workspace = PresentationWorkspace(20000),
                pageReader = PresentationPageReader { _, first, count, mapping, page ->
                    repeat(count) { n -> page.surfaceIds[n] = mapping.surfaceIdAt(first+n)
                        page.positions[n*3] = (first+n)%20 * 0.1f; page.positions[n*3+1] = (first+n)/20 * 0.1f
                        page.positions[n*3+2] = -2f }; afterPageRead?.invoke(); true },
                worldFromGroup = world, voxelSizeMeters = cellSize)
        }
        fun publish(revision: Long, observationClass: Int, expectedCommit: String = "accepted") {
            style = header(revision); install(revision)
            val request = RendererAttributeCodec.header(style, 1, 0, if (balls) 2 else 1,
                if (palette == CoverageRendererPalette.DIRECTION) 2 else 1, 0)
            val first = owner.exchangeRendererAttributes(request.encode()) as ByteArray
            val selected = RendererAttributeCodec.decode(first).first
            val ids = LongArray(selected.total)
            repeat(selected.pageCount) { index ->
                val raw = if (index == 0) first else owner.exchangeRendererAttributes(selected.page(1, index, 0, 0, 0).encode()) as ByteArray
                val page = RendererAttributeCodec.decode(raw).first; val data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                repeat(page.count) { ids[page.first+it] = data.getLong(256+it*8) }
            }
            val bytes = ByteArray(ids.size * 24); val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            ids.forEachIndexed { index, id -> data.putLong(index*24,id).putShort(index*24+8,0)
                bytes[index*24+10] = (if (index == lowNormalRow) 63 else normalConfidence).toByte(); bytes[index*24+11] = 3
                bytes[index*24+12] = observationClass.toByte() }
            val digest = RendererAttributeCodec.digest(selected, bytes)
            val pageCount = maxOf(1, (ids.size+667)/668)
            repeat(pageCount) { index -> val begin = index*668; val count = minOf(668,ids.size-begin)
                assertEquals("accepted", (owner.exchangeRendererAttributes(selected.page(2,index,pageCount,begin,count,digest)
                    .encode(bytes,begin*24,count*24)) as Map<*,*>)["outcome"]) }
            assertEquals(expectedCommit, (owner.exchangeRendererAttributes(selected.page(3,0,0,0,0,digest).encode()) as Map<*,*>)["outcome"])
            uploads.offer(reference())
        }
        fun uploadUntilComplete() { repeat(128) { uploads.onFrame() }; assertNotNull(uploads.completedMesh) }
        fun completeDelayedUpload() {
            val completion = checkNotNull(pendingCompletion)
            pendingCompletion = null
            completion.complete(pendingTicket)
        }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
