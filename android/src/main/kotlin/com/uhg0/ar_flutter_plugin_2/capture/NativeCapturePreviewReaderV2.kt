package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
            val descriptor = jpegDescriptor(File(session, "objects/$manifestId.root"))
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
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                FileInputStream(source).use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                        }
                        output.fd.sync()
                    }
                }
                check(copied == descriptor.length)
                check(digest.digest().hex() == descriptor.hash)
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
        val candidates = listOf("root-A.ptr", "root-B.ptr")
            .mapNotNull { slot ->
                val pointer = File(session, slot)
                val lines = pointer.takeIf(File::isFile)?.readLines()
                    ?: return@mapNotNull null
                if (lines.size != 3) return@mapNotNull null
                val revision = lines[0].toLongOrNull() ?: return@mapNotNull null
                val hash = lines[1]
                if (!HASH.matches(hash) || !validRoot(session, hash, revision, 0)) {
                    return@mapNotNull null
                }
                RootPointer(revision, hash)
            }
        if (candidates.groupBy { it.revision }.values.any { roots ->
                roots.map { it.hash }.toSet().size > 1
            }) {
            return null
        }
        return candidates.maxByOrNull { it.revision }
    }

    private fun validRoot(session: File, hash: String, revision: Long, depth: Int): Boolean {
        if (depth > MAX_ROOT_DEPTH || !HASH.matches(hash)) return false
        val root = File(session, "objects/$hash.root")
        if (!root.isFile || sha256(root).hex() != hash) return false
        val values = root.readLines().associate { line ->
            line.substringBefore('=') to line.substringAfter('=', "")
        }
        if (values["schema"] != "5" ||
            values["revision"]?.toLongOrNull() != revision ||
            !HASH.matches(values["request"].orEmpty())
        ) return false
        if (depth == MAX_ROOT_DEPTH) return true
        val previous = values["previous"]
        val secondPrevious = values["secondPrevious"]
        if (revision == 1L) return previous == "-" && secondPrevious == "-"
        if (previous.isNullOrEmpty() || previous == "-") return false
        if (!validRoot(session, previous, revision - 1L, depth + 1)) return false
        return depth != 0 || revision < 3L || secondPrevious == rootPrevious(session, previous)
    }

    private fun rootPrevious(session: File, hash: String): String? =
        File(session, "objects/$hash.root")
            .takeIf(File::isFile)
            ?.readLines()
            ?.firstOrNull { it.startsWith("previous=") }
            ?.removePrefix("previous=")
            ?.takeUnless { it == "-" }

    private fun jpegDescriptor(root: File): Descriptor? {
        if (!root.isFile) return null
        val fields = root.readLines()
            .firstOrNull { it.startsWith("component=JPEG:") }
            ?.removePrefix("component=JPEG:")
            ?.split(':')
            ?: return null
        if (fields.size != 2) return null
        val length = fields[0].toLongOrNull() ?: return null
        val hash = fields[1]
        return Descriptor(length, hash).takeIf { length >= 0L && HASH.matches(hash) }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private data class Descriptor(val length: Long, val hash: String)
    private data class RootPointer(val revision: Long, val hash: String)

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_ROOT_DEPTH = 2
        const val MAX_RETAINED_ROOTS = 3
        val HASH = Regex("[0-9a-f]{64}")
    }
}
