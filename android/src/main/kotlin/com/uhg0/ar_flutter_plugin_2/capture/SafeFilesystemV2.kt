package com.uhg0.ar_flutter_plugin_2.capture

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

enum class DurableStoreFaultPointV2 {
    ACCEPTED_RECORD, COMPONENT_OPEN, PART_CREATE, PART_WRITE, PART_HASH, LENGTH_CHECK,
    PART_FILE_SYNC, ASSET_RENAME, ASSET_DIRECTORY_SYNC, ROOT_WRITE, ROOT_FILE_SYNC,
    RECEIPT_WRITE, RECEIPT_FILE_SYNC, POINTER_SLOT_REPLACE, POINTER_DIRECTORY_SYNC,
    ABANDONMENT_RECORD, ABANDONMENT_CLEANUP, TOMBSTONE_RECORD, TOMBSTONE_DIRECTORY_SYNC,
    DELETE_RECLAIM, RECOVERY,
}

fun interface DurableStoreFaultInjectorV2 { fun at(point: DurableStoreFaultPointV2) }
class PointerDirectorySyncUnknownV2(cause: Throwable) : IllegalStateException("Pointer rename is not directory-durable", cause)

interface DescriptorFileV2 : AutoCloseable {
    fun read(bytes: ByteArray, offset: Int, count: Int): Int
    fun write(bytes: ByteArray, offset: Int, count: Int)
    fun sync()
}

/** All production file content is accessed through no-follow descriptors. */
interface DescriptorFilesystemV2 {
    fun ensureDirectory(directory: File)
    fun isRegularFile(file: File): Boolean
    fun isDirectory(directory: File): Boolean
    fun size(file: File): Long
    fun openRead(file: File): DescriptorFileV2
    fun createExclusive(file: File): DescriptorFileV2
    fun atomicReplace(from: File, to: File)
    fun atomicMove(from: File, to: File)
    fun delete(file: File)
    fun list(directory: File): List<File>
    fun syncDirectory(directory: File)
}

/** Android production backend: O_NOFOLLOW/O_CLOEXEC descriptors plus fd fsync/stat. */
object AndroidDescriptorFilesystemV2 : DescriptorFilesystemV2 {
    private const val FILE_MODE = 384 // 0600
    private const val DIRECTORY_MODE = 448 // 0700

    override fun ensureDirectory(directory: File) {
        if (!directory.exists()) {
            try { Os.mkdir(directory.absolutePath, DIRECTORY_MODE) }
            catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
        }
        val descriptor = Os.open(directory.absolutePath, readFlags(), 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Durable directory is not a directory" } }
        finally { Os.close(descriptor) }
    }

    override fun isRegularFile(file: File): Boolean = try {
        val descriptor = Os.open(file.absolutePath, readFlags(), 0)
        try { OsConstants.S_ISREG(Os.fstat(descriptor).st_mode) } finally { Os.close(descriptor) }
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ELOOP) false else throw error
    }
    override fun isDirectory(directory: File): Boolean = try {
        val descriptor = Os.open(directory.absolutePath, readFlags(), 0)
        try { OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode) } finally { Os.close(descriptor) }
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ELOOP) false else throw error
    }
    override fun size(file: File): Long {
        val descriptor = Os.open(file.absolutePath, readFlags(), 0)
        try { val stat = Os.fstat(descriptor); check(OsConstants.S_ISREG(stat.st_mode)); return stat.st_size }
        finally { Os.close(descriptor) }
    }

    override fun openRead(file: File): DescriptorFileV2 = AndroidDescriptorFileV2(Os.open(file.absolutePath, readFlags(), 0))

    override fun createExclusive(file: File): DescriptorFileV2 = AndroidDescriptorFileV2(
        Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, FILE_MODE),
    )

    override fun atomicReplace(from: File, to: File) = Os.rename(from.absolutePath, to.absolutePath)
    override fun atomicMove(from: File, to: File) = Os.rename(from.absolutePath, to.absolutePath)
    override fun delete(file: File) {
        try { Os.remove(file.absolutePath) }
        catch (error: ErrnoException) { if (error.errno != OsConstants.ENOENT) throw error }
    }
    override fun list(directory: File): List<File> = directory.list()?.sorted()?.map { File(directory, it) } ?: emptyList()
    override fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, readFlags(), 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) }
        finally { Os.close(descriptor) }
    }
    private fun readFlags() = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
}

private class AndroidDescriptorFileV2(private val descriptor: FileDescriptor) : DescriptorFileV2 {
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int = Os.read(descriptor, bytes, offset, count)
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        var written = 0
        while (written < count) written += Os.write(descriptor, bytes, offset + written, count - written)
    }
    override fun sync() = Os.fsync(descriptor)
    override fun close() = Os.close(descriptor)
}

/** Deterministic JVM fake with the same exclusive/no-follow descriptor contract. */
class JvmDescriptorFilesystemV2(
    private val onDirectorySync: (File) -> Unit = { },
    private val beforeDescriptorOpen: (File) -> Unit = { },
) : DescriptorFilesystemV2 {
    override fun ensureDirectory(directory: File) {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory.toPath())
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Durable directory is not a directory" }
    }
    override fun isRegularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    override fun isDirectory(directory: File): Boolean = Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    override fun size(file: File): Long = FileChannel.open(
        file.also(beforeDescriptorOpen).toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
    ).use(FileChannel::size)
    override fun openRead(file: File): DescriptorFileV2 = JvmDescriptorFileV2(
        FileChannel.open(file.also(beforeDescriptorOpen).toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    )
    override fun createExclusive(file: File): DescriptorFileV2 = JvmDescriptorFileV2(
        FileChannel.open(file.also(beforeDescriptorOpen).toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
    )
    override fun atomicReplace(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic replacement unavailable") }
    }
    override fun atomicMove(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic move unavailable") }
    }
    override fun delete(file: File) { Files.deleteIfExists(file.toPath()) }
    override fun list(directory: File): List<File> = Files.newDirectoryStream(directory.toPath()).use { stream ->
        stream.map { it.toFile() }.sortedBy(File::getName).toList()
    }
    override fun syncDirectory(directory: File) { onDirectorySync(directory.canonicalFile) }
}

private class JvmDescriptorFileV2(private val channel: FileChannel) : DescriptorFileV2 {
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int = channel.read(ByteBuffer.wrap(bytes, offset, count))
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        val buffer = ByteBuffer.wrap(bytes, offset, count)
        while (buffer.hasRemaining()) channel.write(buffer)
    }
    override fun sync() = channel.force(true)
    override fun close() = channel.close()
}

/** Canonical contained path construction plus descriptor-only durable operations. */
class SafeFilesystemV2(
    root: File,
    private val fault: DurableStoreFaultInjectorV2,
    private val backend: DescriptorFilesystemV2 = AndroidDescriptorFilesystemV2,
) {
    private val rootPath = root.absoluteFile.toPath().normalize()

    init {
        backend.ensureDirectory(rootPath.toFile())
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
        val missing = generateSequence(directory) { it.parentFile }
            .takeWhile { it.absoluteFile.toPath().normalize().startsWith(rootPath) }
            .toList().asReversed()
        missing.forEach(backend::ensureDirectory)
    }

    fun isFile(file: File): Boolean { requireContained(file); return backend.isRegularFile(file) }
    fun length(file: File): Long { requireContained(file); return backend.size(file) }
    fun readBytes(file: File): ByteArray {
        requireContained(file)
        val output = ByteArrayOutputStream()
        backend.openRead(file).use { descriptor ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = descriptor.read(buffer, 0, buffer.size); if (read < 0) break; if (read == 0) continue; output.write(buffer, 0, read) }
        }
        return output.toByteArray()
    }
    fun readLines(file: File): List<String> = readBytes(file).toString(Charsets.UTF_8).lines().dropLastWhile(String::isEmpty)
    fun list(directory: File): List<File> {
        requireContained(directory)
        check(backend.isDirectory(directory)) { "Durable listing target is not a no-follow directory" }
        return backend.list(directory).onEach(::requireContained)
    }
    fun walk(directory: File): Sequence<File> = sequence {
        requireContained(directory); yield(directory)
        if (backend.isDirectory(directory)) for (child in list(directory)) {
            yield(child); if (backend.isDirectory(child)) yieldAll(walk(child).drop(1))
        }
    }

    fun writeExclusive(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        prepareParent(file); fault.at(point)
        backend.createExclusive(file).use { descriptor -> descriptor.write(bytes, 0, bytes.size); descriptor.sync() }
        backend.syncDirectory(parent(file))
    }

    fun writeImmutable(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        if (isFile(file)) { check(readBytes(file).contentEquals(bytes)) { "Immutable durable object conflict" }; return }
        writeExclusive(file, bytes, point)
    }

    fun atomicReplace(file: File, bytes: ByteArray, replacePoint: DurableStoreFaultPointV2) {
        prepareParent(file)
        val temporary = childRelative(parent(file), ".${file.name}.part")
        backend.delete(temporary)
        backend.createExclusive(temporary).use { descriptor -> descriptor.write(bytes, 0, bytes.size); descriptor.sync() }
        fault.at(replacePoint)
        backend.atomicReplace(temporary, file)
        // A crash/fault here is UNKNOWN: rename visibility is not durability.
        try {
            fault.at(DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC)
            backend.syncDirectory(parent(file))
        } catch (error: Throwable) {
            throw PointerDirectorySyncUnknownV2(error)
        }
    }

    fun moveAtomic(from: File, to: File) {
        requireContained(from); prepareParent(to)
        fault.at(DurableStoreFaultPointV2.ASSET_RENAME)
        backend.atomicMove(from, to)
        fault.at(DurableStoreFaultPointV2.ASSET_DIRECTORY_SYNC)
        backend.syncDirectory(parent(to))
    }

    fun streamExclusive(file: File, input: InputStream): Pair<Long, ByteArray> {
        prepareParent(file); fault.at(DurableStoreFaultPointV2.PART_CREATE)
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        input.use { source -> backend.createExclusive(file).use { descriptor ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer); if (read < 0) break
                fault.at(DurableStoreFaultPointV2.PART_WRITE)
                descriptor.write(buffer, 0, read); digest.update(buffer, 0, read); length = Math.addExact(length, read.toLong())
            }
            fault.at(DurableStoreFaultPointV2.PART_FILE_SYNC); descriptor.sync()
        } }
        fault.at(DurableStoreFaultPointV2.PART_HASH)
        backend.syncDirectory(parent(file))
        return length to digest.digest()
    }

    fun digestAndLength(file: File): Pair<Long, ByteArray> {
        requireContained(file); val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
        backend.openRead(file).use { descriptor ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = descriptor.read(buffer, 0, buffer.size); if (read < 0) break; if (read == 0) continue; digest.update(buffer, 0, read); length = Math.addExact(length, read.toLong()) }
        }
        return length to digest.digest()
    }

    fun allocateExclusive(file: File, bytes: Long) {
        require(bytes > 0); prepareParent(file); val buffer = ByteArray(64 * 1024)
        backend.createExclusive(file).use { descriptor ->
            var remaining = bytes
            while (remaining > 0) { val count = minOf(buffer.size.toLong(), remaining).toInt(); descriptor.write(buffer, 0, count); remaining -= count }
            descriptor.sync()
        }
        check(length(file) == bytes) { "Physical reservation allocation incomplete" }
        backend.syncDirectory(parent(file))
    }

    fun delete(file: File, point: DurableStoreFaultPointV2) {
        requireContained(file); fault.at(point); backend.delete(file); backend.syncDirectory(parent(file))
    }
    fun deleteTree(directory: File, point: DurableStoreFaultPointV2) {
        requireContained(directory); fault.at(point)
        walk(directory).toList().asReversed().forEach(backend::delete)
        directory.parentFile?.let(backend::syncDirectory)
    }

    private fun prepareParent(file: File) { requireContained(file); ensureDirectory(parent(file)) }
    private fun parent(file: File) = requireNotNull(file.parentFile) { "Durable root has no parent" }
    private fun childRelative(parent: File, name: String): File = File(parent, name).also(::requireContained)
    private fun requireContained(file: File) {
        require(file.absoluteFile.toPath().normalize().startsWith(rootPath)) { "Durable file escapes root" }
    }
    companion object { private val SEGMENT = Regex("[A-Za-z0-9._-]{1,160}") }
}
