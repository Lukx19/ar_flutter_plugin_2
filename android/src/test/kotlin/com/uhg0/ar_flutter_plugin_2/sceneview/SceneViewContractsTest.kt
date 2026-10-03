package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig

class SceneViewContractsTest {
    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test
    fun `node source mapping selects the correct SceneView loader scheme`() {
        val assetResolver: (String) -> String = { "flutter_assets/$it" }

        assertEquals(
            "flutter_assets/packages/plugin/model.gltf",
            resolveNodeUri(
                PluginNodeSource.FLUTTER_ASSET_GLTF2,
                "packages/plugin/model.gltf",
                "/data/user/0/app",
                assetResolver,
            ),
        )
        assertEquals(
            "https://example.test/model.glb",
            resolveNodeUri(
                PluginNodeSource.WEB_GLB,
                "https://example.test/model.glb",
                "/data/user/0/app",
                assetResolver,
            ),
        )
        assertTrue(
            resolveNodeUri(
                PluginNodeSource.APP_FOLDER_GLTF2,
                "model.gltf",
                "/data/user/0/app",
                assetResolver,
            ).startsWith("file:"),
        )
        assertTrue(
            resolveNodeUri(
                PluginNodeSource.APP_FOLDER_GLB,
                "model.glb",
                "/data/user/0/app",
                assetResolver,
            ).endsWith("/app_flutter/model.glb"),
        )
    }

    @Test
    fun `Dart node type ordinals retain their public meanings`() {
        assertEquals(
            PluginNodeSource.FLUTTER_ASSET_GLTF2,
            PluginNodeSource.fromDartOrdinal(0),
        )
        assertEquals(PluginNodeSource.WEB_GLB, PluginNodeSource.fromDartOrdinal(1))
        assertEquals(PluginNodeSource.APP_FOLDER_GLB, PluginNodeSource.fromDartOrdinal(2))
        assertEquals(PluginNodeSource.APP_FOLDER_GLTF2, PluginNodeSource.fromDartOrdinal(3))
        assertThrows(IllegalArgumentException::class.java) {
            PluginNodeSource.fromDartOrdinal(4)
        }
    }

    @Test
    fun `shared camera gate suppresses SceneView resume until Camera2 is ready`() {
        val gate = SharedCameraSceneLifecycleGate(sharedCameraRequested = true)

        assertTrue(gate.shouldPauseSceneViewResume())
        gate.prepareSharedCameraResume()
        assertFalse(gate.shouldPauseSceneViewResume())
    }

    @Test
    fun `ordinary sessions never suppress SceneView resume`() {
        assertFalse(
            SharedCameraSceneLifecycleGate(sharedCameraRequested = false)
                .shouldPauseSceneViewResume(),
        )
    }

    @Test
    fun `transform preserves column-major Flutter values exactly`() {
        val values = List(16) { index -> index.toDouble() / 10.0 }

        val transform = PluginTransform(values)

        assertEquals(values, transform.matrix)
    }

    @Test
    fun `transform rejects malformed or non-finite matrices`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginTransform(List(15) { 0.0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginTransform(List(16) { index -> if (index == 4) Double.NaN else 0.0 })
        }
    }

    @Test
    fun `transform decomposition preserves translation rotation and scale`() {
        val transform = PluginTransform(
            listOf(
                0.0, 2.0, 0.0, 0.0,
                -3.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 4.0, 0.0,
                5.0, 6.0, 7.0, 1.0,
            ),
        )

        val components = transform.decompose()

        assertEquals(PluginVector3(5.0, 6.0, 7.0), components.position)
        assertEquals(PluginVector3(2.0, 3.0, 4.0), components.scale)
        assertEquals(0.0, components.rotation.x, 1e-9)
        assertEquals(0.0, components.rotation.y, 1e-9)
        assertEquals(kotlin.math.sqrt(0.5), components.rotation.z, 1e-9)
        assertEquals(kotlin.math.sqrt(0.5), components.rotation.w, 1e-9)
    }

    @Test
    fun `records preserve stable node anchor and hit identifiers`() {
        val transform = PluginTransform(List(16) { index -> if (index % 5 == 0) 1.0 else 0.0 })
        val node = PluginNodeRecord(
            id = "node-1",
            source = PluginNodeSource.APP_FOLDER_GLB,
            uri = "model.glb",
            transform = transform,
        )
        val anchor = PluginAnchorRecord(
            id = "anchor-1",
            transform = transform,
            childNodeIds = listOf(node.id),
        )
        val hit = PluginHitResult(
            type = PluginHitType.PLANE,
            distanceMeters = 1.25,
            worldTransform = transform,
        )

        assertEquals("node-1", node.id)
        assertEquals(listOf("node-1"), anchor.childNodeIds)
        assertEquals(PluginHitType.PLANE, hit.type)
        assertEquals(1.25, hit.distanceMeters, 0.0)
    }

    @Test
    fun `ownership permits one create and one dispose per platform view`() {
        val ownership = SceneViewHostOwnership()

        ownership.onCreate()
        assertTrue(ownership.onDispose())
        assertFalse(ownership.onDispose())
        assertEquals(1, ownership.createCount)
        assertEquals(1, ownership.disposeCount)
        assertThrows(IllegalStateException::class.java) { ownership.onCreate() }
    }

    @Test
    fun `platform view composition survives transient detach`() {
        assertSame(
            androidx.compose.ui.platform.ViewCompositionStrategy
                .DisposeOnViewTreeLifecycleDestroyed,
            sceneViewCompositionStrategy(),
        )
    }

    @Test
    fun `hidden coverage keeps its composed renderer subtree mounted`() {
        assertTrue(
            shouldComposeCoverageRenderer(PointCloudNativeConfig(enabled = false)),
        )
        assertFalse(shouldComposeCoverageRenderer(null))
    }

    @Test
    fun `render lifecycle pauses before terminal composition disposal`() {
        val executor = ArchTaskExecutor.getInstance()
        executor.setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        try {
            val parent = TestLifecycleOwner()
            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            val render = SceneViewRenderLifecycle(parent.lifecycle)

            assertEquals(Lifecycle.State.RESUMED, render.lifecycle.currentState)
            render.pauseForTeardown()
            assertEquals(Lifecycle.State.CREATED, render.lifecycle.currentState)

            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(Lifecycle.State.CREATED, render.lifecycle.currentState)
            render.resumeAfterTransientDetach()
            assertEquals(Lifecycle.State.RESUMED, render.lifecycle.currentState)
            render.pauseForTeardown()
            render.destroyAfterComposition()
            assertEquals(Lifecycle.State.DESTROYED, render.lifecycle.currentState)

            val parentDestroying = TestLifecycleOwner()
            parentDestroying.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            parentDestroying.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            parentDestroying.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            val renderDuringParentDestroy =
                SceneViewRenderLifecycle(parentDestroying.lifecycle)
            parentDestroying.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            assertEquals(
                Lifecycle.State.CREATED,
                renderDuringParentDestroy.lifecycle.currentState,
            )
            renderDuringParentDestroy.destroyAfterComposition()
            assertEquals(
                Lifecycle.State.DESTROYED,
                renderDuringParentDestroy.lifecycle.currentState,
            )
        } finally {
            executor.setDelegate(null)
        }
    }

    @Test
    fun `render lifecycle trace records parent and terminal transitions`() {
        val executor = ArchTaskExecutor.getInstance()
        executor.setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        try {
            val parent = TestLifecycleOwner()
            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            val trace = mutableListOf<String>()
            val render = SceneViewRenderLifecycle(parent.lifecycle) { trace += it }

            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            render.pauseForTeardown()
            render.destroyAfterComposition()

            assertTrue(trace.any { it.contains("source=parent event=ON_START") })
            assertTrue(trace.any { it.contains("source=parent event=ON_RESUME") })
            assertTrue(trace.any { it.contains("source=pause event=ON_PAUSE") })
            assertTrue(trace.any { it.contains("source=destroyAfterComposition event=ON_DESTROY") })
        } finally {
            executor.setDelegate(null)
        }
    }

    @Test
    fun `composition disposal waits for one frame and coalesces duplicate requests`() {
        var scheduled: (() -> Unit)? = null
        var disposalCount = 0
        val gate = SceneViewCompositionDisposalGate(
            scheduleOnNextFrame = { work -> scheduled = work },
            disposeComposition = { disposalCount++ },
        )

        gate.request()
        gate.request()

        assertEquals(0, disposalCount)
        checkNotNull(scheduled).invoke()
        assertEquals(1, disposalCount)
    }
}
