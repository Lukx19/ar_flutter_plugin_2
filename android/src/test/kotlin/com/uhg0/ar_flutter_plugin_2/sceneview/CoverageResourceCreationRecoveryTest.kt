package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.InvocationTargetException

class CoverageResourceCreationRecoveryTest {
    private class Scenario(private val alwaysFail: Boolean = false) {
        val owner = NativeCoverageRendererOwner()
        val recovery = CoverageResourceCreationRecovery()
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        var resourceOwner: String? = null
        var creates = 0
        var releases = 0
        lateinit var token: CoverageResourceToken
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ ->
                assertEquals(65_536, owners()["coverage-page-staging"])
                ledger.admitResourceReplacement(mode, sourceCapacity = 1199, retainedCount = 1199)
            },
            onFailureDetail = telemetry::recordResourceFailureDetail,
            onCreationFailure = { transition ->
                telemetry.recordResourceFailure()
                ledger.releaseRendererStaging()
                val cut = checkNotNull(owner.snapshot())
                val failed = checkNotNull(transition.token)
                if (!transition.hadActiveResource) recovery.recordFailure(failed,
                    CoverageResourceRecoveryCut(cut.bindingGeneration, cut.groupGeneration,
                        cut.transactionId, cut.geometryRevision))
                owner.markAnyResourceFailure(failed)
            },
            onCreated = { transition -> owner.markAnyResourceMounted(checkNotNull(transition.token)) },
        )
        fun create(): String? = factory.replacePoint(VoxelRenderMode.CENTROIDS, 20_000, "test",
            token = token, create = { _, capacity, name ->
                assertEquals(65_536, owners()["coverage-page-staging"])
                creates++
                if (alwaysFail || creates == 1) throw IllegalStateException("controlled allocation")
                resourceOwner = name
                ledger.installPointResources(name, capacity)
                ledger.completePointStartup(name)
                "gpu"
            }, release = {
                releases++
                resourceOwner?.let(ledger::releasePointResources)
            })
        fun recover(): CoverageResourceToken? {
            fund()
            return recovery.tryReplaceFailedGeneration(owner, token, 0L, true, true, factory)
        }
        fun fund() = ledger.installPersistentCoverageStateForCapacity(20_000, 1199)
        fun owners(): Map<*, *> = telemetry.snapshot()["ownedBufferBytesByOwner"] as Map<*, *>
        fun clear() {
            factory.releaseResource(token)
            factory.clear()
            ledger.clearCoverageState()
            owner.clearLatest()
            assertEquals(0, telemetry.ownedBufferBytesSnapshot())
        }

    }

    private fun install(s: Scenario, geometry: Long, binding: Long = 2L) {
        s.owner.installPresentation(descriptorForReceipt(1199, geometryRevision = geometry,
            rendererGeneration = 0L, bindingGeneration = binding),
            PointCloudNativeConfig(renderCapacity = 20_000, voxelRenderMode = VoxelRenderMode.CENTROIDS))
        s.fund()
    }

    @Test fun creationFailsOnceThenNewCanonicalCutRecoversWithNewResourceEpoch() {
        val s = Scenario()
        install(s, 1)
        s.token = checkNotNull(s.owner.requestResourceReplacement())
        assertNull(s.create())
        assertTrue(s.owner.status().rendererUnavailable)
        assertEquals(1199, s.owner.status().rowCount)
        assertNull(s.recover())
        install(s, 2)
        val failed = s.token
        s.token = checkNotNull(s.recover())
        assertTrue(s.token.epoch > failed.epoch)
        assertEquals("gpu", s.create())
        assertTrue(s.factory.markResourceMounted(s.token))
        s.recovery.mounted(s.token)
        assertTrue(s.owner.status().resourceAvailable)
        assertEquals(2, s.creates)
        assertEquals(1, s.factory.occupiedGenerationCount())
        assertEquals("allocation", s.telemetry.resourceScalarSnapshot()["lastResourceFailureStage"])
        assertEquals("IllegalStateException", s.telemetry.resourceScalarSnapshot()["lastResourceFailureClass"])
    }

    @Test fun persistentFailureHasOneBoundedAutomaticRetryWithoutAFrameLoop() {
        val s = Scenario(true)
        install(s, 1)
        s.token = checkNotNull(s.owner.requestResourceReplacement())
        assertNull(s.create())
        install(s, 2)
        val failed = s.token
        s.token = checkNotNull(s.recover())
        assertNull(s.create())
        assertFalse(s.owners().containsKey("coverage-page-staging"))
        for (revision in 3L..20L) { install(s, revision); assertNull(s.recover()) }
        assertEquals(2, s.creates)
        assertEquals(0, s.factory.occupiedGenerationCount())
        assertEquals(0, s.releases)
        s.ledger.releaseRendererStaging()
        assertFalse(s.owners().containsKey("coverage-page-staging"))
        s.clear()
    }

    @Test fun invalidatedOwnershipAndChargedResourcePreventRetry() {
        val s = Scenario()
        install(s, 1)
        s.token = checkNotNull(s.owner.requestResourceReplacement())
        assertNull(s.create())
        install(s, 2, binding = 4L)
        assertNull(s.recover())
        s.owner.dispose()
        assertNull(s.recover())
        assertEquals(1, s.creates)
        val token = CoverageResourceToken(1, 0, CoveragePresentationMode.SEMANTIC_CENTROIDS)
        val recovery = CoverageResourceCreationRecovery()
        recovery.recordFailure(token, CoverageResourceRecoveryCut(2, 3, 1, 1))
        val later = CoverageResourceRecoveryCut(2, 3, 2, 2)
        assertFalse(recovery.claim(token, later, false, true))
        assertFalse(recovery.claim(token, later, true, false))
        assertFalse(recovery.claim(token, later.copy(bindingGeneration = 4), true, true))
        assertFalse(recovery.claim(token.copy(epoch = 2), later, true, true))
        assertTrue(recovery.claim(token, later, true, true))
        assertFalse(recovery.claim(token, later.copy(transactionId = 3), true, true))
    }

    @Test fun coexistenceFailurePreservesTheActiveResourceAndItsChargedOwnership() {
        val first = CoverageResourceToken(1, 0, CoveragePresentationMode.SEMANTIC_CENTROIDS)
        val next = first.copy(epoch = 2)
        var releases = 0
        var failureHadActive = false
        val factory = CoverageRendererResourceFactory(onCreationFailure = { failureHadActive = it.hadActiveResource })
        assertEquals("active", factory.replacePoint(VoxelRenderMode.CENTROIDS, 20_000, "test",
            token = first, create = { _, _, _ -> "active" }, release = { releases++ }))
        factory.markResourceMounted(first)
        assertNull(factory.replacePoint<String>(VoxelRenderMode.CENTROIDS, 20_000, "test",
            token = next, create = { _, _, _ -> throw IllegalStateException("candidate failed") },
            release = { releases++ }))
        assertTrue(failureHadActive)
        assertTrue(factory.ownsToken(first))
        assertEquals(1, factory.occupiedGenerationCount())
        assertEquals(0, releases)
    }

    /** Constructor-free fixture injects only dependencies used by the real accepted host paths. */
    private fun host(s: Scenario): SceneViewHost {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val field = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val host = unsafeType.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), SceneViewHost::class.java) as SceneViewHost
        fun inject(name: String, value: Any) {
            SceneViewHost::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value)
        }
        val config = PointCloudNativeConfig(renderCapacity = 20_000, voxelRenderMode = VoxelRenderMode.CENTROIDS)
        inject("coverageRendererOwner", s.owner)
        inject("rendererAllocationLedger", s.ledger)
        inject("coverageCreationRecovery", s.recovery)
        inject("coverageResourceFactory", s.factory)
        inject("coverageSlotTokens", arrayOf(mutableStateOf<CoverageResourceToken?>(s.token), mutableStateOf<CoverageResourceToken?>(null)))
        inject("coverageSlotConfigs", arrayOf(mutableStateOf<PointCloudNativeConfig?>(config), mutableStateOf<PointCloudNativeConfig?>(null)))
        inject("coverageSlotRefs", arrayOf(AtomicReference<Any?>(null), AtomicReference<Any?>(null)))
        inject("activeCoverageSlot", 0)
        inject("preparingCoverageSlot", -1)
        inject("retiringCoverageSlot", -1)
        s.owner.setControls(CoverageRendererControls(true, CoveragePresentationMode.SEMANTIC_CENTROIDS, CoverageRendererPalette.COVERAGE))
        return host
    }

    private fun acceptedHostCut(host: SceneViewHost, caller: String, geometry: Long) {
        val config = PointCloudNativeConfig(renderCapacity = 20_000, voxelRenderMode = VoxelRenderMode.CENTROIDS)
        val descriptor = descriptorForReceipt(1199, geometryRevision = geometry, rendererGeneration = 0)
        val input: Any = if (caller == "applyCoveragePresentation") descriptor else CoveragePointRenderSnapshot(
            revision = geometry, enabled = true, capacity = 20_000, count = 1199,
            keys = LongArray(1199) { it.toLong() + 1 }, positions = FloatArray(1199 * 3), colors = IntArray(1199),
            bindingGeneration = 2, groupGeneration = 3, transactionId = 8, geometryRevision = geometry, styleRevision = 7)
        val type = if (caller == "applyCoveragePresentation") BoundedCoveragePresentation::class.java else CoveragePointRenderSnapshot::class.java
        val method = SceneViewHost::class.java.getDeclaredMethod(caller, type, PointCloudNativeConfig::class.java).apply { isAccessible = true }
        try { method.invoke(host, input, config) } catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun bothAcceptedHostPathsRestoreOneStagingChargeBeforeSuccessorAndAllocation() {
        for (caller in listOf("applyCoveragePresentation", "applyCoverageRenderer")) {
            val s = Scenario()
            install(s, 1)
            s.token = checkNotNull(s.owner.requestResourceReplacement())
            assertNull(s.create())
            assertFalse(s.owners().containsKey("coverage-page-staging"))
            val retained = s.telemetry.ownedBufferBytesSnapshot()
            assertTrue(retained > 0)
            val old = s.token
            acceptedHostCut(host(s), caller, 2)
            assertEquals(retained + 65_536, s.telemetry.ownedBufferBytesSnapshot())
            assertEquals(65_536, s.owners()["coverage-page-staging"])
            s.token = checkNotNull(s.owner.currentResourceToken())
            assertTrue(s.token.epoch > old.epoch)
            assertEquals("gpu", s.create())
            s.factory.markResourceMounted(s.token)
            s.recovery.mounted(s.token)
            val mounted = s.telemetry.ownedBufferBytesSnapshot()
            repeat(3) { s.fund(); assertEquals(mounted, s.telemetry.ownedBufferBytesSnapshot()) }
            s.clear()
        }
    }

    @Test fun bothAcceptedHostPathsRefuseUnfundedRestorationBeforeClaimAndCanRetrySameCut() {
        for (caller in listOf("applyCoveragePresentation", "applyCoverageRenderer")) {
            val s = Scenario()
            install(s, 1)
            s.token = checkNotNull(s.owner.requestResourceReplacement())
            assertNull(s.create())
            val host = host(s)
            val old = s.token
            val retained = s.telemetry.ownedBufferBytesSnapshot()
            val pressure = CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES - retained
            s.telemetry.setOwnedBufferBytes("retained-pressure-owner", pressure)
            try {
                acceptedHostCut(host, caller, 2)
                fail("Unfunded staging must refuse before recovery: " + caller)
            } catch (_: IllegalStateException) { }
            assertEquals(old, s.owner.currentResourceToken())
            assertEquals(1, s.creates)
            assertFalse(s.owners().containsKey("coverage-page-staging"))
            assertEquals(pressure, s.owners()["retained-pressure-owner"])
            assertEquals(CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES, s.telemetry.ownedBufferBytesSnapshot())
            s.telemetry.removeOwner("retained-pressure-owner")
            acceptedHostCut(host, caller, 2)
            s.token = checkNotNull(s.owner.currentResourceToken())
            assertTrue(s.token.epoch > old.epoch)
            assertEquals(65_536, s.owners()["coverage-page-staging"])
            assertEquals("gpu", s.create())
            s.clear()
        }
    }

    private fun descriptorForReceipt(
        count: Int,
        geometryRevision: Long = 4L,
        styleRevision: Long = 7L,
        targetSurfaceId: Long? = null,
        targetDirectionIndex: Int? = null,
        bindingGeneration: Long = 2L,
        groupGeneration: Long = 3L,
        world: DoubleArray? = null,
        rendererGeneration: Long = 5L,
    ): BoundedCoveragePresentation {
        val qualifier = CoverageRowsQualifier(bindingGeneration, groupGeneration, rendererGeneration, 8L, geometryRevision, styleRevision)
        val surfaceIds = LongArray(count) { it.toLong() + 1L }
        val style = CoverageRendererStyleRowV1().encode()
        val styleRows = ByteArray(count * com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES) {
            style[it % style.size]
        }
        return PresentationDescriptor.createOwned(
            qualifier = qualifier,
            mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
            enabled = true,
            capacity = CoveragePresentationMode.SEMANTIC_CENTROIDS.presentationCapacity,
            sourceCapacity = count,
            sourceCount = count,
            palette = CoverageRendererPalette.COVERAGE,
            paletteEpoch = 1L,
            targetSurfaceId = targetSurfaceId,
            targetDirectionIndex = targetDirectionIndex,
            selectedSurfaceIds = surfaceIds,
            selectedSourceSlots = IntArray(count) { it },
            styleRows = styleRows,
            update = null,
            worldFromGroup = world,
            workspace = PresentationWorkspace(count),
            pageReader = PresentationPageReader { expected, start, pageCount, _, page ->
                if (expected != qualifier || start >= count || start + pageCount > count) false else {
                    surfaceIds.copyInto(page.surfaceIds, 0, start, start + pageCount)
                    java.util.Arrays.fill(page.positions, 0, pageCount * 3, 0f)
                    true
                }
            },
        )
    }
}
