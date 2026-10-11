package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestRecordingLayout
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Debug-build ingest recording writer (contract 2.11): a 32 B header (magic
 * `C3IR`, version, device model hash, created wall time, message count) then
 * `u32 length` plus one ingest message, repeated. Recordings are the replay
 * fixtures of the grid host tests (`FakeIngestSource`).
 *
 * [record] runs on the reply path: it copies the message into one of
 * [SLOTS] preallocated slots and returns; a writer thread appends slots to the
 * file. A message that finds no free slot is dropped and counted, so the reply
 * path never waits for storage. Slots are allocated on the first [start].
 */
internal class IngestRecorder(
    private val directory: File,
    private val deviceModel: String?,
    private val wallClockNs: () -> Long = { System.currentTimeMillis() * 1_000_000L },
) {
    companion object {
        const val SLOTS = 4

        /** FNV-1a 64 of the device model, the recording header's device hash. */
        fun deviceModelHash(model: String?): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in model.orEmpty()) {
                h = h xor (c.code.toLong() and 0xffff)
                h *= 0x100000001b3L
            }
            return h
        }

        /** Writes the 32 B header into [b] at index 0. */
        fun writeHeader(b: ByteBuffer, deviceModelHash: Long, createdTimeNs: Long, messageCount: Int) {
            for (i in 0 until IngestRecordingLayout.HEADER_BYTES step 8) b.putLong(i, 0L)
            b.putInt(0, IngestRecordingLayout.MAGIC)
            b.putShort(4, IngestRecordingLayout.VERSION.toShort())
            b.putLong(IngestRecordingLayout.DEVICE_MODEL_HASH, deviceModelHash)
            b.putLong(IngestRecordingLayout.CREATED_TIME_NS, createdTimeNs)
            b.putInt(IngestRecordingLayout.MESSAGE_COUNT, messageCount)
        }
    }

    private val lock = Object()
    private var slots: Array<ByteBuffer>? = null
    private val slotLength = IntArray(SLOTS)
    private var head = 0
    private var count = 0
    private var channel: FileChannel? = null
    private var file: RandomAccessFile? = null
    private var writer: Thread? = null
    private var active = false
    private val header = ByteBuffer.allocate(IngestRecordingLayout.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    @Volatile var path: String? = null
        private set
    @Volatile var messages = 0
        private set
    @Volatile var dropped = 0L
        private set
    @Volatile var writeFailed = false
        private set

    val isRecording: Boolean get() = synchronized(lock) { active }

    /** Opens a new recording file; returns false if one is open or the file cannot be created. */
    fun start(): Boolean {
        synchronized(lock) {
            if (active) return false
            val buffers = slots ?: Array(SLOTS) {
                ByteBuffer.allocateDirect(IngestRecordingLayout.LENGTH_PREFIX_BYTES + IngestMessageLayout.MAX_MESSAGE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            }.also { slots = it }
            check(buffers.size == SLOTS)
            return try {
                directory.mkdirs()
                val created = wallClockNs()
                var target = File(directory, "ingest-$created.c3ir")
                var k = 1
                while (target.exists()) target = File(directory, "ingest-$created-${k++}.c3ir") // never overwrite
                val raf = RandomAccessFile(target, "rw")
                writeHeader(header, deviceModelHash(deviceModel), created, 0)
                header.clear()
                raf.channel.write(header)
                file = raf
                channel = raf.channel
                path = target.path
                messages = 0
                dropped = 0
                writeFailed = false
                head = 0
                count = 0
                active = true
                writer = Thread(::drain, "capture3d-grid-ingest-recorder").apply {
                    isDaemon = true
                    start()
                }
                true
            } catch (_: Exception) {
                writeFailed = true
                false
            }
        }
    }

    /** Copies one replied message; never blocks on storage. */
    fun record(message: ByteBuffer, length: Int) {
        synchronized(lock) {
            if (!active) return
            val buffers = slots ?: return
            if (count == SLOTS) {
                dropped++
                return
            }
            val index = (head + count) % SLOTS
            val slot = buffers[index]
            slot.clear()
            slot.putInt(0, length)
            message.limit(length)
            message.position(0)
            slot.position(IngestRecordingLayout.LENGTH_PREFIX_BYTES)
            slot.put(message)
            message.clear()
            slotLength[index] = IngestRecordingLayout.LENGTH_PREFIX_BYTES + length
            count++
            lock.notifyAll()
        }
    }

    /** Flushes queued messages, patches the message count and closes the file. */
    fun stop(): String? {
        val thread: Thread?
        synchronized(lock) {
            if (!active) return null
            active = false
            thread = writer
            writer = null
            lock.notifyAll()
        }
        thread?.join(5_000)
        synchronized(lock) {
            try {
                channel?.let { c ->
                    header.clear()
                    header.putInt(0, messages)
                    header.limit(4)
                    c.write(header, IngestRecordingLayout.MESSAGE_COUNT.toLong())
                    header.clear()
                    c.force(true)
                }
            } catch (_: Exception) {
                writeFailed = true
            } finally {
                runCatching { file?.close() }
                file = null
                channel = null
            }
            return path
        }
    }

    private fun drain() {
        while (true) {
            val slot: ByteBuffer
            val length: Int
            synchronized(lock) {
                while (count == 0 && active) lock.wait()
                if (count == 0) return
                slot = slots!![head]
                length = slotLength[head]
            }
            try {
                slot.limit(length)
                slot.position(0)
                val c = channel
                while (c != null && slot.hasRemaining()) c.write(slot)
            } catch (_: Exception) {
                writeFailed = true
            }
            synchronized(lock) {
                head = (head + 1) % SLOTS
                count--
                if (!writeFailed) messages++
            }
        }
    }
}
