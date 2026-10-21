package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Owns only the persistent JPEG display copies named by immutable V2 references. */
internal class NativeCapturePreviewOwnerV2(
    appFiles: File,
    private val deleteDurableSession: (String) -> Long = { sessionId ->
        NativeCaptureSessionDeletionV2.deleteFromAppFiles(appFiles, sessionId)
    },
    private val copyPreview: (String, String, File, () -> Boolean, Int) -> Boolean =
        NativeCapturePreviewReaderV2(File(appFiles, "capture-v2-native/store"))::materializeJpegPreview,
) {
    private val root = File(appFiles.canonicalFile, "capture-v2-previews")
    private val startup = synchronized(startups) { startups.getOrPut(root.absolutePath) { Startup() } }
    private val lock = startup.lock

    fun materialize(manifestId: String, captureId: String, cancelled: () -> Boolean = { false }, edge: Int = 512): String? = synchronized(lock) {
        require(edge == 512 || edge == 1024)
        val key = key(manifestId, captureId)
        checkRoot()
        if (cancelled()) return@synchronized null
        if (!startup.reconciled) return@synchronized null
        if (File(root, "$key.deleted").exists()) return@synchronized null
        val target = ownedFile(if (edge == 512) "$key.jpg" else "$key.detail.jpg")
        if (!copyPreview(manifestId, captureId, target, cancelled, edge)) return@synchronized null
        if (edge == 1024) trimDetailedCopies(target)
        target.absolutePath
    }

    /**
     * Deletes the durable photos of a session the app deleted: tombstone, one verified budget
     * reclaim and blob removal in the native store. Display copies follow the reference
     * deletion transaction above. Returns the bytes reclaimed by this call.
     */
    fun deleteSession(sessionId: String): Long {
        require(sessionId.isNotEmpty()) { "sessionId is required" }
        return deleteDurableSession(sessionId)
    }

    /** Resolves each interrupted deletion using successfully loaded persisted references. */
    fun reconcile(liveReferences: List<String>) = synchronized(lock) {
        startup.reconciled = false
        val liveKeys = liveReferences.map(::referenceKey).toSet()
        checkRoot()
        for (candidate in root.listFiles().orEmpty().filter { TRANSACTION.matches(it.name) }) {
            val transaction = ownedFile(candidate.name)
            check(transaction.isDirectory)
            for (pending in transaction.listFiles().orEmpty().filter { it.name.endsWith(".pending") }) {
                val key = pending.name.removeSuffix(".pending")
                check(KEY.matches(key))
                val marker = ownedFile("$key.deleted")
                if (key in liveKeys) {
                    for (name in previewNames(key)) {
                        val staged = File(transaction, name)
                        check(staged.canonicalFile == staged.absoluteFile)
                        if (staged.exists()) check(staged.renameTo(ownedFile(name)))
                    }
                    if (marker.exists()) check(marker.delete())
                } else {
                    // Retain the fence even after an interrupted rollback removed it.
                    durableWrite(marker, byteArrayOf())
                    for (name in previewNames(key)) {
                        val staged = File(transaction, name)
                        if (staged.exists()) check(staged.delete())
                        val preview = ownedFile(name)
                        if (preview.exists()) check(preview.delete())
                    }
                }
                check(pending.delete())
            }
            check(transaction.deleteRecursively())
        }
        startup.reconciled = true
    }

    /** Fences readers before staging copies. Tokens contain no caller-controlled paths. */
    fun beginDeletion(references: List<String>): String = synchronized(lock) {
        checkRoot()
        val keys = references.map(::referenceKey).distinct()
        val token = UUID.randomUUID().toString()
        val transaction = ownedFile("deleting-$token")
        check(transaction.mkdir())
        try {
            for (key in keys) {
                val marker = ownedFile("$key.deleted")
                // An already deleted reference must remain deleted after rollback.
                if (marker.exists()) continue
                durableWrite(File(transaction, "$key.pending"), byteArrayOf())
                durableWrite(marker, byteArrayOf())
                for (name in previewNames(key)) {
                    val preview = ownedFile(name)
                    if (preview.exists()) check(preview.renameTo(File(transaction, name)))
                }
            }
            token
        } catch (error: Throwable) {
            rollbackDeletion(token)
            throw error
        }
    }

    fun commitDeletion(token: String) = synchronized(lock) {
        val transaction = transaction(token)
        if (transaction.exists()) check(transaction.deleteRecursively())
    }

    fun rollbackDeletion(token: String) = synchronized(lock) {
        val transaction = transaction(token)
        for (pending in transaction.listFiles().orEmpty().filter { it.name.endsWith(".pending") }) {
            val key = pending.name.removeSuffix(".pending")
            check(KEY.matches(key))
            for (name in previewNames(key)) {
                val staged = File(transaction, name)
                if (staged.exists()) check(staged.renameTo(ownedFile(name)))
            }
            val marker = ownedFile("$key.deleted")
            if (marker.exists()) check(marker.delete())
        }
        if (transaction.exists()) check(transaction.deleteRecursively())
    }

    private fun transaction(token: String): File {
        check(UUID.fromString(token).toString() == token)
        checkRoot()
        return ownedFile("deleting-$token")
    }

    private fun checkRoot() {
        check(root.canonicalFile == root.absoluteFile) { "Preview directory must not be a symbolic link" }
        check(root.isDirectory || root.mkdirs())
    }

    private fun ownedFile(name: String): File = File(root, name).also {
        check(it.canonicalFile == it.absoluteFile) { "Preview files must not be symbolic links" }
    }

    private fun referenceKey(reference: String): String {
        val match = REFERENCE.matchEntire(reference) ?: error("Invalid immutable preview reference")
        return key(match.groupValues[1], match.groupValues[2])
    }

    private fun key(manifestId: String, captureId: String): String {
        require(HASH.matches(manifestId) && HASH.matches(captureId))
        return "$manifestId-$captureId"
    }

    private fun durableWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun previewNames(key: String) = arrayOf("$key.jpg", "$key.detail.jpg")

    /** Keep the viewed display plus at most two others; disk thumbnails remain persistent. */
    private fun trimDetailedCopies(viewed: File) {
        viewed.setLastModified(System.currentTimeMillis())
        val retained = ArrayList<File>(3)
        java.nio.file.Files.newDirectoryStream(root.toPath(), "*.detail.jpg").use { entries ->
            for (entry in entries) {
                val file = ownedFile(entry.fileName.toString())
                if (file == viewed) continue
                retained.add(file)
                if (retained.size > 2) {
                    val oldest = retained.minBy { it.lastModified() }
                    check(oldest.delete())
                    retained.remove(oldest)
                }
            }
        }
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
        val KEY = Regex("[0-9a-f]{64}-[0-9a-f]{64}")
        val REFERENCE = Regex("native-v2://([0-9a-f]{64})/([0-9a-f]{64})")
        val TRANSACTION = Regex("deleting-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val startups = mutableMapOf<String, Startup>()
    }

    private class Startup(val lock: Any = Any(), var reconciled: Boolean = false)
}
