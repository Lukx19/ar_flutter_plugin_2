package com.uhg0.ar_flutter_plugin_2.m0

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Directory durability policy for schema-5 atomic publication. */
fun interface M0DirectorySync {
    fun sync(directory: File)

    companion object {
        /** Keeps the JVM/reference campaign portable when the host rejects directory fsync. */
        val bestEffort: M0DirectorySync = M0DirectorySync { directory ->
            runCatching { M0AndroidDirectorySync.sync(directory) }
        }

        /** Fails the caller when the Android filesystem cannot fsync the directory. */
        val strictAndroid: M0DirectorySync = M0DirectorySync { directory ->
            M0AndroidDirectorySync.sync(directory)
        }
    }
}

/** The complete canonical/coverage cut that one schema-5 root exposes. */
data class M0RegionPairCut(
    val region: M0RegionCoordinate,
    val generation: Long,
    val geometryRevision: Long,
    val coverageRevision: Long,
    val captureEvaluatedThrough: Long,
    val pendingThrough: Long,
) {
    val isCompatible: Boolean
        get() = generation > 0 &&
            geometryRevision >= 0 &&
            coverageRevision >= 0 &&
            captureEvaluatedThrough >= 0 &&
            pendingThrough >= 0 &&
            pendingThrough < captureEvaluatedThrough
}

enum class M0DurableCutFaultPoint {
    beforeCanonicalWrite,
    beforeCoverageWrite,
    afterCanonicalObject,
    afterCoverageObject,
    beforeManifestSync,
    afterManifestSync,
    beforeRootSwitch,
    afterRootPersistBeforeRootSwitch,
    processDeathBeforeRootSwitch,
    beforeReceipt,
    afterReceipt,
    afterRootSwitch,
    beforeActivation,
    afterActivation,
    beforeEviction,
    afterEviction,
    beforeMigration,
    afterMigration,
    beforeTombstone,
    afterTombstone,
}

val M0DurableCutFaultPoint.publishesNewRoot: Boolean
    get() = when (this) {
        M0DurableCutFaultPoint.afterRootSwitch,
        M0DurableCutFaultPoint.beforeActivation,
        M0DurableCutFaultPoint.afterActivation,
        M0DurableCutFaultPoint.beforeEviction,
        M0DurableCutFaultPoint.afterEviction,
        M0DurableCutFaultPoint.beforeMigration,
        M0DurableCutFaultPoint.afterMigration,
        M0DurableCutFaultPoint.beforeTombstone,
        M0DurableCutFaultPoint.afterTombstone -> true
        else -> false
    }

data class M0DurableCutCommitResult(
    val published: Boolean,
    val fault: M0DurableCutFaultPoint?,
    val visibleRootId: Long,
    val stagingCleared: Boolean,
)

/** Exact 256-byte schema-5 alternating root pointer. */
data class M0Schema5RootPointerV1(
    val slot: M0Schema5PointerSlot,
    val pointerGeneration: Long,
    val rootRevision: Long,
    val completeRootBytes: Long,
    val currentRootSha256: ByteArray,
    val durableCommitId: ByteArray,
    val predecessorRootSha256: ByteArray,
    val secondPredecessorRootSha256: ByteArray,
    val priorPointerGeneration: Long,
    val scopeUuid: ByteArray,
    val committedUnixNanos: Long,
    val rootArtifactType: Int,
) {
    init {
        require(pointerGeneration > 0 && rootRevision > 0 && completeRootBytes > 0)
        require(priorPointerGeneration >= 0 && priorPointerGeneration < pointerGeneration)
        require(pointerGeneration == 1L || priorPointerGeneration > 0)
        require(rootArtifactType in setOf(1, 3, 31))
        require(currentRootSha256.size == 32 && !currentRootSha256.allZero())
        require(durableCommitId.size == 16 && !durableCommitId.allZero())
        require(predecessorRootSha256.size == 32)
        require(secondPredecessorRootSha256.size == 32)
        require(scopeUuid.size == 16)
    }

    fun encode(): ByteArray {
        val bytes = ByteArray(ENCODED_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 'S'.code.toByte()
        bytes[1] = '5'.code.toByte()
        bytes[2] = 'R'.code.toByte()
        bytes[3] = 'P'.code.toByte()
        data.putShort(4, SCHEMA.toShort())
        data.putShort(6, VERSION.toShort())
        data.put(8, slot.wireValue.toByte())
        data.put(9, 0)
        data.putShort(10, 0)
        data.putLong(12, pointerGeneration)
        data.putLong(20, rootRevision)
        data.putLong(28, completeRootBytes)
        currentRootSha256.copyInto(bytes, 36)
        durableCommitId.copyInto(bytes, 68)
        predecessorRootSha256.copyInto(bytes, 84)
        secondPredecessorRootSha256.copyInto(bytes, 116)
        data.putLong(148, priorPointerGeneration)
        scopeUuid.copyInto(bytes, 156)
        data.putLong(172, committedUnixNanos)
        data.putShort(180, rootArtifactType.toShort())
        data.putInt(252, crc32c(bytes, 252))
        return bytes
    }

    companion object {
        const val ENCODED_BYTES = 256
        private const val SCHEMA = 5
        private const val VERSION = 1

        fun decode(bytes: ByteArray): M0Schema5RootPointerV1 {
            require(bytes.size == ENCODED_BYTES)
            require(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('S'.code.toByte(), '5'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte())))
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.getShort(4).toInt() and 0xffff == SCHEMA)
            require(data.getShort(6).toInt() and 0xffff == VERSION)
            require(data.get(9).toInt() == 0 && data.getShort(10).toInt() == 0)
            require(data.getInt(252) == crc32c(bytes, 252))
            require(bytes.copyOfRange(182, 252).all { it == 0.toByte() })
            val slot = when (data.get(8).toInt() and 0xff) {
                0 -> M0Schema5PointerSlot.A
                1 -> M0Schema5PointerSlot.B
                else -> error("Schema-5 root pointer slot is invalid")
            }
            return M0Schema5RootPointerV1(
                slot = slot,
                pointerGeneration = data.getLong(12),
                rootRevision = data.getLong(20),
                completeRootBytes = data.getLong(28),
                currentRootSha256 = bytes.copyOfRange(36, 68),
                durableCommitId = bytes.copyOfRange(68, 84),
                predecessorRootSha256 = bytes.copyOfRange(84, 116),
                secondPredecessorRootSha256 = bytes.copyOfRange(116, 148),
                priorPointerGeneration = data.getLong(148),
                scopeUuid = bytes.copyOfRange(156, 172),
                committedUnixNanos = data.getLong(172),
                rootArtifactType = data.getShort(180).toInt() and 0xffff,
            )
        }
    }
}

enum class M0Schema5PointerSlot(val wireValue: Int) { A(0), B(1) }

private fun crc32c(bytes: ByteArray, zeroOffset: Int): Int {
    var crc = -1
    bytes.indices.forEach { index ->
        val value = if (index in zeroOffset until zeroOffset + 4) 0 else bytes[index].toInt() and 0xff
        crc = crc xor value
        repeat(8) {
            crc = if ((crc and 1) != 0) (crc ushr 1) xor 0x82f63b78.toInt() else crc ushr 1
        }
    }
    return crc xor -1
}

private fun ByteArray.allZero(): Boolean = all { it == 0.toByte() }

/**
 * Android/JVM reference store for the schema-5 root publication seam.
 *
 * It proves paired-root visibility and restart selection on the same bytes
 * and fault points as the Dart host reference. Directory durability is still
 * a separate device/filesystem campaign; each file is flushed and published
 * with an atomic replace where the host supports it.
 */
class M0Schema5DurableRegionCutStore(
    private val directory: File,
    initialCuts: List<M0RegionPairCut> = emptyList(),
    private val onBeforeProcessDeathCut: (() -> Unit)? = null,
    private val directorySync: M0DirectorySync = M0DirectorySync.bestEffort,
) {
    private val rootsDirectory = File(directory, "roots")
    private val roots = linkedMapOf<Long, Map<M0RegionCoordinate, M0RegionPairCut>>()
    private val predecessorRootIds = mutableMapOf<Long, Long?>()
    private var visibleRoot = 0L
    private var nextRoot = 1L
    private var activeSlot: M0Schema5PointerSlot? = null
    private var pointerGeneration = 0L

    init {
        require(directory.mkdirs() || directory.isDirectory)
        require(rootsDirectory.mkdirs() || rootsDirectory.isDirectory)
        directorySync.sync(directory)
        directorySync.sync(rootsDirectory)
        recover()
        if (roots.isEmpty()) {
            val initial = validateCuts(initialCuts, allowEmpty = true)
            roots[0L] = initial
            persistRoot(0L, initial)
            writeVisibleRoot(0L, null)
            visibleRoot = 0L
            nextRoot = 1L
        }
    }

    val visibleRootId: Long get() = visibleRoot
    val activePointerSlot: M0Schema5PointerSlot? get() = activeSlot
    val visibleCuts: Map<M0RegionCoordinate, M0RegionPairCut>
        get() = roots[visibleRoot] ?: emptyMap()
    val hasStaging: Boolean
        get() = listOf("root.staging.json", "canonical.staging.json", "coverage.staging.json")
            .any { File(directory, it).exists() }

    fun hasRoot(rootId: Long): Boolean = rootFile(rootId).isFile
    fun hasReceipt(rootId: Long): Boolean = File(directory, "receipt_$rootId.json").isFile
    fun hasActivation(rootId: Long): Boolean =
        File(directory, "active_root").takeIf { it.isFile }?.readText()?.trim() == rootId.toString()
    fun hasEviction(rootId: Long): Boolean = File(directory, "eviction_$rootId.json").isFile
    fun hasMigration(rootId: Long): Boolean = File(directory, "migration_$rootId.json").isFile
    fun hasTombstone(rootId: Long): Boolean = File(directory, "tombstone_$rootId").isFile

    fun publish(
        replacement: List<M0RegionPairCut>,
        fault: M0DurableCutFaultPoint? = null,
        operationId: String? = null,
    ): M0DurableCutCommitResult {
        val nextCuts = validateCuts(replacement, allowEmpty = false)
        val operationDigest = operationId?.let { operationDigest(it, nextCuts) }
        if (operationId != null) {
            val prior = readOperationReceipt(operationId)
            if (prior != null) {
                require(prior.digest == operationDigest) {
                    "Durable operation identity conflicts with different bytes."
                }
                require(roots.containsKey(prior.rootId) && rootFile(prior.rootId).isFile) {
                    "Durable operation receipt references a missing root."
                }
                if (visibleRoot != prior.rootId) {
                    val previousRoot = visibleRoot
                    writeVisibleRoot(prior.rootId, previousRoot)
                    visibleRoot = prior.rootId
                    clearStaging()
                    completePublication(previousRoot, prior.rootId)
                }
                return M0DurableCutCommitResult(true, null, prior.rootId, !hasStaging)
            }
        }
        val previousRoot = visibleRoot
        val rootId = nextRoot++
        clearStaging()
        try {
            inject(fault, M0DurableCutFaultPoint.beforeCanonicalWrite)
            writeStaging(rootId, nextCuts, "canonical.staging.json")
            inject(fault, M0DurableCutFaultPoint.afterCanonicalObject)
            inject(fault, M0DurableCutFaultPoint.beforeCoverageWrite)
            writeStaging(rootId, nextCuts, "coverage.staging.json")
            inject(fault, M0DurableCutFaultPoint.afterCoverageObject)
            inject(fault, M0DurableCutFaultPoint.beforeManifestSync)
            writeStaging(rootId, nextCuts, "root.staging.json")
            inject(fault, M0DurableCutFaultPoint.afterManifestSync)
            inject(fault, M0DurableCutFaultPoint.beforeRootSwitch)
            persistRoot(rootId, nextCuts)
            onBeforeProcessDeathCut?.invoke()
            inject(fault, M0DurableCutFaultPoint.afterRootPersistBeforeRootSwitch)
            inject(fault, M0DurableCutFaultPoint.processDeathBeforeRootSwitch)
            inject(fault, M0DurableCutFaultPoint.beforeReceipt)
            writeReceipt(rootId, operationId, operationDigest)
            inject(fault, M0DurableCutFaultPoint.afterReceipt)
            roots[rootId] = nextCuts
            writeVisibleRoot(rootId, previousRoot)
            visibleRoot = rootId
            clearStaging()
            inject(fault, M0DurableCutFaultPoint.afterRootSwitch)
            inject(fault, M0DurableCutFaultPoint.beforeActivation)
            writeText(File(directory, "active_root"), "$rootId\n")
            inject(fault, M0DurableCutFaultPoint.afterActivation)
            inject(fault, M0DurableCutFaultPoint.beforeEviction)
            writeText(File(directory, "eviction_$rootId.json"), "{\"evictedRootId\":$previousRoot,\"rootId\":$rootId}")
            inject(fault, M0DurableCutFaultPoint.afterEviction)
            inject(fault, M0DurableCutFaultPoint.beforeMigration)
            writeText(File(directory, "migration_$rootId.json"), "{\"schema\":5,\"rootId\":$rootId}")
            inject(fault, M0DurableCutFaultPoint.afterMigration)
            inject(fault, M0DurableCutFaultPoint.beforeTombstone)
            writeText(File(directory, "tombstone_$rootId"), "$rootId\n")
            inject(fault, M0DurableCutFaultPoint.afterTombstone)
            return M0DurableCutCommitResult(true, null, visibleRoot, !hasStaging)
        } catch (error: M0DurableCutFault) {
            val orphan = rootFile(rootId).isFile && visibleRoot == previousRoot
            if (!error.point.publishesNewRoot && !orphan) {
                rootFile(rootId).delete()
                roots.remove(rootId)
                visibleRoot = previousRoot
                recover()
            } else if (orphan) {
                visibleRoot = previousRoot
                recover()
            } else {
                clearStaging()
            }
            return M0DurableCutCommitResult(error.point.publishesNewRoot, error.point, visibleRoot, !hasStaging)
        }
    }

    fun recoverAfterProcessDeath() = recover()

    private fun inject(requested: M0DurableCutFaultPoint?, point: M0DurableCutFaultPoint) {
        if (requested == point) throw M0DurableCutFault(point)
    }

    private fun validateCuts(
        replacement: List<M0RegionPairCut>,
        allowEmpty: Boolean,
    ): Map<M0RegionCoordinate, M0RegionPairCut> {
        require(allowEmpty || replacement.isNotEmpty())
        require(replacement.map { it.region }.toSet().size == replacement.size)
        require(replacement.all { it.isCompatible })
        return replacement.sortedBy { it.region }.associateBy { it.region }
    }

    private fun writeStaging(
        rootId: Long,
        cuts: Map<M0RegionCoordinate, M0RegionPairCut>,
        name: String,
    ) = writeText(File(directory, name), rootJson(rootId, cuts))

    private fun operationReceiptFile(operationId: String): File = File(
        directory,
        "operation_${sha256(operationId.toByteArray(Charsets.UTF_8)).hex()}.json",
    )

    private fun operationDigest(
        operationId: String,
        cuts: Map<M0RegionCoordinate, M0RegionPairCut>,
    ): String {
        require(operationId.matches(Regex("[A-Za-z0-9._:/-]{1,128}"))) {
            "Durable operation identity is invalid."
        }
        return sha256(rootJson(0, cuts).toByteArray(Charsets.UTF_8)).hex()
    }

    private fun writeReceipt(rootId: Long, operationId: String?, digest: String?) {
        val receipt = buildString {
            append("{\"schema\":5,\"rootId\":").append(rootId)
            if (operationId != null) {
                append(",\"operationId\":\"").append(operationId).append('"')
                append(",\"operationDigest\":\"").append(digest).append('"')
            }
            append('}')
        }
        writeText(File(directory, "receipt_$rootId.json"), receipt)
        if (operationId != null) writeAtomic(
            operationReceiptFile(operationId),
            receipt.toByteArray(Charsets.UTF_8),
        )
    }

    private fun readOperationReceipt(operationId: String): OperationReceipt? {
        require(operationId.matches(Regex("[A-Za-z0-9._:/-]{1,128}"))) {
            "Durable operation identity is invalid."
        }
        val file = operationReceiptFile(operationId)
        if (!file.isFile) return null
        val value = Json.parseToJsonElement(file.readText()).jsonObject
        require(value.getValue("schema").jsonPrimitive.int == 5)
        require(value.getValue("operationId").jsonPrimitive.content == operationId)
        return OperationReceipt(
            value.getValue("rootId").jsonPrimitive.long,
            value.getValue("operationDigest").jsonPrimitive.content,
        )
    }

    private fun completePublication(previousRoot: Long, rootId: Long) {
        writeText(File(directory, "active_root"), "$rootId\n")
        writeText(File(directory, "eviction_$rootId.json"), "{\"evictedRootId\":$previousRoot,\"rootId\":$rootId}")
        writeText(File(directory, "migration_$rootId.json"), "{\"schema\":5,\"rootId\":$rootId}")
        writeText(File(directory, "tombstone_$rootId"), "$rootId\n")
    }

    private fun persistRoot(rootId: Long, cuts: Map<M0RegionCoordinate, M0RegionPairCut>) =
        writeAtomic(rootFile(rootId), rootJson(rootId, cuts).toByteArray(Charsets.UTF_8))

    private fun writeVisibleRoot(rootId: Long, predecessorRootId: Long?) {
        val slot = when (activeSlot) {
            M0Schema5PointerSlot.A -> M0Schema5PointerSlot.B
            M0Schema5PointerSlot.B -> M0Schema5PointerSlot.A
            null -> M0Schema5PointerSlot.A
        }
        val generation = pointerGeneration + 1
        val predecessorHash = predecessorRootId?.let { rootHash(it) } ?: ByteArray(32)
        val secondHash = predecessorRootId?.let { predecessorRootIds[it]?.let(::rootHash) } ?: ByteArray(32)
        val commitId = ByteArray(16).also {
            it[0] = 0x4d
            it[1] = 0x30
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(8, rootId + 1)
        }
        val pointer = M0Schema5RootPointerV1(
            slot = slot,
            pointerGeneration = generation,
            rootRevision = rootId + 1,
            completeRootBytes = rootFile(rootId).length(),
            currentRootSha256 = rootHash(rootId),
            durableCommitId = commitId,
            predecessorRootSha256 = predecessorHash,
            secondPredecessorRootSha256 = secondHash,
            priorPointerGeneration = generation - 1,
            scopeUuid = ByteArray(16),
            committedUnixNanos = 0,
            rootArtifactType = 3,
        )
        writeAtomic(pointerFile(slot), pointer.encode())
        activeSlot = slot
        pointerGeneration = generation
        predecessorRootIds[rootId] = predecessorRootId
    }

    private fun recover() {
        roots.clear()
        predecessorRootIds.clear()
        var highest = -1L
        rootsDirectory.listFiles()?.forEach { file ->
            val match = Regex("root_(\\d+)\\.json").matchEntire(file.name) ?: return@forEach
            val rootId = match.groupValues[1].toLong()
            runCatching { roots[rootId] = decodeRoot(file.readText()) }
                .onSuccess { if (rootId > highest) highest = rootId }
        }

        val candidates = M0Schema5PointerSlot.entries.mapNotNull { slot ->
            val file = pointerFile(slot)
            if (!file.isFile) return@mapNotNull null
            runCatching {
                val pointer = M0Schema5RootPointerV1.decode(file.readBytes())
                if (pointer.slot != slot || pointer.rootArtifactType != 3 || !pointer.scopeUuid.allZero()) return@runCatching null
                val rootId = rootIdForPointer(pointer) ?: return@runCatching null
                if (!hasCompletePredecessorChain(pointer, rootId)) return@runCatching null
                PointerCandidate(pointer, rootId)
            }.getOrNull()
        }
        val pointerExists = M0Schema5PointerSlot.entries.any { pointerFile(it).isFile }
        candidates.sortedWith(compareByDescending<PointerCandidate> { it.pointer.rootRevision }.thenByDescending { it.pointer.pointerGeneration }).let { sorted ->
            sorted.zipWithNext().forEach { (left, right) ->
                if (left.pointer.rootRevision == right.pointer.rootRevision &&
                    (!left.pointer.currentRootSha256.contentEquals(right.pointer.currentRootSha256) ||
                        !left.pointer.durableCommitId.contentEquals(right.pointer.durableCommitId))
                ) error("Schema-5 root pointer fork is invalid")
            }
            val selected = sorted.firstOrNull()
            if (selected != null) {
                visibleRoot = selected.rootId
                activeSlot = selected.pointer.slot
                pointerGeneration = selected.pointer.pointerGeneration
                predecessorRootIds[selected.rootId] = rootIdForHash(selected.pointer.predecessorRootSha256)
                selected
            } else {
                if (pointerExists) error("Schema-5 root pointer authority is unavailable")
                val legacy = File(directory, "visible_root").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                visibleRoot = when {
                    legacy != null && roots.containsKey(legacy) -> legacy
                    roots.containsKey(0L) -> 0L
                    roots.isEmpty() -> 0L
                    else -> error("Schema-5 root pointer authority is unavailable")
                }
                null
            }
        }
        nextRoot = maxOf(highest + 1, visibleRoot + 1)
        if (candidates.isNotEmpty() || roots.containsKey(0L)) clearStaging()
    }

    private fun rootIdForPointer(pointer: M0Schema5RootPointerV1): Long? {
        val rootId = pointer.rootRevision - 1
        val file = rootFile(rootId)
        if (!file.isFile || file.length() != pointer.completeRootBytes) return null
        return if (rootHash(rootId).contentEquals(pointer.currentRootSha256)) rootId else null
    }

    private fun hasCompletePredecessorChain(pointer: M0Schema5RootPointerV1, rootId: Long): Boolean {
        if (pointer.rootRevision != rootId + 1) return false
        if (pointer.rootRevision == 1L) return pointer.predecessorRootSha256.allZero() && pointer.secondPredecessorRootSha256.allZero()
        if (rootId <= 0 || !rootHash(rootId - 1).contentEquals(pointer.predecessorRootSha256)) return false
        return pointer.rootRevision == 2L || rootId > 1 && rootHash(rootId - 2).contentEquals(pointer.secondPredecessorRootSha256)
    }

    private fun rootIdForHash(hash: ByteArray): Long? = roots.keys.firstOrNull { rootHash(it).contentEquals(hash) }

    private fun rootHash(rootId: Long): ByteArray = sha256(rootFile(rootId).readBytes())

    private fun rootFile(rootId: Long) = File(rootsDirectory, "root_$rootId.json")
    private fun pointerFile(slot: M0Schema5PointerSlot) = File(directory, "root-${if (slot == M0Schema5PointerSlot.A) 'A' else 'B'}.ptr")

    private fun clearStaging() {
        var changed = false
        listOf("root.staging.json", "canonical.staging.json", "coverage.staging.json").forEach {
            changed = File(directory, it).delete() || changed
        }
        if (changed) directorySync.sync(directory)
    }

    private fun rootJson(rootId: Long, cuts: Map<M0RegionCoordinate, M0RegionPairCut>): String = buildString {
        append("{\"schema\":5,\"rootId\":").append(rootId).append(",\"cuts\":[")
        cuts.values.sortedBy { it.region }.forEachIndexed { index, cut ->
            if (index > 0) append(',')
            append("{\"region\":{\"x\":").append(cut.region.x)
                .append(",\"y\":").append(cut.region.y)
                .append(",\"z\":").append(cut.region.z)
                .append("},\"generation\":").append(cut.generation)
                .append(",\"geometryRevision\":").append(cut.geometryRevision)
                .append(",\"coverageRevision\":").append(cut.coverageRevision)
                .append(",\"captureEvaluatedThrough\":").append(cut.captureEvaluatedThrough)
                .append(",\"pendingThrough\":").append(cut.pendingThrough).append('}')
        }
        append("]}")
    }

    private fun decodeRoot(text: String): Map<M0RegionCoordinate, M0RegionPairCut> {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("schema").jsonPrimitive.int == 5)
        val cuts = root.getValue("cuts").jsonArray.map { value ->
            val objectValue = value.jsonObject
            val region = objectValue.getValue("region").jsonObject
            M0RegionPairCut(
                M0RegionCoordinate(region.getValue("x").jsonPrimitive.int, region.getValue("y").jsonPrimitive.int, region.getValue("z").jsonPrimitive.int),
                objectValue.getValue("generation").jsonPrimitive.long,
                objectValue.getValue("geometryRevision").jsonPrimitive.long,
                objectValue.getValue("coverageRevision").jsonPrimitive.long,
                objectValue.getValue("captureEvaluatedThrough").jsonPrimitive.long,
                objectValue.getValue("pendingThrough").jsonPrimitive.long,
            )
        }
        return validateCuts(cuts, allowEmpty = true)
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        file.parentFile?.let(directorySync::sync)
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File("${file.path}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        file.parentFile?.let(directorySync::sync)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private data class PointerCandidate(val pointer: M0Schema5RootPointerV1, val rootId: Long)
    private data class OperationReceipt(val rootId: Long, val digest: String)
    private class M0DurableCutFault(val point: M0DurableCutFaultPoint) : RuntimeException()
}
