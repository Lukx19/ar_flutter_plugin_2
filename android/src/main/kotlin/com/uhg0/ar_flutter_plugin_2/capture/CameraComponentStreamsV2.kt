package com.uhg0.ar_flutter_plugin_2.capture

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded zero-copy view over one Camera2 plane. Closing the store-owned input
 * releases the originating Image exactly once through [closeOwner].
 */
internal class CameraPlaneInputStreamV2(
    source: ByteBuffer,
    private val closeOwner: () -> Unit,
) : InputStream() {
    private val buffer = source.slice()
    private val closed = AtomicBoolean(false)

    override fun read(): Int {
        check(!closed.get()) { "Camera component stream is closed" }
        return if (buffer.hasRemaining()) buffer.get().toInt() and 0xff else -1
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        check(!closed.get()) { "Camera component stream is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= target.size)
        if (length == 0) return 0
        if (!buffer.hasRemaining()) return -1
        val count = minOf(length, buffer.remaining())
        buffer.get(target, offset, count)
        return count
    }

    override fun available(): Int = if (closed.get()) 0 else buffer.remaining()

    override fun close() {
        if (closed.compareAndSet(false, true)) closeOwner()
    }
}

/** A fixed-capacity producer pipe whose failure is observable by the store. */
internal class BoundedProducerInputStreamV2 private constructor(
    private val input: PipedInputStream,
    private val failure: AtomicReference<Throwable?>,
    private val closeOwner: () -> Unit,
) : InputStream() {
    private val closed = AtomicBoolean(false)

    override fun read(): Int = checked(input.read())

    override fun read(target: ByteArray, offset: Int, length: Int): Int =
        checked(input.read(target, offset, length))

    private fun checked(result: Int): Int {
        if (result < 0) failure.get()?.let { throw IOException("Camera component producer failed", it) }
        return result
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { input.close() }
            closeOwner()
        }
    }

    companion object {
        fun start(
            executor: Executor,
            capacityBytes: Int,
            closeOwner: () -> Unit,
            produce: (OutputStream) -> Unit,
        ): BoundedProducerInputStreamV2 {
            require(capacityBytes in 4 * 1024..1024 * 1024)
            val input = PipedInputStream(capacityBytes)
            val output = PipedOutputStream(input)
            val failure = AtomicReference<Throwable?>()
            val stream = BoundedProducerInputStreamV2(input, failure, closeOwner)
            try {
                executor.execute {
                    try {
                        output.use(produce)
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                        runCatching { output.close() }
                    }
                }
            } catch (error: Throwable) {
                runCatching { output.close() }
                stream.close()
                throw error
            }
            return stream
        }
    }
}
