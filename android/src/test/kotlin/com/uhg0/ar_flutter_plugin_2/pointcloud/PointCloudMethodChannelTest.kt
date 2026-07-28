package com.uhg0.ar_flutter_plugin_2.pointcloud

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCloudMethodChannelTest {
    @Test
    fun `direct channel validates init methods typed arrays and disposal`() {
        val endpoint = FakeEndpoint()
        val scheduler = FakeScheduler()
        val snapshots = mutableListOf<CoveragePointRenderSnapshot?>()
        val channel = PointCloudMethodChannel(
            endpoint = endpoint,
            isDebuggable = true,
            onRendererStateChanged = { snapshot, _ -> snapshots += snapshot },
            sourceFactory = { SyntheticPointCloudSource() },
            callbackScheduler = scheduler,
        )

        val beforeInit = endpoint.call("setPointsEnabled", mapOf("enabled" to false))
        assertEquals("PC_NOT_INITIALIZED", beforeInit.errorCode)
        val init = endpoint.call("init", initArguments())
        assertEquals(POINT_CLOUD_WIRE_VERSION, init.successMap()["version"])
        assertEquals(false, init.successMap()["rendererReady"])
        channel.setRendererMounted(true)
        assertEquals(0, snapshots.last()!!.count)
        assertTrue(snapshots.last()!!.enabled)

        val keys = LongArray(4_096) { it.toLong() }
        val positions = FloatArray(4_096 * 3)
        val colors = IntArray(4_096) { 0xFF00FF00.toInt() }
        val colorsResult = endpoint.call(
            "updateVoxels",
            mapOf(
                "epoch" to 9L,
                "keys" to keys,
                "positionsWorld" to positions,
                "colors" to colors,
            ),
        )
        assertEquals(true, colorsResult.successMap()["applied"])
        assertEquals(9L, colorsResult.successMap()["lastAppliedEpoch"])
        assertEquals(
            9L,
            endpoint.call("getRenderingStats").successMap()["lastAppliedColorEpoch"],
        )

        assertEquals(true, endpoint.call("clear").successValue)
        assertEquals(true, endpoint.call("dispose").successValue)
        assertNull(endpoint.handler)
        assertTrue(scheduler.removed)
        assertNotNull(snapshots.lastIndexOf(null).takeIf { it >= 0 })
    }

    @Test
    fun `runtime voxel mode toggle updates renderer configuration`() {
        val endpoint = FakeEndpoint()
        val configs = mutableListOf<PointCloudNativeConfig?>()
        val rawSnapshots = mutableListOf<CoveragePointRenderSnapshot?>()
        val channel = PointCloudMethodChannel(
            endpoint = endpoint,
            isDebuggable = true,
            onRendererStateChanged = { _, config -> configs += config },
            onRawPointCloudChanged = { rawSnapshots += it },
            sourceFactory = { SyntheticPointCloudSource() },
            callbackScheduler = FakeScheduler(),
        )
        endpoint.call("init", initArguments())
        channel.onFrameForTest(0)
        assertEquals(4, rawSnapshots.last()!!.count)
        assertTrue(rawSnapshots.last()!!.colors.all { it == 0xFFFF0000.toInt() })

        assertEquals(
            true,
            endpoint.call("setVoxelRenderMode", mapOf("mode" to "centroids")).successValue,
        )
        assertEquals(VoxelRenderMode.CENTROIDS, configs.last()!!.voxelRenderMode)
        assertEquals(null, rawSnapshots.last())
        assertEquals(
            true,
            endpoint.call("setPointsEnabled", mapOf("enabled" to false)).successValue,
        )
        assertFalse(configs.last()!!.enabled)
        assertEquals(null, rawSnapshots.last())
        assertEquals(
            "PC_PROTOCOL_INVALID",
            endpoint.call("setVoxelRenderMode", mapOf("mode" to "invalid")).errorCode,
        )
        channel.dispose()
    }

    @Test
    fun `one in flight frame coalesces latest and acknowledgement drains it`() {
        val endpoint = FakeEndpoint()
        val channel = channel(endpoint)
        endpoint.call("init", initArguments())

        channel.onFrameForTest(0)
        channel.onFrameForTest(100_000_000)
        channel.onFrameForTest(200_000_000)
        val frames = endpoint.invocations.filter { it.method == "onPointCloudFrame" }
        assertEquals(1, frames.size)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), frames.single().argumentsMap()["ids"] as IntArray)

        frames.single().callback!!.success(mapOf("acceptedSequence" to 0L))
        val drained = endpoint.invocations.filter { it.method == "onPointCloudFrame" }
        assertEquals(2, drained.size)
        assertEquals(2L, drained.last().argumentsMap()["sequence"])
        drained.last().callback!!.success(mapOf("acceptedSequence" to 2L))
        assertEquals(
            1L,
            endpoint.call("getRenderingStats").successMap()["coalescedFrames"],
        )
    }

    @Test
    fun `ARCore samples do not render until Dart sends voxel centroids`() {
        val endpoint = FakeEndpoint()
        val snapshots = mutableListOf<CoveragePointRenderSnapshot?>()
        val channel = PointCloudMethodChannel(
            endpoint = endpoint,
            isDebuggable = true,
            onRendererStateChanged = { snapshot, _ -> snapshots += snapshot },
            sourceFactory = { SyntheticPointCloudSource() },
            callbackScheduler = FakeScheduler(),
        )
        endpoint.call("init", initArguments())
        val afterInit = snapshots.size

        channel.onFrameForTest(0)
        val afterFirstFrame = snapshots.size
        assertEquals(afterInit, afterFirstFrame)

        endpoint.call(
            "updateVoxels",
            mapOf(
                "epoch" to 1L,
                "keys" to longArrayOf(7L),
                "positionsWorld" to floatArrayOf(0.05f, 0.05f, -0.95f),
                "colors" to intArrayOf(0xFFFF0000.toInt()),
            ),
        )
        assertTrue(snapshots.size > afterFirstFrame)

        // Keep the render-only callbacks inside the 10 Hz acquisition window;
        // crossing it would intentionally create a new renderer revision.
        for (frame in 1..6) {
            channel.onFrameForTest(frame * 16_000_000L)
        }

        assertEquals(afterFirstFrame + 1, snapshots.size)
    }

    @Test
    fun `timeout and acknowledgement mismatch report stable errors`() {
        val endpoint = FakeEndpoint()
        val scheduler = FakeScheduler()
        val channel = channel(endpoint, scheduler)
        endpoint.call("init", initArguments())
        channel.onFrameForTest(0)
        scheduler.runNext()
        assertEquals("PC_CALLBACK_TIMEOUT", endpoint.errorCodes().single())

        channel.onFrameForTest(100_000_000)
        endpoint.invocations.last { it.method == "onPointCloudFrame" }
            .callback!!.success(mapOf("acceptedSequence" to 999L))
        assertEquals(
            listOf("PC_CALLBACK_TIMEOUT", "PC_PROTOCOL_INVALID"),
            endpoint.errorCodes(),
        )
        val invocationCount = endpoint.invocations.size
        channel.onFrameForTest(200_000_000)
        assertEquals(invocationCount, endpoint.invocations.size)
    }

    @Test
    fun `pause invalidates old callbacks and resume restores acquisition`() {
        val endpoint = FakeEndpoint()
        val channel = channel(endpoint)
        endpoint.call("init", initArguments())
        channel.setRendererMounted(true)

        channel.onFrameForTest(0)
        val first = endpoint.invocations.last { it.method == "onPointCloudFrame" }
        channel.pause()
        first.callback!!.success(mapOf("acceptedSequence" to 0L))
        channel.onFrameForTest(100_000_000)
        assertEquals(1, endpoint.invocations.count { it.method == "onPointCloudFrame" })

        channel.resume()
        channel.onFrameForTest(200_000_000)
        assertEquals(2, endpoint.invocations.count { it.method == "onPointCloudFrame" })
    }

    @Test
    fun `repeated invalid source data disables acquisition without disabling renderer`() {
        val endpoint = FakeEndpoint()
        val source = InvalidPointCloudSource()
        val channel = PointCloudMethodChannel(
            endpoint = endpoint,
            isDebuggable = true,
            sourceFactory = { source },
            callbackScheduler = FakeScheduler(),
        )
        endpoint.call("init", initArguments())
        channel.setRendererMounted(true)

        channel.onFrameForTest(0)
        channel.onFrameForTest(100_000_000)
        channel.onFrameForTest(200_000_000)

        assertEquals(listOf("PC_INVALID_POINT_DATA"), endpoint.errorCodes())
        val error = endpoint.invocations.last { it.method == "onError" }.argumentsMap()
        assertEquals(true, error["fatalToAcquisition"])
        assertEquals(false, error["fatalToRenderer"])
        val readiness = endpoint.invocations.last { it.method == "onRendererReady" }.argumentsMap()
        assertEquals(false, readiness["acquisitionReady"])
        assertEquals(true, readiness["rendererReady"])
        val stats = endpoint.call("getRenderingStats").successMap()
        assertEquals(3L, stats["invalidPointCloudAcquisitions"])

        channel.onFrameForTest(300_000_000)
        assertEquals(3L, source.acquisitionCount)
    }

    @Test
    fun `synthetic source is forbidden outside debug builds`() {
        val endpoint = FakeEndpoint()
        PointCloudMethodChannel(
            endpoint = endpoint,
            isDebuggable = false,
            sourceFactory = { SyntheticPointCloudSource() },
            callbackScheduler = FakeScheduler(),
        )
        val result = endpoint.call("init", initArguments(synthetic = true))
        assertEquals("PC_SYNTHETIC_FORBIDDEN", result.errorCode)
    }

    @Test
    fun `one hundred init dispose cycles release handlers and callbacks`() {
        repeat(100) {
            val endpoint = FakeEndpoint()
            val scheduler = FakeScheduler()
            channel(endpoint, scheduler)
            assertEquals(true, endpoint.call("init", initArguments()).successMap()["acquisitionReady"])
            assertEquals(true, endpoint.call("dispose").successValue)
            assertNull(endpoint.handler)
            assertTrue(scheduler.removed)
        }
    }

    private fun channel(
        endpoint: FakeEndpoint,
        scheduler: FakeScheduler = FakeScheduler(),
    ): PointCloudMethodChannel = PointCloudMethodChannel(
        endpoint = endpoint,
        isDebuggable = true,
        sourceFactory = { SyntheticPointCloudSource() },
        callbackScheduler = scheduler,
    )

    private fun initArguments(synthetic: Boolean = false): Map<String, Any> = mapOf(
        "version" to POINT_CLOUD_WIRE_VERSION,
        "renderCapacity" to 4_096,
        "defaultColor" to 0xFFFF0000.toInt(),
        "pointSizePx" to 6.0,
        "frameRateHz" to 10,
        "maxConsecutiveAcquisitionErrors" to 3,
        "minConfidence" to 0.3,
        "enabled" to true,
        "syntheticSource" to synthetic,
    )
}

private data class OutgoingInvocation(
    val method: String,
    val arguments: Any?,
    val callback: MethodChannel.Result?,
) {
    fun argumentsMap(): Map<*, *> = arguments as Map<*, *>
}

private class FakeEndpoint : PointCloudChannelEndpoint {
    var handler: MethodChannel.MethodCallHandler? = null
    val invocations = mutableListOf<OutgoingInvocation>()

    override fun setMethodCallHandler(handler: MethodChannel.MethodCallHandler?) {
        this.handler = handler
    }

    override fun invokeMethod(method: String, arguments: Any?, callback: MethodChannel.Result?) {
        invocations += OutgoingInvocation(method, arguments, callback)
    }

    fun call(method: String, arguments: Any? = null): RecordingResult {
        val result = RecordingResult()
        requireNotNull(handler).onMethodCall(MethodCall(method, arguments), result)
        return result
    }

    fun errorCodes(): List<String> = invocations
        .filter { it.method == "onError" }
        .map { it.argumentsMap()["code"] as String }
}

private class FakeScheduler : PointCloudCallbackScheduler {
    private val callbacks = ArrayDeque<() -> Unit>()
    var removed = false

    override fun postDelayed(callback: () -> Unit, delayMs: Long) {
        assertEquals(1_000L, delayMs)
        callbacks.addLast(callback)
    }

    override fun removeAll() {
        removed = true
        callbacks.clear()
    }

    fun runNext() = callbacks.removeFirst().invoke()
}

private class InvalidPointCloudSource : PointCloudSource {
    var acquisitionCount = 0L

    override fun acquire(frame: com.google.ar.core.Frame?): PointCloudSample? {
        acquisitionCount++
        return null
    }

    override fun diagnostics(): PointCloudSourceDiagnostics = PointCloudSourceDiagnostics(
        rawIds = 1,
        rawPointFloats = 4,
        nonFiniteRejectedPoints = acquisitionCount,
        invalidPointCloudAcquisitions = acquisitionCount,
    )
}

private class RecordingResult : MethodChannel.Result {
    var successValue: Any? = null
    var errorCode: String? = null
    var notImplemented = false

    override fun success(result: Any?) {
        successValue = result
    }

    override fun error(code: String, message: String?, details: Any?) {
        errorCode = code
    }

    override fun notImplemented() {
        notImplemented = true
    }

    @Suppress("UNCHECKED_CAST")
    fun successMap(): Map<String, Any> = successValue as Map<String, Any>
}
