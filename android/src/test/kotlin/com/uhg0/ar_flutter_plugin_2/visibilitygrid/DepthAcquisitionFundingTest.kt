package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlCodec
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlOperation
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlRequest
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.PacketCodec
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.StartRequestCodecV2
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.TransactionResponseProfileV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.Uuid
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthAcquisitionFundingTest {
    @Test
    fun `underfunded native profile refuses acquisition without a canonical mutation`() {
        val fixture = Fixture(rendererOwnerBytes = 64L * 1024L * 1024L)
        try {
            val owner = fixture.startAndAcknowledge()
            val completed = CountDownLatch(1)
            var result: DepthAcquisitionPreparationResult? = null
            fixture.integration.prepareDepthAcquisition(DepthAcquisitionFunding.from(owner, false)) { result = it; completed.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(DepthAcquisitionPreparationResult.REFUSED, result)
            assertEquals(0L, fixture.integration.integrationReceipt().committed)
            assertFalse(fixture.integration.hasPendingPublicationForIngress())
            assertEquals(1_024L, fixture.integration.portableOwnerMemoryReceipt().depthAcquisitionIdentityReservationBytes)
        } finally { fixture.close() }
    }

    @Test
    fun `live bootstrap reserves depth identity on existing worker before image acquisition`() {
        val fixture = Fixture()
        try {
            val owner = fixture.startAndAcknowledge()
            val completed = CountDownLatch(1)
            val caller = Thread.currentThread().id
            var funded = false
            var callbackThread = caller
            fixture.integration.prepareDepthAcquisition(DepthAcquisitionFunding.from(owner, false)) {
                funded = it == DepthAcquisitionPreparationResult.FUNDED; callbackThread = Thread.currentThread().id; completed.countDown()
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(funded)
            assertNotEquals(caller, callbackThread)
            assertEquals(1_024L, fixture.integration.portableOwnerMemoryReceipt().depthAcquisitionIdentityReservationBytes)
            assertEquals(0L, fixture.integration.integrationReceipt().committed)
            assertFalse(fixture.integration.hasPendingPublicationForIngress())
            assertSame(owner, fixture.binding.currentObservationOwnership())
        } finally { fixture.close() }
    }

    @Test
    fun `blocked mapper worker cannot block frame funding or publish through newer pause`() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = AndroidVisibilityGridRuntime(fixture.binding::currentObservationOwnership, fixture.integration)
        try {
            val owner = fixture.startAndAcknowledge()
            fixture.worker.execute { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val frameCaller = Executors.newSingleThreadExecutor()
            try {
                assertFalse(frameCaller.submit<Boolean> { runtime.prepareDepthAcquisition(owner) }.get(500, TimeUnit.MILLISECONDS))
                assertEquals(1L, release.count)
            } finally { frameCaller.shutdownNow() }
            runtime.fencePause()
            release.countDown()
            fixture.worker.submit {}.get(2, TimeUnit.SECONDS)
            assertFalse(runtime.prepareDepthAcquisition(owner))
        } finally { release.countDown(); runtime.close(); fixture.close() }
    }

    @Test
    fun `same owner retries failed renderer rebuild funding before admitting a fresh estimate`() {
        val fixture = Fixture(rendererRebuildFailures = 2)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val runtime = AndroidVisibilityGridRuntime(fixture.binding::currentObservationOwnership, fixture.integration,
            scheduler = scheduler, ownsScheduler = true)
        try {
            val owner = fixture.startAndAcknowledge()
            runtime.setDepthCapability(VisibilityDepthCapability.RAW_DEPTH)
            fun prepareAndSettle() {
                assertFalse(runtime.prepareDepthAcquisition(owner))
                fixture.worker.submit {}.get(500, TimeUnit.MILLISECONDS)
                assertSame(owner, fixture.binding.currentObservationOwnership())
            }
            // The real session-memory mapper opens, then retains a failed rebuild.
            prepareAndSettle()
            assertEquals("rendererRebuildPending", fixture.integration.integrationReceipt().status)
            assertEquals(1, fixture.rebuildAttempts)
            assertFalse(runtime.offerDepth(depth(owner), 0))
            assertEquals(0L, runtime.snapshot().freshEstimateAdmissions)
            assertEquals(0L, fixture.integration.snapshot().admittedDepths)
            // Another genuine renderer failure remains retryable for this same owner.
            prepareAndSettle()
            assertEquals(2, fixture.rebuildAttempts)
            assertEquals(0, fixture.rebuildCompletions)
            assertFalse(runtime.offerDepth(depth(owner), 0))
            // ensureOpened deliberately defers admission on the recovery turn.
            prepareAndSettle()
            assertEquals("rendererRebuildRecovered", fixture.integration.integrationReceipt().status)
            assertEquals(3, fixture.rebuildAttempts)
            assertEquals(1, fixture.rebuildCompletions)
            assertFalse(runtime.offerDepth(depth(owner), 0))
            prepareAndSettle()
            assertTrue(runtime.prepareDepthAcquisition(owner))
            assertEquals(3, fixture.rebuildAttempts)
            assertTrue(runtime.offerDepth(depth(owner), 0))
            scheduler.submit {}.get(500, TimeUnit.MILLISECONDS)
            assertEquals(1L, runtime.snapshot().freshEstimateAdmissions)
            assertEquals(0L, runtime.snapshot().duplicateEstimateRefusals)
            assertEquals(1L, fixture.integration.snapshot().admittedDepths)
            assertEquals(0L, runtime.snapshot().residentPayloadBytes)
            assertSame(owner, fixture.binding.currentObservationOwnership())
            assertEquals(1_024L, fixture.integration.portableOwnerMemoryReceipt().depthAcquisitionIdentityReservationBytes)
            assertEquals(736L, DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES)
            assertTrue(DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES <= DepthAcquisitionPortableMemory.RESERVATION_BYTES)
        } finally { runtime.close(); fixture.close() }
    }

    private fun depth(owner: VisibilityObservationOwnership): VisibilityDepthObservation {
        val samples = listOf(VisibilityDepthSample(0, 0, 1_000, 255))
        return VisibilityDepthObservation(ownership = owner,
            frame = VisibilityObservationFrame(VisibilityObservationSource.ARCORE_RAW_DEPTH, 200, 200, 100,
                "arcore-rear-camera", true, "landscape_right_x_right_y_down_v1",
                VisibilityCameraPose.copyOf(identityVisibilityGridTransform()),
                VisibilityCameraIntrinsics(4, 3, 2.0, 2.0, 1.5, 1.0), VisibilityDepthCapability.RAW_DEPTH),
            samples = samples, sourceRejectedSamples = 0,
            payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES + VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
            acquisitionMode = DepthAcquisitionMode.RAW)
    }

    private class Fixture(rendererOwnerBytes: Long = 0L, rendererRebuildFailures: Int = 0) : AutoCloseable {
        val directory = Files.createTempDirectory("depth-acquisition-funding").toFile()
        val coordinator = StorageBudgetCoordinatorV2(File(directory, "canonical"),
            StorageBudgetPolicyV2(64L * 1024L * 1024L, 0),
            JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L })) { 128L * 1024L * 1024L }
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2178, CommittedBaselineAuthority(), postToMain = { it() })
        val worker = Executors.newSingleThreadExecutor()
        var rebuildAttempts = 0
        var rebuildCompletions = 0
        val integration = VisibilityGridIntegration(binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = { CanonicalRuntimeResources.openLive(directory, it, coordinator) },
            renderer = object : CommittedRendererProjection by CommittedRendererProjection.NONE {
                override val maximumRows = 100_000
                override fun portableOwnerBytes() = rendererOwnerBytes
                override fun beginRebuild(cut: CommittedGeometryCut) {
                    rebuildAttempts++
                    if (rebuildAttempts <= rendererRebuildFailures) throw IllegalStateException("injected renderer rebuild failure")
                }
                override fun finishRebuild(cut: CommittedGeometryCut) { rebuildCompletions++ }
            },
            executor = worker, ownsExecutor = false)

        fun startAndAcknowledge(): VisibilityObservationOwnership {
            val snapshot = binding.snapshot()
            val qualifier = snapshot.nativeStreamToken + snapshot.workerBindingToken
            val started = RecordingResult()
            val request = ControlRequest(ControlOperation.START, 0, uuid(1), uuid(20), uuid(40), 3, 4, 5, 0,
                StartRequestCodecV2.defaultPayload())
            MethodChannel(messenger, "visibility_grid_v2_control_2178").invokeMethod("start",
                qualifier + ControlCodec.encodeRequest(request), started)
            assertTrue(started.completed.await(2, TimeUnit.SECONDS))
            val response = ControlCodec.decodeResponse((started.successValue as ByteArray).copyOfRange(qualifier.size,
                (started.successValue as ByteArray).size))
            assertEquals(0, response.outcome)
            for (sequence in 1L..3L) {
                val acknowledged = if (sequence == 3L) 1L else 0L
                val reply = RecordingBinaryReply()
                messenger.send("visibility_surface_stream_2178", ByteBuffer.wrap(qualifier + PacketCodec.encodeRequest(
                    PacketCodec.Request(0, response.streamToken, acknowledged, acknowledged, acknowledged, 0,
                        TransactionResponseProfileV1.ordinary.responseCeilingBytes, emptyList(), byteArrayOf(), sequence))), reply)
                assertTrue(reply.completed.await(2, TimeUnit.SECONDS))
                assertEquals(0, PacketCodec.decodeResponse(requireNotNull(reply.bytes).copyOfRange(qualifier.size,
                    requireNotNull(reply.bytes).size)).errorId)
            }
            return requireNotNull(binding.currentObservationOwnership())
        }

        override fun close() { integration.close(); worker.shutdownNow(); binding.dispose(); coordinator.close(); directory.deleteRecursively() }
        private fun uuid(seed: Int): Uuid {
            val bytes = ByteArray(16) { (seed + it).toByte() }
            bytes[6] = 0x40; bytes[8] = 0x80.toByte()
            return Uuid(bytes)
        }
    }
}
