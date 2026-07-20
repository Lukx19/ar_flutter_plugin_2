package com.uhg0.ar_flutter_plugin_2.pointcloud

class LatestPointCloudQueue {
    private var inFlight: PointCloudSample? = null
    private var pending: PointCloudSample? = null
    var coalescedCount: Long = 0
        private set

    @Synchronized
    fun offer(sample: PointCloudSample): PointCloudSample? {
        if (inFlight == null) {
            inFlight = sample
            return sample
        }
        if (pending != null) coalescedCount++
        pending = sample
        return null
    }

    @Synchronized
    fun acknowledge(sequence: Long): PointCloudSample? {
        val current = inFlight ?: return null
        if (current.sequence != sequence) return null
        val next = pending
        pending = null
        inFlight = next
        return next
    }

    @Synchronized
    fun failCurrent(): PointCloudSample? {
        val current = inFlight ?: return null
        return acknowledge(current.sequence)
    }

    @Synchronized
    fun fail(sequence: Long): PointCloudQueueFailure? {
        if (inFlight?.sequence != sequence) return null
        return PointCloudQueueFailure(acknowledge(sequence))
    }

    @Synchronized
    fun clear() {
        inFlight = null
        pending = null
        coalescedCount = 0
    }

    @Synchronized
    fun stateCounts(): Pair<Int, Int> =
        Pair(if (inFlight == null) 0 else 1, if (pending == null) 0 else 1)
}

data class PointCloudQueueFailure(val next: PointCloudSample?)
