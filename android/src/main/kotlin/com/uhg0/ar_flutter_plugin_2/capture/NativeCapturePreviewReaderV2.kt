package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Read-only access to committed V2 JPEGs for History/detail screens.
 *
 * This reader never acquires the per-view durable-root lease and never opens a
 * budget coordinator. It validates the selected-root chain and the JPEG
 * descriptor before creating the persistent app-private display copy.
 */
internal class NativeCapturePreviewReaderV2(
    private val storeRoot: File,
) {
    private val copyWorkspace = CaptureCopyWorkspace()
    private val rootMetadata = CaptureRootMetadataReaderV2()

    @Synchronized
    fun materializeJpegPreview(
        manifestId: String,
        captureId: String,
        target: File,
    ): Boolean {
        if (!HASH.matches(manifestId) || !HASH.matches(captureId)) return false
        val sessions = File(storeRoot, "sessions")
            .listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()
        for (session in sessions) {
            if (manifestId !in retainedRootHashes(session)) continue
            val descriptor = readRootMetadata(session, manifestId)?.components
                ?.firstOrNull { it.kind == "JPEG" }?.let { Descriptor(it.length, it.hash) }
                ?: continue
            val source = File(
                session,
                "assets/$captureId/jpeg.${descriptor.hash}.blob",
            )
            if (!source.isFile || source.length() != descriptor.length) continue
            val parent = target.parentFile ?: continue
            parent.mkdirs()
            if (isValidPreview(target, descriptor)) return true
            val partial = File(parent, "${target.name}.part")
            try {
                val receipt = FileInputStream(source).use { input ->
                    FileOutputStream(partial).use { output ->
                        val copied = copyWorkspace.copy(
                            read = { buffer, offset, count -> input.read(buffer, offset, count) },
                            write = { buffer, offset, count -> output.write(buffer, offset, count) },
                        )
                        output.fd.sync()
                        copied
                    }
                }
                check(receipt.length == descriptor.length)
                check(receipt.digest.hex() == descriptor.hash)
                Files.move(
                    partial.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return true
            } catch (_: Throwable) {
                partial.delete()
                return false
            }
        }
        return false
    }

    private fun isValidPreview(target: File, descriptor: Descriptor): Boolean =
        target.isFile && target.length() == descriptor.length &&
            runCatching { sha256(target).hex() == descriptor.hash }.getOrDefault(false)

    private fun retainedRootHashes(session: File): Set<String> {
        val selected = selectedRoot(session) ?: return emptySet()
        return buildSet {
            var hash: String? = selected.hash
            repeat(MAX_RETAINED_ROOTS) {
                hash?.let(::add)
                hash = hash?.let { rootPrevious(session, it) }
            }
        }
    }

    private fun selectedRoot(session: File): RootPointer? {
        var selected: RootPointer? = null
        for (slot in 0..1) {
            val file = File(session, if (slot == 0) "root-A.ptr" else "root-B.ptr")
            if (!file.isFile) continue
            val pointer = FileInputStream(file).use { rootMetadata.pointer(it::read) } ?: continue
            if (!validRoot(session, pointer.hash, pointer.revision, 0)) continue
            val prior = selected
            if (prior != null && prior.revision == pointer.revision && prior.hash != pointer.hash) return null
            if (prior == null || pointer.revision > prior.revision) selected = RootPointer(pointer.revision, pointer.hash)
        }
        return selected
    }

    private fun validRoot(session: File, hash: String, revision: Long, depth: Int): Boolean {
        if (depth > MAX_ROOT_DEPTH || !HASH.matches(hash)) return false
        val values = readRootMetadata(session, hash) ?: return false
        if (values.schema != "5" ||
            values.revision != revision ||
            !CaptureRootMetadataReaderV2.isHash(values.request)
        ) return false
        if (depth == MAX_ROOT_DEPTH) return true
        val previous = values.previous
        val secondPrevious = values.secondPrevious
        if (revision == 1L) return previous == "-" && secondPrevious == "-"
        if (previous.isNullOrEmpty() || previous == "-") return false
        if (!validRoot(session, previous, revision - 1L, depth + 1)) return false
        return depth != 0 || revision < 3L || secondPrevious == rootPrevious(session, previous)
    }

    private fun readRootMetadata(session: File, hash: String): CaptureRootMetadataV2? {
        if (!CaptureRootMetadataReaderV2.isHash(hash)) return null
        val root = File(session, "objects/$hash.root")
        if (!root.isFile) return null
        return FileInputStream(root).use { rootMetadata.root(hash, it::read) }
    }

    private fun rootPrevious(session: File, hash: String): String? =
        readRootMetadata(session, hash)?.previous?.takeUnless { it == "-" }

    private fun sha256(file: File): ByteArray {
        return FileInputStream(file).use { input ->
            copyWorkspace.digest { buffer, offset, count -> input.read(buffer, offset, count) }.digest
        }
    }

    private fun ByteArray.hex() = toCaptureHashHex()

    private data class Descriptor(val length: Long, val hash: String)
    private data class RootPointer(val revision: Long, val hash: String)

    private companion object {
        const val MAX_ROOT_DEPTH = 2
        const val MAX_RETAINED_ROOTS = 3
        val HASH = Regex("[0-9a-f]{64}")
    }
}
