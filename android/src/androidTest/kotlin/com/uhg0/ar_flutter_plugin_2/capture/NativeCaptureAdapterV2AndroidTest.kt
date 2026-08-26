package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic only: production binding/manager/Looper lifecycle, not Camera2 hardware. */
@RunWith(AndroidJUnit4::class)
class NativeCaptureAdapterV2AndroidTest {
    @Test
    fun perViewBindingDrainsAndFencesLateComponents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val callbackThread = HandlerThread("native-capture-v2-test").apply { start() }
        val flutterEngine = FlutterEngine(context)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val signal = CaptureSafetySignalV2()
        val binding = NativeCaptureBindingV2(context, signal)
        val manager = SharedCameraManager(
            context = context,
            methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "native-capture-v2-test"),
            session = null,
            cameraTextureIds = { intArrayOf() },
            prepareSessionResume = {},
            scenePreviewSurface = surface,
            configMap = mapOf("resolution" to mapOf("width" to 128, "height" to 128), "format" to "jpeg"),
        )
        binding.attachSharedCamera(manager)
        val callbacks = mutableListOf<Pair<CaptureAttemptQualifierV2, SharedCameraExposureCallbackV2>>()
        binding.installSyntheticExposureHookForTest(request = { qualifier, _, callback ->
            callbacks += qualifier to callback
            true
        })
        val runId = System.nanoTime().toString()

        try {
            val paused = request("$runId-paused")
            binding.admit(paused)
            val pausedOwner = callbacks.single()
            binding.onPause()
            manager.onArSessionPaused()
            assertFalse(signal.isCaptureSafe())
            assertEquals(1L, binding.snapshot().abandoned)
            val lateAfterPause = TrackingInput("jpeg".toByteArray())
            postComponents(callbackThread, pausedOwner, lateAfterPause)
            assertEquals(1, lateAfterPause.closeCalls)

            val later = request("$runId-later")
            binding.admit(later)
            val laterOwner = callbacks.last()
            val successful = TrackingInput("jpeg".toByteArray())
            postComponents(callbackThread, laterOwner, successful)
            assertEquals(1, successful.closeCalls)
            assertEquals(1L, binding.snapshot().committed)
            assertEquals(0, binding.snapshot().running)
            assertEquals(0, binding.snapshot().fundedWaiting)

            val disposed = request("$runId-disposed")
            binding.admit(disposed)
            val disposedOwner = callbacks.last()
            binding.close()
            manager.cleanup()
            manager.finishCameraShutdown(1_000L)
            assertFalse(signal.isCaptureSafe())
            val lateAfterDispose = TrackingInput("jpeg".toByteArray())
            postComponents(callbackThread, disposedOwner, lateAfterDispose)
            assertEquals(1, lateAfterDispose.closeCalls)
            assertEquals(2L, binding.snapshot().lateCallbacks)
            assertEquals(2L, binding.snapshot().abandoned)
            // Snapshot is the only outward V2 projection and is scalar metadata.
            assertEquals(0, CaptureResourceSnapshotV2::class.java.declaredFields.count { it.type == ByteArray::class.java })
        } finally {
            runCatching { binding.close() }
            surface.release()
            texture.release()
            flutterEngine.destroy()
            callbackThread.quitSafely()
            callbackThread.join(5_000L)
        }
    }

    private fun postComponents(
        thread: HandlerThread,
        owner: Pair<CaptureAttemptQualifierV2, SharedCameraExposureCallbackV2>,
        input: TrackingInput,
    ) {
        val done = CountDownLatch(1)
        Handler(thread.looper).post {
            owner.second.onComponents(SharedCameraComponentSetV2(owner.first, listOf(
                CaptureComponentStreamV2(CaptureComponentKind.JPEG, input),
            )))
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun request(suffix: String): CaptureCommitRequest {
        val cut = CaptureLifecycleCut("session-$suffix", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
        val accepted = CaptureAcceptedAttempt(
            CaptureAttemptIdentity("attempt-$suffix", "commit-$suffix", 1, cut), CaptureLane.MANUAL,
            CaptureComponentProfile("jpeg", setOf(CaptureComponentKind.JPEG), 128, 128),
            CaptureReservationLiability(0, 128, 1, 1, 0, true), digest("intent-$suffix"), digest("accepted-$suffix"),
        )
        val bytes = "jpeg".toByteArray()
        return CaptureCommitRequest(
            accepted,
            listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, bytes.size.toLong(), digest("jpeg"), "object-$suffix")),
            1,
            digest("p"), digest("c"), digest("v"), digest("l"),
        )
    }

    private class TrackingInput(private val bytes: ByteArray) : InputStream() {
        private var offset = 0
        var closeCalls = 0
            private set
        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xff
        override fun close() { closeCalls++ }
    }

    private companion object {
        fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).map { it.toInt() and 0xff }
    }
}
