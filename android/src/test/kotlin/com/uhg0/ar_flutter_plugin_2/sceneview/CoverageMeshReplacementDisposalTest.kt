package com.uhg0.ar_flutter_plugin_2.sceneview

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import io.github.sceneview.node.Node
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageMeshReplacementDisposalTest {
    @OptIn(InternalComposeApi::class, ExperimentalComposeApi::class)
    @Test
    fun retiredMeshMaterialIsReplacedWhileLiveResidencySurvivesRecomposition() {
        val loader = TestMaterialLoader()
        val resource = mutableStateOf(Any())
        val visible = mutableStateOf(true)
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(MaterialTestApplier(), recomposer)
        lateinit var mounted: TestMaterialNode

        fun recompose() {
            Snapshot.sendApplyNotifications()
            composition.invalidateAll()
            assertTrue(composition.recompose())
            composition.applyChanges()
            composition.applyLateChanges()
            composition.changesApplied()
        }

        try {
            composition.setContent {
                key("coverage-slot", 0) {
                    val mesh = resource.value
                    val surface = rememberCoverageResourceMaterial(mesh, loader, loader::create)
                    val outline = rememberCoverageResourceMaterial(mesh, loader, loader::create)
                    val node = remember(mesh, surface, outline) {
                        // Construction checks the same live-instance prerequisite
                        // as Filament's RenderableManager.Builder.material.
                        TestMaterialNode(listOf(surface, outline))
                    }
                    val displayVisible = visible.value
                    SideEffect { mounted = node; node.visible = displayVisible }
                    DisposableEffect(node) { onDispose { node.destroy() } }
                }
            }

            val outgoing = mounted
            assertEquals(2, loader.materials.size)
            visible.value = false
            recompose()
            assertSame(outgoing, mounted)
            assertFalse(mounted.visible)
            assertTrue(outgoing.materials.all { it.destroyCalls == 0 })
            assertEquals(2, loader.materials.size)

            visible.value = true
            recompose()
            assertSame(outgoing, mounted)
            assertTrue(mounted.visible)
            assertEquals(2, loader.materials.size)

            // Promotion/cancellation destroys the old node before the physical
            // Compose slot is reused for a different exact mesh resource.
            outgoing.destroy()
            assertTrue(outgoing.materials.all { it.destroyCalls == 1 })
            resource.value = Any()
            recompose()

            val successor = mounted
            assertNotSame(outgoing, successor)
            assertEquals(4, loader.materials.size)
            assertTrue(successor.materials.all { it.destroyCalls == 0 })
            successor.materials.forEach(TestMaterial::requireLive)
            assertTrue(outgoing.materials.all { it.destroyCalls == 1 })

            visible.value = false
            recompose()
            assertSame(successor, mounted)
            assertEquals(4, loader.materials.size)
            assertTrue(successor.materials.all { it.destroyCalls == 0 })
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        assertTrue(loader.materials.all { it.destroyCalls == 1 })
    }

    @Test
    fun `detached outgoing node still releases its resource before replacement`() {
        val resources = DetachedCoverageResources()

        disposeCoverageResourcesForReplacement(node = null, resources = resources)

        assertEquals(1, resources.destroyCalls)
    }

    @Test
    fun `retired resource remains charged until asynchronous drain`() {
        val disposed = mutableListOf<CoverageResourceToken?>()
        val factory = CoverageRendererResourceFactory(onDisposed = { disposed += it })
        val firstToken = CoverageResourceToken(1L, 1L, CoveragePresentationMode.SEMANTIC_CENTROIDS)
        val secondToken = CoverageResourceToken(2L, 2L, CoveragePresentationMode.SEMANTIC_CENTROIDS)
        lateinit var first: DetachedCoverageResources
        factory.replacePoint(
            VoxelRenderMode.CENTROIDS, 1, "first", token = firstToken,
            create = { _, _, _ -> DetachedCoverageResources().also { first = it } },
            release = DetachedCoverageResources::destroy,
        )
        factory.replacePoint(
            VoxelRenderMode.CENTROIDS, 1, "second", token = secondToken,
            create = { _, _, _ -> DetachedCoverageResources() },
            release = DetachedCoverageResources::destroy,
        )

        assertTrue(factory.releaseResource(firstToken))
        assertTrue(disposed.isEmpty())
        first.signalDrain()
        assertEquals(listOf(firstToken), disposed)
    }
}

private class DetachedCoverageResources : CoverageVoxelMeshResources {
    var destroyCalls = 0
    private var drained = false
    private var onDrained: () -> Unit = {}

    override val capacity: Int get() = 1
    override val vertexBuffer: VertexBuffer get() = error("not used by disposal test")
    override val indexBuffer: IndexBuffer get() = error("not used by disposal test")
    override val primitiveType: RenderableManager.PrimitiveType
        get() = error("not used by disposal test")

    override fun update(
        node: Node,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    ) = error("not used by disposal test")

    override fun hide(node: Node) = error("not used by disposal test")

    override fun requireRetainedSnapshotUpload() = error("not used by disposal test")

    override fun onRendererFrame() = error("not used by disposal test")

    override fun setOnUploadPageReleased(listener: () -> Unit) = error("not used by disposal test")

    override fun setOnResourceDrained(listener: () -> Unit) {
        onDrained = listener
        if (drained) listener()
    }

    override val resourceDrainComplete: Boolean
        get() = drained

    override fun destroy() {
        destroyCalls++
    }

    fun signalDrain() {
        drained = true
        onDrained()
    }
}

private class TestMaterial {
    var destroyCalls = 0
        private set

    fun requireLive() {
        check(destroyCalls == 0) { "Calling method on destroyed MaterialInstance" }
    }

    fun destroy() {
        destroyCalls++
    }
}

private class TestMaterialLoader {
    val materials = mutableListOf<TestMaterial>()
    fun create(): TestMaterial = TestMaterial().also(materials::add)
}

private class TestMaterialNode(val materials: List<TestMaterial>) {
    var visible = true
    private var destroyed = false

    init {
        materials.forEach(TestMaterial::requireLive)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        materials.forEach(TestMaterial::destroy)
    }
}

private class MaterialTestApplier : AbstractApplier<Unit>(Unit) {
    override fun onClear() = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
}
