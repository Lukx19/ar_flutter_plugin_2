package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kind 6 session events (contract 2.4): tracking lost and regained, AR
 * session restarted, depth mode changed, recording started and stopped. Each
 * event is queued in order (never replaced). Callers on different threads are
 * serialised; the message buffer is allocated once.
 */
internal class SessionEventEmitter(
    private val sink: IngestMessageSink,
    private val clock: IngestClock,
) {
    private val buffer = ByteBuffer.allocateDirect(
        IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.SESSION_EVENT_BYTES,
    ).order(ByteOrder.LITTLE_ENDIAN)
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    @Volatile var emitted = 0L; private set

    /** Emits [event] with [value]; [sampleNs] is the AR timestamp it refers to (0 if none). */
    @Synchronized
    fun emit(event: Int, value: Long, sampleNs: Long, trackingState: Int, groupEpoch: Int) {
        val b = buffer
        IngestMessageWriter.begin(b, IngestMessageLayout.KIND_SESSION_EVENT)
        IngestMessageWriter.payloadBytes(b, IngestMessageLayout.SESSION_EVENT_BYTES)
        IngestMessageWriter.timing(b, sampleNs, sampleNs, clock.nowNs())
        IngestMessageWriter.pose(b, trackingState, IngestMessageLayout.POSE_MATCH_MISSING, groupEpoch, 0, identity)
        val p = IngestMessageLayout.HEADER_BYTES
        b.putLong(p, 0L)
        b.put(p, event.toByte())
        b.putLong(p + 8, value)
        if (sink.offer(b, IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.SESSION_EVENT_BYTES)) emitted++
    }
}
