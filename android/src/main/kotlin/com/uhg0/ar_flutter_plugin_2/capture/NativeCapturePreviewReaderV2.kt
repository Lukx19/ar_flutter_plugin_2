package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
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
    private val writePreview: (File, File, () -> Boolean, Int) -> Boolean = CaptureThumbnail::write,
    private val validPreview: (File, Int) -> Boolean = CaptureThumbnail::valid,
) {
    private val copyWorkspace = CaptureCopyWorkspace()
    private val rootMetadata = CaptureRootMetadataReaderV2()

    @Synchronized
    fun materializeJpegPreview(
        manifestId: String,
        captureId: String,
        target: File,
        cancelled: () -> Boolean = { false },
        edge: Int = 512,
    ): Boolean {
        require(edge == 512 || edge == 1024)
        if (!HASH.matches(manifestId) || !HASH.matches(captureId)) return false
        val sessions = File(storeRoot.canonicalFile, "sessions")
        if (!sessions.isDirectory) return false
        Files.newDirectoryStream(sessions.toPath()).use { entries ->
        for (entry in entries) {
            if (cancelled()) return false
            val session = entry.toFile()
            if (!session.isDirectory || session.canonicalFile != session.absoluteFile || File(session, "tombstone").exists()) continue
            val descriptor = committedRoot(session, manifestId, captureId, cancelled)?.components
                ?.firstOrNull { it.kind == "JPEG" }?.let { Descriptor(it.length, it.hash) }
                ?: continue
            val source = File(
                session,
                "assets/$captureId/jpeg.${descriptor.hash}.blob",
            )
            if (!source.isFile || source.canonicalFile != source.absoluteFile || source.length() != descriptor.length) continue
            val parent = target.parentFile ?: continue
            parent.mkdirs()
            if (validPreview(target, edge)) return true
            val partial = File(parent, "${target.name}.part")
            try {
                check(target.canonicalFile == target.absoluteFile && partial.canonicalFile == partial.absoluteFile)
                if (cancelled() || sha256(source, cancelled).hex() != descriptor.hash) return false
                if (!writePreview(source, partial, cancelled, edge) || cancelled()) return false
                Files.move(
                    partial.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return true
            } catch (_: Throwable) { return false }
            finally { partial.delete() }
        }
        }
        return false
    }

    /** Walk immutable ancestors with one open descriptor and scalar state, not a root-sized set. */
    private fun committedRoot(session: File, wanted: String, captureId: String, cancelled: () -> Boolean): CaptureRootMetadataV2? {
        val selected = selectedRoot(session) ?: return null
        var hash = selected.hash
        var revision = selected.revision
        while (revision > 0 && !cancelled()) {
            val root = readRootMetadata(session, hash) ?: return null
            if (!knownQualitySchema(root) || root.revision != revision || !CaptureRootMetadataReaderV2.isHash(root.request)) return null
            if (revision == 1L && (root.previous != "-" || root.secondPrevious != "-")) return null
            if (hash == wanted) return root.takeIf { boundReceipt(session, hash, captureId, it, cancelled) }
            hash = root.previous?.takeIf(HASH::matches) ?: return null
            revision--
        }
        return null
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
        if (!knownQualitySchema(values) ||
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

    private fun knownQualitySchema(root: CaptureRootMetadataV2): Boolean = when (root.schema) {
        "5" -> root.qualityAssessment == null && root.qualityAssessmentHash == null && root.exposureTimestampNanoseconds == null
        "6" -> root.qualityAssessment != null && root.request != null && root.exposureTimestampNanoseconds != null &&
            root.qualityAssessmentHash == CaptureQualityAssessmentV1.boundHash(root.request, root.qualityAssessment)
        else -> false
    }

    /** Root descriptors alone cannot authorize a different capture with coincidentally equal JPEG bytes. */
    private fun boundReceipt(session: File, manifestId: String, captureId: String,
        root: CaptureRootMetadataV2, cancelled: () -> Boolean): Boolean {
        val attempts = File(session, "attempts")
        if (!attempts.isDirectory || attempts.canonicalFile != attempts.absoluteFile) return false
        Files.newDirectoryStream(attempts.toPath()).use { entries ->
            for (entry in entries) {
                if (cancelled()) return false
                val directory = entry.toFile()
                if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) continue
                val file = File(directory, "receipt.properties")
                if (!file.isFile || file.canonicalFile != file.absoluteFile || file.length() > MAX_RECEIPT_BYTES) continue
                val values = runCatching { FileInputStream(file).use { Properties().apply { load(it) } } }.getOrNull() ?: continue
                if (values.getProperty("kind") != CaptureTerminalKind.COMMITTED_PICTURE.name ||
                    values.getProperty("rootHash") != manifestId || values.getProperty("captureId") != captureId) continue
                if (values.getProperty("requestHash") != root.request || values.getProperty("revision")?.toLongOrNull() != root.revision ||
                    values.getProperty("receiptHash") != MessageDigest.getInstance("SHA-256")
                        .digest("${root.request}|$manifestId".toByteArray()).toCaptureHashHex()) return false
                val jpeg = root.components.singleOrNull { it.kind == "JPEG" } ?: return false
                val dng = root.components.singleOrNull { it.kind == "DNG" }
                when (root.schema) {
                    "5" -> {
                        if (values.stringPropertyNames().any { it.startsWith("quality") }) return false
                        if (values.getProperty("jpegSizeBytes")?.let { it.toLongOrNull() != jpeg.length } == true ||
                            values.getProperty("dngSizeBytes")?.let { it.toLongOrNull() != dng?.length } == true) return false
                    }
                    "6" -> {
                        val fields = values.stringPropertyNames().associateWith { values.getProperty(it) }
                        if (CaptureQualityAssessmentV1.fromRootFields(fields) != root.qualityAssessment ||
                            values.getProperty("qualityAssessmentHash") != root.qualityAssessmentHash ||
                            values.getProperty("jpegSizeBytes")?.toLongOrNull() != jpeg.length ||
                            values.getProperty("dngSizeBytes")?.toLongOrNull() != dng?.length ||
                            (dng == null && values.getProperty("dngSizeBytes") != null)) return false
                    }
                    else -> return false
                }
                return true
            }
        }
        return false
    }

    private fun readRootMetadata(session: File, hash: String): CaptureRootMetadataV2? {
        if (!CaptureRootMetadataReaderV2.isHash(hash)) return null
        val root = File(session, "objects/$hash.root")
        if (!root.isFile) return null
        return FileInputStream(root).use { rootMetadata.root(hash, it::read) }
    }

    private fun rootPrevious(session: File, hash: String): String? =
        readRootMetadata(session, hash)?.previous?.takeUnless { it == "-" }

    private fun sha256(file: File, cancelled: () -> Boolean): ByteArray {
        return FileInputStream(file).use { input ->
            copyWorkspace.digest { buffer, offset, count ->
                check(!cancelled()) { "Obsolete preview load" }
                input.read(buffer, offset, count)
            }.digest
        }
    }

    private fun ByteArray.hex() = toCaptureHashHex()

    private data class Descriptor(val length: Long, val hash: String)
    private data class RootPointer(val revision: Long, val hash: String)

    private companion object {
        const val MAX_ROOT_DEPTH = 2
        const val MAX_RECEIPT_BYTES = 64L * 1024L
        val HASH = Regex("[0-9a-f]{64}")
    }
}
