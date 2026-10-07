package com.uhg0.ar_flutter_plugin_2.capture

/** Off-Main shutdown phases; a failed phase never authorizes Session destruction. */
internal object CameraShutdownDrain {
    fun finish(
        quiesceCallbacks: () -> Unit,
        releaseCorrelatorOwners: () -> Unit,
        awaitImagesClosed: () -> Unit,
        closeReaders: () -> Unit,
        completeShutdown: () -> Unit,
    ) {
        quiesceCallbacks()
        releaseCorrelatorOwners()
        awaitImagesClosed()
        closeReaders()
        completeShutdown()
    }
}

/** Delayed quit requests retain both joinable owners until termination is proven. */
internal class CameraCallbackThreads<T : Thread>(
    private val requestQuit: (T) -> Unit,
    private val elapsedMillis: () -> Long,
) {
    @Volatile var camera: T? = null
    @Volatile var capture: T? = null

    fun requestQuit() {
        camera?.let(requestQuit)
        capture?.let(requestQuit)
    }

    fun drainUntil(deadlineMillis: Long) {
        val ownedCamera = camera
        val ownedCapture = capture
        requestQuit()
        for (thread in listOfNotNull(ownedCamera, ownedCapture)) {
            check(thread !== Thread.currentThread()) { "A callback thread cannot drain itself" }
            if (thread.isAlive) {
                val remaining = deadlineMillis - elapsedMillis()
                check(remaining > 0) { "Camera callback drain deadline expired; owners retained" }
                thread.join(remaining)
            }
            check(!thread.isAlive) { "Camera callback thread remains owned; Session retained" }
        }
        check(camera === ownedCamera && capture === ownedCapture) { "Camera callback owner generation changed during drain" }
        camera = null
        capture = null
    }
}
