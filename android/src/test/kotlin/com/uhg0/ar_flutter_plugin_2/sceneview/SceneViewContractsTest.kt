package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneViewContractsTest {
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
}
