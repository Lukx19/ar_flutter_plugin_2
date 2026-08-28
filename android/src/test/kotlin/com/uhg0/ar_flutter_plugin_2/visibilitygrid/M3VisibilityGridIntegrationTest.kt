package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlOperation
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlRequest
import com.uhg0.ar_flutter_plugin_2.m0.M0aPacketCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aStartRequestCodecV2
import com.uhg0.ar_flutter_plugin_2.m0.M0aTransactionResponseProfileV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aUuid
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks Option A's M1-ACK seeded CREATE cut without a Flutter payload seam. */
class M3VisibilityGridIntegrationTest {
    @Test
    fun `real binding publishes adjacent CREATE with exact replay and same renderer cut`() {
        val directory = Files.createTempDirectory("m3-runtime-integration").toFile()
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2106, M0aCommittedBaselineAuthority(), postToMain = { it() })
        val rendered = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot>()
        val integration = M3VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            renderer = M3NativeRendererProjection(
                render = { snapshot, _ -> snapshot?.let(rendered::add) },
            ),
        )
        try {
            val stream = start(binding, messenger, 2106)
            assertEquals(2, exchange(messenger, 2106, stream, 1, 0, 0, 0).first.messageKind)
            assertEquals(4, exchange(messenger, 2106, stream, 2, 0, 0, 0).first.messageKind)
            assertEquals(0, exchange(messenger, 2106, stream, 3, 1, 1, 1).first.messageKind)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitFeature(feature(cut, 10))
            val staged = integration.integrationReceipt()
            assertEquals("pendingAck", staged.status)
            assertEquals(2, staged.transactionId)
            assertEquals(2, staged.geometryRevision)
            assertEquals(1, staged.lineageRevision)
            assertEquals(1, rendered.size)
            assertEquals(2L, rendered.single().update?.geometryRevision)
            assertEquals(staged.rendererRows, rendered.single().count)

            val first = exchange(messenger, 2106, stream, 4, 1, 1, 1)
            val replay = exchange(messenger, 2106, stream, 4, 1, 1, 1)
            assertEquals(2, first.first.messageKind)
            assertArrayEquals(first.second, replay.second)
            assertEquals(3, exchange(messenger, 2106, stream, 5, 1, 1, 1).first.messageKind)
            assertEquals(4, exchange(messenger, 2106, stream, 6, 1, 1, 1).first.messageKind)
            assertEquals(0, exchange(messenger, 2106, stream, 7, 2, 2, 1).first.messageKind)
            await { integration.integrationReceipt().status == "acknowledged" }
            assertTrue(integration.integrationReceipt()::class.java.declaredFields.none { it.type == ByteArray::class.java })
            assertEquals(null, integration.snapshot().lastReceipt)
        } finally {
            integration.close(); binding.dispose(); directory.deleteRecursively()
        }
    }

    @Test
    fun `pause replacement and close fence feature publication`() {
        val directory = Files.createTempDirectory("m3-runtime-pause").toFile()
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2107, M0aCommittedBaselineAuthority(), postToMain = { it() })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val integration = M3VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            beforeAdmission = { entered.countDown(); release.await(2, TimeUnit.SECONDS) },
        )
        try {
            val stream = start(binding, messenger, 2107)
            exchange(messenger, 2107, stream, 1, 0, 0, 0)
            exchange(messenger, 2107, stream, 2, 0, 0, 0)
            exchange(messenger, 2107, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())
            val admitted = Thread { integration.admitFeature(feature(cut, 11)) }.apply { start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val pausing = Thread(integration::pause).apply { start() }
            release.countDown()
            admitted.join(2_000); pausing.join(2_000)
            assertEquals("seeded", integration.integrationReceipt().status)
            assertEquals(0, exchange(messenger, 2107, stream, 4, 1, 1, 1).first.messageKind)

            integration.resume()
            binding.dispose()
            integration.admitFeature(feature(cut, 12))
            assertTrue(integration.integrationReceipt().transactionId < 2)

            val beforeClose = integration.integrationReceipt()
            integration.close()
            integration.admitFeature(feature(cut, 13))
            assertEquals(beforeClose, integration.integrationReceipt())
        } finally {
            integration.close(); binding.dispose(); directory.deleteRecursively()
        }
    }

    @Test
    fun `seeded M1 empty baseline makes first atomic CREATE adjacent and lineage-stable`() {
        val baseline = M3CommittedEmptyBaseline("binding:group:7:9", "group", 1, 1, 1)
        val opened = M3SurfaceOwnership.inMemory(
            M3SurfaceGroup("group"),
            M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline),
        ) as M3SurfaceOwnershipOpenResult.Opened
        val result = opened.ownership.transact(
            M3CanonicalTransactionCommand(
                commandId = "create-after-m1-ack",
                kind = M3CanonicalOperation.CREATE,
                expectedGeometryRevision = 1,
                expectedLineageRevision = 1,
                sourceIds = emptyList(),
                targets = listOf(M3CanonicalTarget(voxel = M3Voxel(1, 2, 3), normalOctX = 1, normalOctY = 1, normalConfidence = 200)),
            ),
        ) as M3CanonicalTransactionResult.Accepted
        assertEquals(2, result.receipt.geometryRevision)
        assertEquals(1, result.receipt.lineageRevision)
        assertTrue(result.receipt.lineageEdges.isEmpty())
        assertEquals(1, result.targets.size)
    }

    @Test
    fun `seed identity is durable before CREATE and mismatched reopen fails closed`() {
        val directory = Files.createTempDirectory("m3-seeded-baseline").toFile()
        try {
            val baseline = M3CommittedEmptyBaseline("binding-a", "group", 1, 1, 1)
            val configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val first = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
                as M3SurfaceOwnershipOpenResult.Opened
            first.ownership.close()

            val reopened = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
            assertTrue(reopened is M3SurfaceOwnershipOpenResult.Opened)
            (reopened as M3SurfaceOwnershipOpenResult.Opened).ownership.close()

            val mismatch = baseline.copy(bindingIdentity = "binding-b")
            val refused = M3SurfaceOwnership.open(
                M3SurfaceGroup("group"),
                directory,
                M3SurfaceOwnershipConfiguration(seededEmptyBaseline = mismatch),
            ) as M3SurfaceOwnershipOpenResult.Refused
            assertEquals(M3SurfaceOwnershipRestoreRefusal.FORK, refused.reason)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `committed CREATE reopens with byte exact current receipt`() {
        val directory = Files.createTempDirectory("m3-create-current-delta").toFile()
        try {
            val baseline = M3CommittedEmptyBaseline("binding", "group", 1, 1, 1)
            val configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val first = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
                as M3SurfaceOwnershipOpenResult.Opened
            val accepted = first.ownership.transact(
                M3CanonicalTransactionCommand(
                    "create", M3CanonicalOperation.CREATE, 1, 1, emptyList(),
                    listOf(
                        M3CanonicalTarget(
                            voxel = M3Voxel(3, 2, 1),
                            normalOctX = 1,
                            normalOctY = 1,
                            normalConfidence = 200,
                        ),
                    ),
                ),
            ) as M3CanonicalTransactionResult.Accepted
            first.ownership.close()

            val reopened = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
                as M3SurfaceOwnershipOpenResult.Opened
            val restored = requireNotNull(reopened.ownership.currentCanonicalTransaction())
            assertEquals(2, restored.receipt.geometryRevision)
            assertEquals(1, restored.receipt.lineageRevision)
            assertArrayEquals(
                accepted.receipt.canonicalBytes.toByteArray(),
                restored.receipt.canonicalBytes.toByteArray(),
            )
            reopened.ownership.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Stream(
        val qualifier: ByteArray,
        val token: Long,
    )

    private fun start(binding: VisibilityGridV2Binding, messenger: MethodTestMessenger, viewId: Int): Stream {
        val snapshot = binding.snapshot()
        val qualifier = snapshot.nativeStreamToken + snapshot.workerBindingToken
        val result = RecordingResult()
        MethodChannel(messenger, "visibility_grid_v2_control_$viewId").invokeMethod(
            "start", qualifier + M0aControlCodec.encodeRequest(startRequest()), result,
        )
        assertTrue(result.completed.await(2, TimeUnit.SECONDS))
        val response = M0aControlCodec.decodeResponse(strip(result.successValue as ByteArray, qualifier))
        assertEquals(0, response.outcome)
        return Stream(qualifier, response.streamToken)
    }

    private fun exchange(
        messenger: MethodTestMessenger,
        viewId: Int,
        stream: Stream,
        sequence: Long,
        transaction: Long,
        geometry: Long,
        lineage: Long,
    ): Pair<M0aPacketCodec.Response, ByteArray> {
        val request = stream.qualifier + M0aPacketCodec.encodeRequest(
            M0aPacketCodec.Request(
                0, stream.token, transaction, geometry, lineage, 0,
                M0aTransactionResponseProfileV1.ordinary.responseCeilingBytes,
                emptyList(), byteArrayOf(), sequence,
            ),
        )
        val reply = RecordingBinaryReply()
        messenger.send("visibility_surface_stream_$viewId", ByteBuffer.wrap(request), reply)
        assertTrue(reply.completed.await(2, TimeUnit.SECONDS))
        val raw = strip(requireNotNull(reply.bytes), stream.qualifier)
        return M0aPacketCodec.decodeResponse(raw) to raw
    }

    private fun feature(cut: VisibilityObservationOwnership, timestamp: Long): VisibilityFeatureObservation {
        val samples = VisibilityFeatureObservation.copySamples(
            listOf(VisibilityFeatureSample(7, 0.12, 0.02, 0.02, 1.0)),
        )
        return VisibilityFeatureObservation(
            ownership = cut,
            frame = VisibilityObservationFrame(
                VisibilityObservationSource.SYNTHETIC_FEATURE, timestamp, timestamp, timestamp,
                "synthetic-camera", true, "landscape_right_x_right_y_down_v1",
                VisibilityCameraPose.copyOf(identityVisibilityGridTransform()),
                VisibilityCameraIntrinsics(16, 12, 10.0, 10.0, 8.0, 6.0),
                VisibilityDepthCapability.UNSUPPORTED,
            ),
            samples = samples,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                samples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
        )
    }

    private fun startRequest() = M0aControlRequest(
        M0aControlOperation.START, 0, uuid(1), uuid(20), uuid(40), 3, 4, 5, 0,
        M0aStartRequestCodecV2.defaultPayload(),
    )

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40; bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }

    private fun strip(bytes: ByteArray, qualifier: ByteArray): ByteArray {
        assertArrayEquals(qualifier, bytes.copyOfRange(0, qualifier.size))
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return else Thread.sleep(5) }
        assertTrue(condition())
    }
}
