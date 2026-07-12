package com.uhg0.ar_flutter_plugin_2.capture

import com.google.ar.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCameraSessionFeaturePlannerTest {
    @Test
    fun `high res request before session creation requires rebuild with shared camera feature`() {
        val plan =
            SharedCameraSessionFeaturePlanner.plan(
                enableHighResCapture = true,
                currentSessionFeatures = emptySet(),
                hasLiveSession = false,
            )

        assertEquals(setOf(Session.Feature.SHARED_CAMERA), plan.targetSessionFeatures)
        assertTrue(plan.requiresSceneRebuild)
        assertNull(plan.failureCode)
    }

    @Test
    fun `high res request after live non shared session fails explicitly`() {
        val plan =
            SharedCameraSessionFeaturePlanner.plan(
                enableHighResCapture = true,
                currentSessionFeatures = emptySet(),
                hasLiveSession = true,
            )

        assertEquals(emptySet<Session.Feature>(), plan.targetSessionFeatures)
        assertFalse(plan.requiresSceneRebuild)
        assertEquals("SHARED_CAMERA_REQUIRES_RESTART", plan.failureCode)
    }

    @Test
    fun `high res request on already shared session keeps current features`() {
        val features = setOf(Session.Feature.SHARED_CAMERA)
        val plan =
            SharedCameraSessionFeaturePlanner.plan(
                enableHighResCapture = true,
                currentSessionFeatures = features,
                hasLiveSession = true,
            )

        assertEquals(features, plan.targetSessionFeatures)
        assertFalse(plan.requiresSceneRebuild)
        assertNull(plan.failureCode)
    }

    @Test
    fun `non high res request leaves current features unchanged`() {
        val features = setOf(Session.Feature.SHARED_CAMERA)
        val plan =
            SharedCameraSessionFeaturePlanner.plan(
                enableHighResCapture = false,
                currentSessionFeatures = features,
                hasLiveSession = true,
            )

        assertEquals(features, plan.targetSessionFeatures)
        assertFalse(plan.requiresSceneRebuild)
        assertNull(plan.failureCode)
    }
}
