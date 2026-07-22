package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SharedCameraInteropPlannerTest {
    @Test
    fun `checkAvailability fails when ARCore session is missing`() {
        val failure =
            SharedCameraInteropPlanner.checkAvailability(
                hasSession = false,
                hasSharedCamera = false,
            )

        assertEquals("CAPTURE_NOT_INITIALIZED", failure?.code)
    }

    @Test
    fun `checkAvailability fails when shared camera is missing from active session`() {
        val failure =
            SharedCameraInteropPlanner.checkAvailability(
                hasSession = true,
                hasSharedCamera = false,
            )

        assertEquals("SHARED_CAMERA_UNSUPPORTED", failure?.code)
    }

    @Test
    fun `checkAvailability accepts active shared camera session`() {
        val failure =
            SharedCameraInteropPlanner.checkAvailability(
                hasSession = true,
                hasSharedCamera = true,
            )

        assertNull(failure)
    }

    @Test
    fun `resolveCameraId prefers ARCore camera id when available`() {
        val resolved =
            SharedCameraInteropPlanner.resolveCameraId(
                sharedCameraId = "1",
            )

        assertEquals("1", resolved)
    }

    @Test
    fun `resolveCameraId fails when shared camera id is absent`() {
        try {
            SharedCameraInteropPlanner.resolveCameraId(sharedCameraId = null)
            fail("Expected CaptureSessionException")
        } catch (error: CaptureSessionException) {
            assertEquals("CAPTURE_NOT_INITIALIZED", error.code)
        }
    }

    @Test
    fun `buildSessionSurfaces prepends ARCore surfaces when available`() {
        val surfaces =
            SharedCameraInteropPlanner.buildSessionSurfaces(
                arCoreSurfaces = listOf("arcore-preview", "arcore-metadata"),
                appSurfaces = listOf("jpeg-reader"),
            )

        assertEquals(
            listOf("arcore-preview", "arcore-metadata", "jpeg-reader"),
            surfaces,
        )
    }

    @Test
    fun `requireArCoreSurfaces fails when ARCore surfaces are absent`() {
        try {
            SharedCameraInteropPlanner.requireArCoreSurfaces<String>(null)
            fail("Expected CaptureSessionException")
        } catch (error: CaptureSessionException) {
            assertEquals("CAPTURE_NOT_INITIALIZED", error.code)
        }
    }

    @Test
    fun `buildRepeatingRequestTargets includes every configured shared-camera surface`() {
        val targets =
            SharedCameraInteropPlanner.buildRepeatingRequestTargets(
                arCoreSurfaces = listOf("arcore-preview"),
                appSurfaces = listOf("app-jpeg"),
            )

        assertEquals(listOf("arcore-preview", "app-jpeg"), targets)
    }
}
