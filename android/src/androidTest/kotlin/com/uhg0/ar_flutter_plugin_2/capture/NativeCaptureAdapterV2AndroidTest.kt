package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        val captureRoot = File(context.filesDir, "capture-v2-native")
        check(captureRoot.deleteRecursively())
        assertFalse(captureRoot.exists())
        val callbackThread = HandlerThread("native-capture-v2-test").apply { start() }
        val signal = CaptureSafetySignalV2()
        val callbacks = mutableListOf<Pair<CaptureAttemptQualifierV2, SharedCameraExposureCallbackV2>>()
        var flutterEngine: FlutterEngine? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        var binding: NativeCaptureBindingV2? = null
        var manager: SharedCameraManager? = null
        var syntheticCancels = 0
        var mainResourcesClosed = false
        try {
            onMain {
                assertTrue(Looper.myLooper() === Looper.getMainLooper())
                flutterEngine = FlutterEngine(context)
                texture = SurfaceTexture(0)
                surface = Surface(checkNotNull(texture))
                binding = NativeCaptureBindingV2(context, signal)
                assertTrue(captureRoot.isDirectory)
                manager = SharedCameraManager(
                    context = context,
                    methodChannel = MethodChannel(checkNotNull(flutterEngine).dartExecutor.binaryMessenger, "native-capture-v2-test"),
                    session = null,
                    cameraTextureIds = { intArrayOf() },
                    prepareSessionResume = {},
                    scenePreviewSurface = checkNotNull(surface),
                    configMap = mapOf("resolution" to mapOf("width" to 128, "height" to 128), "format" to "jpeg"),
                )
                checkNotNull(binding).attachSharedCamera(checkNotNull(manager))
                assertTrue(
                    checkNotNull(binding).installSyntheticExposureHookForTest(
                        request = { qualifier, _, callback ->
                            assertTrue(Looper.myLooper() === Looper.getMainLooper())
                            callbacks += qualifier to callback
                            true
                        },
                        cancel = { syntheticCancels += 1 },
                    ),
                )
            }
            val activeBinding = checkNotNull(binding)
            val activeManager = checkNotNull(manager)
            val runId = System.nanoTime().toString()
            val paused = request("$runId-paused")
            onMain { activeBinding.admit(paused) }
            val pausedOwner = callbacks.single()
            val pausedSnapshot = onMainValue {
                activeBinding.onPause()
                activeManager.onArSessionPaused()
                assertFalse(signal.isCaptureSafe())
                activeBinding.snapshot()
            }
            assertEquals(1L, pausedSnapshot.abandoned)
            val lateAfterPause = TrackingInput("jpeg".toByteArray())
            postComponents(callbackThread, pausedOwner, lateAfterPause)
            assertEquals(1, lateAfterPause.closeCalls)

            val later = request("$runId-later")
            onMain { activeBinding.admit(later) }
            val laterOwner = callbacks.last()
            val successful = TrackingInput("jpeg".toByteArray())
            postComponents(callbackThread, laterOwner, successful)
            assertEquals(1, successful.closeCalls)
            val laterSnapshot = onMainValue(activeBinding::snapshot)
            assertEquals(1L, laterSnapshot.committed)
            assertEquals(0, laterSnapshot.running)
            assertEquals(0, laterSnapshot.fundedWaiting)

            val disposed = request("$runId-disposed")
            onMain { activeBinding.admit(disposed) }
            val disposedOwner = callbacks.last()
            onMain {
                activeBinding.close()
                assertFalse(
                    activeBinding.installSyntheticExposureHookForTest(
                        request = { _, _, _ -> true },
                    ),
                )
                activeBinding.detachSharedCamera(activeManager)
                activeManager.cleanup()
                activeManager.finishCameraShutdown(1_000L)
                assertFalse(signal.isCaptureSafe())
                checkNotNull(surface).release()
                checkNotNull(texture).release()
                checkNotNull(flutterEngine).destroy()
                mainResourcesClosed = true
            }
            val lateAfterDispose = TrackingInput("jpeg".toByteArray())
            postComponents(callbackThread, disposedOwner, lateAfterDispose)
            assertEquals(1, lateAfterDispose.closeCalls)
            val disposedSnapshot = onMainValue(activeBinding::snapshot)
            assertEquals(2L, disposedSnapshot.lateCallbacks)
            assertEquals(2L, disposedSnapshot.abandoned)
            assertEquals(3, syntheticCancels)
            // Snapshot is the only outward V2 projection and is scalar metadata.
            assertEquals(0, CaptureResourceSnapshotV2::class.java.declaredFields.count { it.type == ByteArray::class.java })
        } finally {
            if (!mainResourcesClosed) onMain {
                runCatching { binding?.close() }
                manager?.let { current -> runCatching { binding?.detachSharedCamera(current) } }
                runCatching { manager?.cleanup() }
                runCatching { manager?.finishCameraShutdown(1_000L) }
                runCatching { surface?.release() }
                runCatching { texture?.release() }
                runCatching { flutterEngine?.destroy() }
                mainResourcesClosed = true
            }
            callbackThread.quitSafely()
            callbackThread.join(5_000L)
            check(captureRoot.deleteRecursively())
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private fun <T : Any> onMainValue(block: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(block()) }
        return checkNotNull(value.get())
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
