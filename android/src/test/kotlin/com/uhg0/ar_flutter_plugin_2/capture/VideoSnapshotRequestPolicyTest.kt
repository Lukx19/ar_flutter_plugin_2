package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.*
import org.junit.Test

class VideoSnapshotRequestPolicyTest {
    private data class Request(
        val values: MutableMap<String, Any?>,
        var targets: List<String> = emptyList(),
        var tag: String? = null,
    )
    private class Adapter : VideoSnapshotRequestAdapter<Request, String> {
        override val captureIntentKey = "android.control.captureIntent"
        override val stillCaptureIntent = 2
        override val videoSnapshotIntent = 4
        var calls = 0
        var ordinary = true
        var failTemplate = false
        var extra = false
        var changeQuality = false
        var changeTag = false
        var cloneArrays = false
        override fun isOrdinaryPrimaryRequest(request: Request): Boolean { calls++; return ordinary }
        override fun controls(request: Request): Map<String, Any?> { calls++; return request.values.toMap() }
        override fun createVideoSnapshot(): Request {
            calls++
            check(!failTemplate) { "Unsupported VIDEO_SNAPSHOT template" }
            return Request(mutableMapOf<String, Any?>(captureIntentKey to 4).apply {
                if (extra) put("android.control.enableZsl", true)
            })
        }
        override fun setControl(request: Request, key: String, value: Any) {
            calls++
            request.values[key] = when {
                changeQuality && key == "android.jpeg.quality" -> 90.toByte()
                cloneArrays && value is IntArray -> value.copyOf()
                cloneArrays && value is Array<*> -> value.map { if (it is IntArray) it.copyOf() else it }.toTypedArray()
                else -> value
            }
        }
        override fun copyTargetsAndTag(baseline: Request, candidate: Request) {
            calls++; candidate.targets = baseline.targets.toList()
            candidate.tag = if (changeTag) "other-generation" else baseline.tag
        }
        override fun targetsAndTagMatch(baseline: Request, candidate: Request): Boolean {
            calls++; return baseline.targets == candidate.targets && baseline.tag == candidate.tag
        }
    }
    private fun baseline() = Request(mutableMapOf(
        "android.control.captureIntent" to 2,
        "android.control.aeTargetFpsRange" to intArrayOf(7, 30),
        "android.jpeg.quality" to 95.toByte(),
        "android.scaler.cropRegion" to intArrayOf(0, 0, 4128, 3096),
        "android.edge.mode" to 2,
        "android.noiseReduction.mode" to 2,
        "android.tonemap.mode" to 2,
        "android.shading.mode" to 2,
        "android.colorCorrection.aberrationMode" to 2,
        "android.control.aeMode" to 1,
        "android.control.afMode" to 1,
        "android.control.awbMode" to 1,
        "android.control.afRegions" to arrayOf(intArrayOf(0, 0, 100, 100, 1)),
    ), listOf("arCore", "jpeg"), "owned-current-generation")
    private fun rejected(adapter: Adapter, source: Request = baseline()) {
        try { VideoSnapshotRequestPolicy.transform(true, source, adapter); fail("Must reject") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun disabledReturnsSameRequestWithoutReadsOrCreation() {
        val source = baseline(); val adapter = Adapter()
        assertSame(source, VideoSnapshotRequestPolicy.transform(false, source, adapter))
        assertEquals(0, adapter.calls)
    }
    @Test fun copiesAllControlsWithBaselineFpsQualityProcessingCropAndExactTargetsTag() {
        val source = baseline(); val adapter = Adapter().apply { cloneArrays = true }
        val candidate = VideoSnapshotRequestPolicy.transform(true, source, adapter)
        assertNotSame(source, candidate)
        assertEquals(4, candidate.values["android.control.captureIntent"])
        assertArrayEquals(intArrayOf(7, 30), candidate.values["android.control.aeTargetFpsRange"] as IntArray)
        assertEquals(95.toByte(), candidate.values["android.jpeg.quality"])
        assertEquals(source.targets, candidate.targets); assertEquals(source.tag, candidate.tag)
        assertEquals(source.values.keys, candidate.values.keys)
    }
    @Test fun rejectsDestinationOnlyTemplateDefaults() { rejected(Adapter().apply { extra = true }) }
    @Test fun rejectsUnexpectedQualityReadback() { rejected(Adapter().apply { changeQuality = true }) }
    @Test fun rejectsNullBaselineControl() {
        rejected(Adapter(), baseline().apply { values["android.control.aeMode"] = null })
    }
    @Test fun rejectsForeignTargetTag() { rejected(Adapter().apply { changeTag = true }) }
    @Test fun rejectsReprocessOrPhysicalOverrideBeforeTemplateCreation() {
        val adapter = Adapter().apply { ordinary = false }; rejected(adapter)
        assertEquals(1, adapter.calls)
    }
    @Test fun unsupportedTemplatePropagatesWithoutFallback() {
        val adapter = Adapter().apply { failTemplate = true }
        try { VideoSnapshotRequestPolicy.transform(true, baseline(), adapter); fail("Must reject") }
        catch (error: IllegalStateException) { assertEquals("Unsupported VIDEO_SNAPSHOT template", error.message) }
    }
}
