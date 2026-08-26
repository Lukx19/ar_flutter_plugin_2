package com.uhg0.ar_flutter_plugin_2.capture

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DurableStoreFaultPointV2 {
    ACCEPTED_RECORD, COMPONENT_OPEN, PART_CREATE, PART_WRITE, PART_HASH, LENGTH_CHECK,
    PART_FILE_SYNC, ASSET_RENAME, ASSET_DIRECTORY_SYNC, ROOT_WRITE, ROOT_FILE_SYNC,
    RECEIPT_WRITE, RECEIPT_FILE_SYNC, POINTER_SLOT_REPLACE, POINTER_DIRECTORY_SYNC,
    ABANDONMENT_RECORD, ABANDONMENT_CLEANUP, TOMBSTONE_RECORD, TOMBSTONE_DIRECTORY_SYNC,
    DELETE_RECLAIM, RECOVERY,
}

fun interface DurableStoreFaultInjectorV2 { fun at(point: DurableStoreFaultPointV2) }
fun interface DirectorySyncV2 { fun sync(directory: File) }

/** A successful production return means the directory itself was fsynced. */
object AndroidDirectorySyncV2 : DirectorySyncV2 {
    override fun sync(directory: File) {
        require(directory.isDirectory) { "Cannot fsync a missing directory: ${directory.path}" }
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}

/** Contained, no-follow filesystem authority for every schema-5 mutation. */
class SafeFilesystemV2(
    root: File,
    private val fault: DurableStoreFaultInjectorV2,
    private val directorySync: DirectorySyncV2 = AndroidDirectorySyncV2,
) {
    private val rootPath: java.nio.file.Path = root.absoluteFile.toPath().normalize()

    init {
        require(rootPath.toFile().exists() || rootPath.toFile().mkdirs())
        verifyPath(rootPath.toFile())
    }

    fun child(vararg names: String): File {
        var result = rootPath.toFile()
        names.forEach { name ->
            require(name.matches(SEGMENT) && name != "." && name != "..") { "Unsafe durable path segment" }
            result = File(result, name)
        }
        requireContained(result)
        return result
    }

    fun ensureDirectory(directory: File) {
        requireContained(directory)
        if (!directory.exists()) require(directory.mkdirs()) { "Cannot create durable directory" }
        verifyPath(directory)
        require(directory.isDirectory)
    }

    fun isFile(file: File): Boolean { requireContained(file); return file.isFile }
    fun readBytes(file: File): ByteArray { requireContained(file); verifyPath(file); return file.readBytes() }
    fun readLines(file: File): List<String> = readBytes(file).toString(Charsets.UTF_8).lines().dropLastWhile(String::isEmpty)
    fun list(directory: File): List<File> { requireContained(directory); verifyPath(directory); return directory.listFiles()?.onEach(::requireContained)?.toList() ?: emptyList() }
    fun walk(directory: File): Sequence<File> = sequence {
        requireContained(directory); verifyPath(directory)
        yield(directory)
        if (directory.isDirectory) for (child in list(directory)) yieldAll(walk(child))
    }

    fun writeExclusive(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        prepareParent(file)
        check(!file.exists()) { "Immutable durable record already exists" }
        fault.at(point)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        directorySync.sync(parent(file))
    }

    fun writeImmutable(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        if (isFile(file)) {
            check(readBytes(file).contentEquals(bytes)) { "Immutable durable object conflict" }
            return
        }
        writeExclusive(file, bytes, point)
    }

    fun atomicReplace(file: File, bytes: ByteArray, replacePoint: DurableStoreFaultPointV2) {
        prepareParent(file)
        val temporary = childRelative(parent(file), ".${file.name}.part")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        fault.at(replacePoint)
        atomicMove(temporary, file, replace = true)
        syncDirectory(parent(file), DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC)
    }

    fun moveSameDirectory(from: File, to: File) {
        requireContained(from); prepareParent(to); verifyPath(from)
        fault.at(DurableStoreFaultPointV2.ASSET_RENAME)
        atomicMove(from, to, replace = false)
        syncDirectory(parent(to), DurableStoreFaultPointV2.ASSET_DIRECTORY_SYNC)
    }

    /** Writes every byte so this is physical allocation, not a sparse length claim. */
    fun allocateExclusive(file: File, bytes: Long) {
        require(bytes > 0)
        prepareParent(file)
        check(!file.exists()) { "Physical reservation already exists" }
        val buffer = ByteArray(64 * 1024)
        RandomAccessFile(file, "rw").use { output ->
            var remaining = bytes
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        check(file.length() == bytes) { "Physical reservation allocation incomplete" }
        directorySync.sync(parent(file))
    }

    fun delete(file: File, point: DurableStoreFaultPointV2) {
        requireContained(file); fault.at(point)
        if (file.exists() && !file.delete()) error("Cannot delete durable file")
        directorySync.sync(parent(file))
    }

    fun deleteTree(directory: File, point: DurableStoreFaultPointV2) {
        requireContained(directory); fault.at(point)
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { requireContained(it); verifyPath(it); if (!it.delete()) error("Cannot delete durable tree") }
        directory.parentFile?.let(directorySync::sync)
    }

    fun syncDirectory(directory: File, point: DurableStoreFaultPointV2) {
        requireContained(directory); verifyPath(directory); fault.at(point); directorySync.sync(directory)
    }

    private fun prepareParent(file: File) { requireContained(file); ensureDirectory(parent(file)) }
    private fun parent(file: File): File = requireNotNull(file.parentFile) { "Durable root has no parent" }
    private fun childRelative(parent: File, name: String): File {
        require(name.matches(SEGMENT)); return File(parent, name).also(::requireContained)
    }
    private fun atomicMove(from: File, to: File, replace: Boolean) {
        val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) else arrayOf(StandardCopyOption.ATOMIC_MOVE)
        try { Files.move(from.toPath(), to.toPath(), *options) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic same-directory replace unavailable") }
    }
    private fun requireContained(file: File) {
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(rootPath)) { "Durable file escapes root" }
        verifyPath(file)
    }
    private fun verifyPath(file: File) {
        var current: File? = file.absoluteFile
        while (current != null && current.toPath().normalize().startsWith(rootPath)) {
            check(!Files.isSymbolicLink(current.toPath())) { "Symlink durable path is refused" }
            current = current.parentFile
        }
    }

    companion object { private val SEGMENT = Regex("[A-Za-z0-9._-]{1,160}") }
}
