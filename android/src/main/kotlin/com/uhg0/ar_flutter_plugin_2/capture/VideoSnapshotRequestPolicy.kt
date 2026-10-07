package com.uhg0.ar_flutter_plugin_2.capture

/** Typed public Camera2 controls; request construction and session-owned targets stay in the adapter. */
internal interface VideoSnapshotRequestAdapter<R, K> {
    val captureIntentKey: K
    val stillCaptureIntent: Any
    val videoSnapshotIntent: Any
    fun controls(request: R): Map<K, Any?>
    fun createVideoSnapshot(): R
    fun setControl(request: R, key: K, value: Any)
    fun copyTargetsAndTag(baseline: R, candidate: R)
    fun targetsAndTagMatch(baseline: R, candidate: R): Boolean
    fun isOrdinaryPrimaryRequest(request: R): Boolean
}

/** Opt-in template experiment. Every populated public control except intent must read back unchanged. */
internal object VideoSnapshotRequestPolicy {
    fun <R, K> transform(enabled: Boolean, baseline: R, adapter: VideoSnapshotRequestAdapter<R, K>): R {
        if (!enabled) return baseline
        require(adapter.isOrdinaryPrimaryRequest(baseline)) {
            "Video snapshot requires an ordinary primary-camera request without reprocessing or physical overrides"
        }
        val source = adapter.controls(baseline)
        require(source.values.none { it == null }) { "Baseline public control has a null readback" }
        require(source[adapter.captureIntentKey] == adapter.stillCaptureIntent) {
            "Video snapshot baseline must retain STILL capture intent"
        }
        val candidate = adapter.createVideoSnapshot()
        for ((key, value) in source) {
            if (key != adapter.captureIntentKey) adapter.setControl(candidate, key, checkNotNull(value))
        }
        adapter.copyTargetsAndTag(baseline, candidate)
        val result = adapter.controls(candidate)
        require(result.values.none { it == null }) { "Video snapshot public control has a null readback" }
        require(result[adapter.captureIntentKey] == adapter.videoSnapshotIntent) {
            "Video snapshot template did not retain VIDEO_SNAPSHOT intent"
        }
        val sourceKeys = source.keys - adapter.captureIntentKey
        val resultKeys = result.keys - adapter.captureIntentKey
        require(sourceKeys == resultKeys) { "Video snapshot has extra or missing public controls" }
        require(sourceKeys.all { deepValueEquals(source[it], result[it]) }) {
            "Video snapshot public control differs from the STILL baseline"
        }
        require(adapter.targetsAndTagMatch(baseline, candidate)) {
            "Video snapshot changed session-owned targets or tag"
        }
        return candidate
    }

    private fun deepValueEquals(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a is Array<*> && b is Array<*> ->
            a.size == b.size && a.indices.all { deepValueEquals(a[it], b[it]) }
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is ShortArray && b is ShortArray -> a.contentEquals(b)
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is LongArray && b is LongArray -> a.contentEquals(b)
        a is FloatArray && b is FloatArray -> a.contentEquals(b)
        a is DoubleArray && b is DoubleArray -> a.contentEquals(b)
        a is CharArray && b is CharArray -> a.contentEquals(b)
        a is BooleanArray && b is BooleanArray -> a.contentEquals(b)
        else -> a == b
    }
}
