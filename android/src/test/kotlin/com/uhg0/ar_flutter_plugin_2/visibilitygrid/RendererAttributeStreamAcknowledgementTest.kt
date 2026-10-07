package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import com.uhg0.ar_flutter_plugin_2.sceneview.*
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.*
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class RendererAttributeStreamAcknowledgementTest {
    @Test fun `multi-page real style stream gates selected attributes on actual final empty exchange`() { proveFinalEmptyFence(true) }
    @Test fun `single-page real style stream gates selected attributes on actual final empty exchange`() { proveFinalEmptyFence(false) }
    private fun proveFinalEmptyFence(multiplePages: Boolean) {
        val messenger = MethodTestMessenger()
        val owner = NativeCoverageRendererOwner()
        owner.setControls(CoverageRendererControls(true, CoveragePresentationMode.PLANAR_PATCHES, CoverageRendererPalette.COVERAGE))
        val binding = VisibilityGridV2Binding(messenger, 2171, CommittedBaselineAuthority(), postToMain = { it() },
            coverageRendererOwner = owner, onRendererStyleCut = { cut ->
                val descriptor = PresentationDescriptor.createOwned(
                    CoverageRowsQualifier(cut.ownership.bindingGeneration, cut.ownership.groupGeneration, 1,
                        cut.transactionId, cut.geometryRevision, cut.styleRevision),
                    CoveragePresentationMode.PLANAR_PATCHES, true, 20000, cut.surfaceIds.size, cut.surfaceIds.size, CoverageRendererPalette.COVERAGE, 0,
                    selectedSurfaceIds = cut.surfaceIds.copyOf(), selectedSourceSlots = IntArray(cut.surfaceIds.size) { it }, styleRows = cut.styleRows.copyOf(),
                    update = null, workspace = PresentationWorkspace(20000),
                    pageReader = PresentationPageReader { _, _, _, _, _ -> true },
                    worldFromGroup = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }, voxelSizeMeters = 0.1f)
                owner.installPresentation(descriptor, PointCloudNativeConfig(voxelRenderMode = VoxelRenderMode.CENTROIDS,
                    rendererGeneration = 1))
                RendererStyleCutResult.Applied(cut.styleRevision, cut.targetRevision, cut.surfaceIds.size, cut.targetSurfaceId)
            })
        binding.configureRendererAttributeBudget { true }
        try {
            val before = binding.snapshot(); val qualifier = before.nativeStreamToken + before.workerBindingToken
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_2171")
            val start = ControlRequest(ControlOperation.START, 0, uuid(1), uuid(20), uuid(40), 3, 4, 5, 0,
                StartRequestCodecV2.defaultPayload())
            val result = RecordingResult()
            channel.invokeMethod("start", qualifier + ControlCodec.encodeRequest(start), result)
            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            val stream = ControlCodec.decodeResponse((result.successValue as ByteArray).copyOfRange(32,
                (result.successValue as ByteArray).size)).streamToken
            fun exchange(sequence: Long, acknowledged: Long, style: Long, command: ByteArray = byteArrayOf()): PacketCodec.Response {
                val reply = RecordingBinaryReply()
                messenger.send("visibility_surface_stream_2171", ByteBuffer.wrap(qualifier + PacketCodec.encodeRequest(
                    PacketCodec.Request(0, stream, acknowledged, acknowledged, acknowledged, style,
                        TransactionResponseProfileV1.ordinary.responseCeilingBytes, emptyList(), command, sequence))), reply)
                assertTrue(reply.completed.await(2, TimeUnit.SECONDS))
                return PacketCodec.decodeResponse(reply.bytes!!.copyOfRange(32, reply.bytes!!.size))
            }
            exchange(1, 0, 0); exchange(2, 0, 0); exchange(3, 1, 0)
            val cut = RendererStyleCutPayloadV1(uuid(40).bytes, binding.snapshot().bindingGeneration, 4,
                1, 1, 1, 1, 1, 1, 1, 1, true, longArrayOf(1, 2), ByteArray(32).also { rows ->
                    val row = CoverageRendererStyleRowV1().encode(); row.copyInto(rows); row.copyInto(rows, 16) })
            val pages = RendererStyleCommandV1.encodePages(cut, maxPageBytes = if (multiplePages) 200 else RendererStyleCommandV1.MAX_PAGE_BYTES)
            var sequence = 3L
            var applied: PacketCodec.Response? = null
            for (page in pages) applied = exchange(++sequence, 1, 1, page)
            assertEquals(1L, applied!!.acceptedStyleRevision)
            val request = RendererAttributeCodec.header(cut.canonicalBytes().copyOf(116), 1, 0, 1, 1, 0).encode()
            fun select(): Any? {
                val selected = RecordingResult(); channel.invokeMethod("rendererAttributesV1", qualifier + request, selected)
                assertTrue(selected.completed.await(2, TimeUnit.SECONDS)); return selected.successValue
            }
            assertEquals("staleCut", (select() as Map<*, *>)["outcome"])
            assertEquals(512L, owner.rendererAttributePortableBytes())
            val acknowledged = exchange(++sequence, 1, 1)
            assertEquals(1L, acknowledged.acceptedStyleRevision)
            val selected = select() as ByteArray
            assertArrayEquals(qualifier, selected.copyOfRange(0, 32))
            assertEquals(2, RendererAttributeCodec.decode(selected.copyOfRange(32, selected.size)).first.total)
            assertEquals(sequence + 1, acknowledged.nextExpectedRequestSequence)
        } finally { binding.dispose(); owner.dispose() }
    }
    private fun uuid(seed: Int) = Uuid(ByteArray(16) { (seed + it).toByte() }.also { it[6] = 0x40; it[8] = 0x80.toByte() })
}
