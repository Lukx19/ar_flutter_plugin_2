package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.DigestOutputStream

/**
 * The one-pass, file-backed #121 seam.  Records are deliberately scalars:
 * this reader never gives a caller a mutable byte array or a retained decoded
 * mutation graph.  Returning false means the caller stopped consumption; it
 * is never a validated terminal success.
 */
internal interface M3PreparedIntentVisitor {
    fun onHeader(identity: M3PreparedIntentIdentity): Boolean
    fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean
    fun onRemovedId(id: Long): Boolean
    fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean
    fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean
    fun onDirtyLineage(sourceId: Long, targetId: Long): Boolean
    fun onTerminal(currentReceipt: M3PreparedIntentCurrentReceipt): Boolean

    companion object {
        val NONE = object : M3PreparedIntentVisitor {
            override fun onHeader(identity: M3PreparedIntentIdentity) = true
            override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = true
            override fun onRemovedId(id: Long) = true
            override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = true
            override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = true
            override fun onDirtyLineage(sourceId: Long, targetId: Long) = true
            override fun onTerminal(currentReceipt: M3PreparedIntentCurrentReceipt) = true
        }
    }
}

internal data class M3PreparedIntentIdentity(
    val sourceCut: M3CompactCanonicalCut,
    val commandId: String,
    val kind: M3PreparedMutationKind,
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val targetHighWater: Long,
    val targetLive: Int,
    val targetSource: Int,
    val targetSupport: Int,
    val targetLineage: Int,
    val targetGeometry: Long,
    val targetLineageRevision: Long,
    val walReceipt: M3PreparedIntentWalReceipt,
    val expectedCurrentReceipt: M3PreparedIntentCurrentReceipt,
) {
    companion object {
        fun from(header: M3DirtyIntentHeader, commandId: String, kind: M3PreparedMutationKind) = M3PreparedIntentIdentity(
            header.sourceCut, commandId, kind, header.commandHash, header.commandFingerprint, header.targetHighWater,
            header.targetLive, header.targetSource, header.targetSupport, header.targetLineage,
            header.targetGeometry, header.targetLineageRevision,
            M3PreparedIntentWalReceipt(header.walLength, header.walHash),
            M3PreparedIntentCurrentReceipt(header.currentLength, header.currentHash),
        )
    }
}

internal data class M3PreparedIntentWalReceipt(val length: Long, val hash: M3CanonicalReceiptBytes)
internal data class M3PreparedIntentCurrentReceipt(val length: Long, val hash: M3CanonicalReceiptBytes)
internal sealed interface M3PreparedIntentIdentityResult {
    data class Complete(val identity: M3PreparedIntentIdentity) : M3PreparedIntentIdentityResult
    data class Refused(val reason: M3PreparedIntentVisitRefusal) : M3PreparedIntentIdentityResult
}
internal sealed interface M3PreparedIntentCurrentReceiptResult {
    data class Complete(val receipt: M3PreparedIntentCurrentReceipt) : M3PreparedIntentCurrentReceiptResult
    data object Stopped : M3PreparedIntentCurrentReceiptResult
    data class Refused(val reason: M3PreparedIntentVisitRefusal) : M3PreparedIntentCurrentReceiptResult
}
internal sealed interface M3PreparedIntentVisitResult {
    data class Complete(val identity: M3PreparedIntentIdentity, val currentReceipt: M3PreparedIntentCurrentReceipt) : M3PreparedIntentVisitResult
    data object Stopped : M3PreparedIntentVisitResult
    data class Refused(val reason: M3PreparedIntentVisitRefusal) : M3PreparedIntentVisitResult
}
internal enum class M3PreparedIntentVisitRefusal {
    CLOSED,
    CORRUPT_INTENT,
    VISITOR_FAILURE,
    UNVERIFIABLE_LEGACY_STRUCTURAL_CARDINALITY,
}

/** The reader owns one fixed streaming page; decoded records never survive a callback. */
internal object M3PreparedIntentVisitorResources {
    const val STREAMING_SCRATCH_BYTES = 65_536
    const val RETAINED_DECODED_RECORD_BYTES = 0
    const val PHASE_PEAK_BYTES = STREAMING_SCRATCH_BYTES
}

internal class M3PreparedIntentStreamingVisitor(
    private val file: File,
    private val isClosed: () -> Boolean,
) {
    fun visit(visitor: M3PreparedIntentVisitor): M3PreparedIntentVisitResult {
        if (isClosed()) return refused(M3PreparedIntentVisitRefusal.CLOSED)
        val header = try { M3DirtyIntentHeader.read(file) } catch (_: Exception) { return refused(M3PreparedIntentVisitRefusal.CORRUPT_INTENT) }
        return try {
            FileInputStream(file).use { raw ->
                DataInputStream(BufferedInputStream(M3BoundedInputStream(raw, header.walOffset, header.walLength), M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)).use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val counter = M3CountingOutputStream()
                    val output = java.io.DataOutputStream(DigestOutputStream(counter, digest))
                    require(input.readInt() == WAL_MAGIC)
                    val bodyVersion = input.readInt(); require(bodyVersion in LEGACY_BODY_VERSION..BODY_VERSION)
                    output.writeInt(CURRENT_MAGIC); output.writeInt(bodyVersion)
                    copyHash(input, output, header.sourceCut.rootHash)
                    val command = readCommand(input); output.writeUTF(command)
                    val kindOrdinal = input.readInt(); require(kindOrdinal in M3PreparedMutationKind.entries.indices); output.writeInt(kindOrdinal)
                    val kind = M3PreparedMutationKind.entries[kindOrdinal]
                    val identity = M3PreparedIntentIdentity.from(header, command, kind)
                    copyHash(input, output, header.commandHash); copyHash(input, output, header.commandFingerprint)
                    require(input.readLong().also(output::writeLong) == header.targetHighWater)
                    require(input.readInt().also(output::writeInt) == header.targetLive)
                    require(input.readInt().also(output::writeInt) == header.targetSource)
                    require(input.readInt().also(output::writeInt) == header.targetSupport)
                    require(input.readInt().also(output::writeInt) == header.targetLineage)
                    require(input.readLong().also(output::writeLong) == header.targetGeometry)
                    require(input.readLong().also(output::writeLong) == header.targetLineageRevision)
                    if (!call { visitor.onHeader(identity) }) return stoppedOrClosed()
                    val rows = count(input, output, 100_000)
                    var previousRow = 0L
                    var newRows = 0
                    repeat(rows) {
                        val record = record(input, output)
                        require(record.id > 0 && unsignedAfter(record.id, previousRow)); previousRow = record.id
                        if (record.id >= header.sourceCut.nextSurfaceIdHighWater) newRows++
                        if (!call { visitor.onDirtyRow(record.id, record.x, record.y, record.z, record.normal, record.confidence, record.f0, record.f1, record.f2, record.f3) }) return stoppedOrClosed()
                    }
                    val removed = count(input, output, 100_000)
                    var previousRemoved = 0L
                    repeat(removed) {
                        val id = input.readLong(); output.writeLong(id)
                        require(id > 0 && id < header.sourceCut.nextSurfaceIdHighWater && unsignedAfter(id, previousRemoved)); previousRemoved = id
                        if (!call { visitor.onRemovedId(id) }) return stoppedOrClosed()
                    }
                    val removedSupports = if (bodyVersion >= BODY_VERSION) count(input, output, 300_000) else {
                        if (kind !in LEGACY_DERIVABLE_KINDS)
                            return refused(M3PreparedIntentVisitRefusal.UNVERIFIABLE_LEGACY_STRUCTURAL_CARDINALITY)
                        0
                    }
                    require(removedSupports <= header.sourceCut.supportCount)
                    val supports = count(input, output, 300_000)
                    var previousSupportTarget = 0L; var previousSupportSource = 0L
                    repeat(supports) {
                        val target = input.readLong(); output.writeLong(target); require(target > 0)
                        val record = record(input, output)
                        require(record.id > 0 && (target != previousSupportTarget || unsignedAfter(record.id, previousSupportSource)) && (previousSupportTarget == 0L || unsignedAfter(target, previousSupportTarget) || target == previousSupportTarget))
                        previousSupportTarget = target; previousSupportSource = record.id
                        if (!call { visitor.onDirtySupport(target, record.id, record.x, record.y, record.z, record.normal, record.confidence, record.f0, record.f1, record.f2, record.f3) }) return stoppedOrClosed()
                    }
                    val sources = count(input, output, 100_000)
                    var previousSource = 0L
                    repeat(sources) {
                        val record = record(input, output)
                        require(record.id >= header.sourceCut.nextSurfaceIdHighWater && unsignedAfter(record.id, previousSource)); previousSource = record.id
                        if (!call { visitor.onDirtySource(record.id, record.x, record.y, record.z, record.normal, record.confidence, record.f0, record.f1, record.f2, record.f3) }) return stoppedOrClosed()
                    }
                    val lineage = count(input, output, 200_000)
                    var previousLineageSource = 0L; var previousLineageTarget = 0L
                    repeat(lineage) {
                        val source = input.readLong(); val target = input.readLong(); output.writeLong(source); output.writeLong(target)
                        require(source > 0 && target > 0 && (source != previousLineageSource || unsignedAfter(target, previousLineageTarget)) && (previousLineageSource == 0L || unsignedAfter(source, previousLineageSource) || source == previousLineageSource))
                        previousLineageSource = source; previousLineageTarget = target
                        if (!call { visitor.onDirtyLineage(source, target) }) return stoppedOrClosed()
                    }
                    require(input.read() == -1)
                    require(newRows == sources && header.targetHighWater == header.sourceCut.nextSurfaceIdHighWater + newRows)
                    require(validCardinality(kind, header, rows, removed, removedSupports, supports, sources, lineage))
                    output.flush()
                    val receipt = M3PreparedIntentCurrentReceipt(counter.count, M3CanonicalReceiptBytes(digest.digest()))
                    require(receipt.length == header.currentLength && receipt.hash == header.currentHash)
                    if (!call { visitor.onTerminal(receipt) }) return stoppedOrClosed()
                    M3PreparedIntentVisitResult.Complete(identity, receipt)
                }
            }
        } catch (_: M3PreparedIntentVisitorFailure) {
            refused(M3PreparedIntentVisitRefusal.VISITOR_FAILURE)
        } catch (_: Exception) {
            refused(if (isClosed()) M3PreparedIntentVisitRefusal.CLOSED else M3PreparedIntentVisitRefusal.CORRUPT_INTENT)
        }
    }

    private fun validCardinality(kind: M3PreparedMutationKind, header: M3DirtyIntentHeader, rows: Int, removed: Int, removedSupports: Int, supports: Int, sources: Int, lineage: Int): Boolean {
        if (header.targetSource != header.sourceCut.sourceCount + sources) return false
        if (header.targetLive !in (header.sourceCut.liveSurfaceCount - removed)..(header.sourceCut.liveSurfaceCount - removed + rows)) return false
        val expectedSupport = try {
            Math.addExact(Math.subtractExact(header.sourceCut.supportCount.toLong(), removedSupports.toLong()), supports.toLong())
        } catch (_: ArithmeticException) { return false }
        if (expectedSupport != header.targetSupport.toLong()) return false
        return when (kind) {
            M3PreparedMutationKind.FEATURE_ADD -> rows == 1 && removed == 0 && supports == 1 && sources == 1 && lineage == 0 && header.targetLive == header.sourceCut.liveSurfaceCount + 1 && header.targetSupport == header.sourceCut.supportCount + 1 && header.targetLineage == header.sourceCut.lineageCount
            M3PreparedMutationKind.FEATURE_REFINE -> rows == 1 && removed == 0 && supports == 0 && sources == 0 && lineage == 0 && header.targetLive == header.sourceCut.liveSurfaceCount && header.targetSupport == header.sourceCut.supportCount && header.targetLineage == header.sourceCut.lineageCount
            M3PreparedMutationKind.CREATE -> header.sourceCut.liveSurfaceCount == 0 && header.sourceCut.sourceCount == 0 && header.sourceCut.supportCount == 0 && header.sourceCut.lineageCount == 0 && removed == 0 && rows > 0 && supports == rows && sources == rows && lineage == 0 && header.targetLive == rows && header.targetSupport == rows && header.targetLineage == 0
            else -> rows > 0 && removed > 0 && lineage.toLong() == rows.toLong() * removed.toLong() && header.targetLive == header.sourceCut.liveSurfaceCount - removed + rows && header.targetLineage == header.sourceCut.lineageCount + lineage
        }
    }

    private fun count(input: DataInputStream, output: java.io.DataOutputStream, limit: Int): Int = input.readInt().also { require(it in 0..limit); output.writeInt(it) }
    private fun readCommand(input: DataInputStream): String {
        input.mark(2)
        val encodedBytes = input.readUnsignedShort()
        require(encodedBytes in 1..MAX_COMMAND_BYTES && input.markSupported())
        input.reset()
        return input.readUTF().also { command ->
            require(command.isNotBlank() && modifiedUtf8Length(command) == encodedBytes.toLong())
        }
    }
    private fun copyHash(input: DataInputStream, output: java.io.DataOutputStream, expected: M3CanonicalReceiptBytes) {
        val first = input.readLong(); val second = input.readLong(); val third = input.readLong(); val fourth = input.readLong()
        require(expected.matchesWords(first, second, third, fourth))
        output.writeLong(first); output.writeLong(second); output.writeLong(third); output.writeLong(fourth)
    }
    private fun record(input: DataInputStream, output: java.io.DataOutputStream): Record {
        val id = input.readLong(); val x = input.readInt(); val y = input.readInt(); val z = input.readInt(); val normal = input.readInt(); val confidence = input.readInt()
        require(id in 1 until UINT32_END && normal in 0..0xffff && signedByte(normal ushr 8) in -127..127 && signedByte(normal) in -127..127 && confidence in 0..255)
        output.writeLong(id); output.writeInt(x); output.writeInt(y); output.writeInt(z); output.writeInt(normal); output.writeInt(confidence)
        val f0 = input.readLong(); val f1 = input.readLong(); val f2 = input.readLong(); val f3 = input.readLong()
        output.writeLong(f0); output.writeLong(f1); output.writeLong(f2); output.writeLong(f3)
        return Record(id, x, y, z, normal, confidence, f0, f1, f2, f3)
    }
    private fun call(block: () -> Boolean) = !isClosed() && try { block() } catch (_: Exception) { throw M3PreparedIntentVisitorFailure }
    private fun stoppedOrClosed() = if (isClosed()) refused(M3PreparedIntentVisitRefusal.CLOSED) else M3PreparedIntentVisitResult.Stopped
    private fun refused(reason: M3PreparedIntentVisitRefusal) = M3PreparedIntentVisitResult.Refused(reason)
    private fun unsignedAfter(value: Long, previous: Long) = previous == 0L || java.lang.Long.compareUnsigned(value, previous) > 0
    private fun signedByte(value: Int) = (value and 0xff).let { if (it < 128) it else it - 256 }
    private data class Record(val id: Long, val x: Int, val y: Int, val z: Int, val normal: Int, val confidence: Int, val f0: Long, val f1: Long, val f2: Long, val f3: Long)
    private companion object {
        const val WAL_MAGIC = 0x4d33574c
        const val CURRENT_MAGIC = 0x4d334350
        const val LEGACY_BODY_VERSION = 1
        const val BODY_VERSION = 2
        const val MAX_COMMAND_BYTES = 256
        const val UINT32_END = 0x1_0000_0000L
        val LEGACY_DERIVABLE_KINDS = setOf(
            M3PreparedMutationKind.FEATURE_ADD,
            M3PreparedMutationKind.FEATURE_REFINE,
            M3PreparedMutationKind.CREATE,
        )
    }
}

private object M3PreparedIntentVisitorFailure : RuntimeException()

private class M3CountingOutputStream : OutputStream() {
    var count = 0L
    override fun write(value: Int) { count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
}
