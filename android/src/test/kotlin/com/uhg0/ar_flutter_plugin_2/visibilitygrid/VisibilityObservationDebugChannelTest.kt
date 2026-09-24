package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityObservationDebugChannelTest {
    @Test
    fun `release pressure snapshot is rejected without reading owners or mutating health`() {
        val messenger = MethodTestMessenger()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { null },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) = Unit

                override fun admitDepth(observation: VisibilityDepthObservation) = Unit
            },
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
        )
        var pressureOwnerReads = 0
        val channel = VisibilityObservationDebugChannel(
            messenger = messenger,
            viewId = 75,
            isDebuggable = false,
            runtime = runtime,
            ownership = { null },
            gate = VisibilityObservationDebugGate(),
            pressureOwners = {
                pressureOwnerReads++
                VisibilityPressureOwnerScalars()
            },
        )
        try {
            val before = runtime.snapshot()
            val result = RecordingResult()

            MethodChannel(messenger, "visibility_observation_v2_75").invokeMethod(
                "pressureSnapshot",
                null,
                result,
            )

            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, result.successCount)
            assertEquals(1, result.errorCount)
            assertEquals("VG_PROTOCOL_INVALID", result.errorCode)
            assertEquals(0, pressureOwnerReads)
            assertEquals(before, runtime.snapshot())
        } finally {
            channel.dispose()
            runtime.close()
        }
    }
}
