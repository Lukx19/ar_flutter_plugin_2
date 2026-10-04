package com.uhg0.ar_flutter_plugin_2.capture

import java.security.MessageDigest

/** Result of one bounded copy or digest pass over a durable component. */
internal data class CaptureCopyReceipt(
    val length: Long,
    val digest: ByteArray,
)

/**
 * One serial durable copy consumer's reusable 64 KiB transfer and SHA-256
 * state. Digest operations reset their state and return an independent result;
 * plain transfers do not hash. Callers never retain the mutable transfer buffer.
 */
internal class CaptureCopyWorkspace(
    val capacityBytes: Int = COPY_BUFFER_BYTES,
) {
    init {
        require(capacityBytes in 1..COPY_BUFFER_BYTES) {
            "copy workspace capacity must be between 1 and $COPY_BUFFER_BYTES bytes"
        }
    }

    private val buffer = ByteArray(capacityBytes)
    private val digest = MessageDigest.getInstance("SHA-256")

    /** Copies through the owned buffer without creating or updating a digest. */
    @Synchronized
    fun transfer(
        read: (ByteArray, Int, Int) -> Int,
        write: (ByteArray, Int, Int) -> Unit,
    ) {
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count > 0) write(buffer, 0, count)
        }
    }

    @Synchronized
    fun copy(
        read: (ByteArray, Int, Int) -> Int,
        write: (ByteArray, Int, Int) -> Unit,
    ): CaptureCopyReceipt {
        digest.reset()
        var length = 0L
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            write(buffer, 0, count)
            digest.update(buffer, 0, count)
            length = Math.addExact(length, count.toLong())
        }
        return CaptureCopyReceipt(length, digest.digest())
    }

    @Synchronized
    fun digest(read: (ByteArray, Int, Int) -> Int): CaptureCopyReceipt {
        digest.reset()
        var length = 0L
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            length = Math.addExact(length, count.toLong())
        }
        return CaptureCopyReceipt(length, digest.digest())
    }

    companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
