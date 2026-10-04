package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

/** Shared production rule for stream style acceptance and retained revision. */
object StyleRevisionSemantics {
    fun expectedRevision(currentRevision: Long, hasStyleRecords: Boolean): Long =
        if (hasStyleRecords) Math.addExact(currentRevision, 1L) else currentRevision

    fun accepts(currentRevision: Long, nextStyleRevision: Long, hasStyleRecords: Boolean): Boolean =
        nextStyleRevision == expectedRevision(currentRevision, hasStyleRecords)

    fun committedRevision(currentRevision: Long, nextStyleRevision: Long, hasStyleRecords: Boolean): Long {
        require(accepts(currentRevision, nextStyleRevision, hasStyleRecords)) {
            "Style revision does not match the retained baseline"
        }
        return if (hasStyleRecords) nextStyleRevision else currentRevision
    }
}
