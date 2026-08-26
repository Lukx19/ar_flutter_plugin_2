package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
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

/** A backend is rebound once to a trusted root; all later operations are root-relative. */
interface DescriptorFilesystemV2 {
    fun bind(root: File): DescriptorFilesystemV2
    fun ensureDirectory(segments: List<String>)
    fun isRegularFile(segments: List<String>): Boolean
    fun isDirectory(segments: List<String>): Boolean
    fun size(segments: List<String>): Long
    fun openRead(segments: List<String>): DescriptorFileV2
    fun createExclusive(segments: List<String>): DescriptorFileV2
    fun atomicReplace(parent: List<String>, from: String, to: String)
    fun atomicMove(fromParent: List<String>, from: String, toParent: List<String>, to: String)
    fun delete(segments: List<String>)
    fun list(segments: List<String>): List<String>
    fun syncDirectory(segments: List<String>)
}

/** JNI bridge to openat/mkdirat/renameat/unlinkat rooted at one O_DIRECTORY fd. */
object AndroidDescriptorNativeV2 {
    init { System.loadLibrary("capture3d_safe_fs_v2") }
    external fun nativeOpenRoot(path: String): Long
    external fun nativeClose(descriptor: Long)
    external fun nativeEnsureDirectory(root: Long, segments: Array<String>)
    external fun nativeIsRegular(root: Long, segments: Array<String>): Boolean
    external fun nativeIsDirectory(root: Long, segments: Array<String>): Boolean
    external fun nativeSize(root: Long, segments: Array<String>): Long
    external fun nativeOpenRead(root: Long, segments: Array<String>): Long
    external fun nativeCreateExclusive(root: Long, segments: Array<String>): Long
    external fun nativeRead(descriptor: Long, bytes: ByteArray, offset: Int, count: Int): Int
    external fun nativeWrite(descriptor: Long, bytes: ByteArray, offset: Int, count: Int)
    external fun nativeSync(descriptor: Long)
    external fun nativeAtomicReplace(root: Long, parent: Array<String>, from: String, to: String)
    external fun nativeAtomicMove(root: Long, fromParent: Array<String>, from: String, toParent: Array<String>, to: String)
    external fun nativeDelete(root: Long, segments: Array<String>)
    external fun nativeList(root: Long, segments: Array<String>): Array<String>
    external fun nativeSyncDirectory(root: Long, segments: Array<String>)
}

/** Android production backend; no operation re-enters absolute pathname traversal. */
class AndroidDescriptorFilesystemV2 private constructor(private val rootDescriptor: Long?) : DescriptorFilesystemV2 {
    constructor() : this(null)
    override fun bind(root: File) = AndroidDescriptorFilesystemV2(AndroidDescriptorNativeV2.nativeOpenRoot(root.absolutePath))
    private fun root() = requireNotNull(rootDescriptor) { "Descriptor filesystem is not root-bound" }
    override fun ensureDirectory(segments: List<String>) = AndroidDescriptorNativeV2.nativeEnsureDirectory(root(), segments.toTypedArray())
    override fun isRegularFile(segments: List<String>) = AndroidDescriptorNativeV2.nativeIsRegular(root(), segments.toTypedArray())
    override fun isDirectory(segments: List<String>) = AndroidDescriptorNativeV2.nativeIsDirectory(root(), segments.toTypedArray())
    override fun size(segments: List<String>) = AndroidDescriptorNativeV2.nativeSize(root(), segments.toTypedArray())
    override fun openRead(segments: List<String>): DescriptorFileV2 =
        AndroidNativeFileV2(AndroidDescriptorNativeV2.nativeOpenRead(root(), segments.toTypedArray()))
    override fun createExclusive(segments: List<String>): DescriptorFileV2 =
        AndroidNativeFileV2(AndroidDescriptorNativeV2.nativeCreateExclusive(root(), segments.toTypedArray()))
    override fun atomicReplace(parent: List<String>, from: String, to: String) = AndroidDescriptorNativeV2.nativeAtomicReplace(root(), parent.toTypedArray(), from, to)
    override fun atomicMove(fromParent: List<String>, from: String, toParent: List<String>, to: String) = AndroidDescriptorNativeV2.nativeAtomicMove(root(), fromParent.toTypedArray(), from, toParent.toTypedArray(), to)
    override fun delete(segments: List<String>) = AndroidDescriptorNativeV2.nativeDelete(root(), segments.toTypedArray())
    override fun list(segments: List<String>) = AndroidDescriptorNativeV2.nativeList(root(), segments.toTypedArray()).toList()
    override fun syncDirectory(segments: List<String>) = AndroidDescriptorNativeV2.nativeSyncDirectory(root(), segments.toTypedArray())
}

private class AndroidNativeFileV2(private val descriptor: Long) : DescriptorFileV2 {
    override fun read(bytes: ByteArray, offset: Int, count: Int) = AndroidDescriptorNativeV2.nativeRead(descriptor, bytes, offset, count)
    override fun write(bytes: ByteArray, offset: Int, count: Int) = AndroidDescriptorNativeV2.nativeWrite(descriptor, bytes, offset, count)
    override fun sync() = AndroidDescriptorNativeV2.nativeSync(descriptor)
    override fun close() = AndroidDescriptorNativeV2.nativeClose(descriptor)
}

/** JVM model: serialized component-by-component NOFOLLOW checks and exclusive final opens. */
class JvmDescriptorFilesystemV2(
    private val onDirectorySync: (File) -> Unit = { },
    private val beforeComponentOpen: (File) -> Unit = { },
    private val boundRoot: File? = null,
) : DescriptorFilesystemV2 {
    private val lock = Any()
    override fun bind(root: File): DescriptorFilesystemV2 {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root.toPath())
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        return JvmDescriptorFilesystemV2(onDirectorySync, beforeComponentOpen, root.canonicalFile)
    }
    override fun ensureDirectory(segments: List<String>) = synchronized(lock) {
        var current = root()
        segments.forEach { segment ->
            current = File(current, segment); beforeComponentOpen(current)
            if (!Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current.toPath())
            check(Files.isDirectory(current.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Intermediate durable component is not a no-follow directory" }
        }
    }
    override fun isRegularFile(segments: List<String>) = synchronized(lock) { resolveParent(segments)?.let { Files.isRegularFile(File(it, segments.last()).toPath(), LinkOption.NOFOLLOW_LINKS) } ?: false }
    override fun isDirectory(segments: List<String>) = synchronized(lock) { resolveDirectory(segments) != null }
    override fun size(segments: List<String>) = synchronized(lock) { openChannel(segments, StandardOpenOption.READ).use(FileChannel::size) }
    override fun openRead(segments: List<String>): DescriptorFileV2 = synchronized(lock) { JvmDescriptorFileV2(openChannel(segments, StandardOpenOption.READ)) }
    override fun createExclusive(segments: List<String>): DescriptorFileV2 = synchronized(lock) { JvmDescriptorFileV2(openChannel(segments, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) }
    override fun atomicReplace(parent: List<String>, from: String, to: String) = synchronized(lock) {
        val directory = requireNotNull(resolveDirectory(parent)); Files.move(File(directory, from).toPath(), File(directory, to).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit
    }
    override fun atomicMove(fromParent: List<String>, from: String, toParent: List<String>, to: String) = synchronized(lock) {
        val source = requireNotNull(resolveDirectory(fromParent)); val target = requireNotNull(resolveDirectory(toParent))
        try { Files.move(File(source, from).toPath(), File(target, to).toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic move unavailable") }; Unit
    }
    override fun delete(segments: List<String>) = synchronized(lock) { resolveParent(segments)?.let { Files.deleteIfExists(File(it, segments.last()).toPath()) }; Unit }
    override fun list(segments: List<String>): List<String> = synchronized(lock) {
        val directory = requireNotNull(resolveDirectory(segments)); Files.newDirectoryStream(directory.toPath()).use { it.map { child -> child.fileName.toString() }.sorted().toList() }
    }
    override fun syncDirectory(segments: List<String>) = synchronized(lock) { onDirectorySync(requireNotNull(resolveDirectory(segments)).canonicalFile) }
    private fun openChannel(segments: List<String>, vararg options: StandardOpenOption): FileChannel {
        val parent = requireNotNull(resolveParent(segments)); val final = File(parent, segments.last()); beforeComponentOpen(final)
        val openOptions: Array<OpenOption> =
            (options.map { it as OpenOption } + LinkOption.NOFOLLOW_LINKS).toTypedArray()
        return FileChannel.open(final.toPath(), *openOptions)
    }
    private fun resolveParent(segments: List<String>) = if (segments.isEmpty()) null else resolveDirectory(segments.dropLast(1))
    private fun resolveDirectory(segments: List<String>): File? {
        var current = root()
        segments.forEach { segment ->
            val next = File(current, segment); beforeComponentOpen(next)
            if (!Files.isDirectory(next.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            current = next
        }
        return current
    }
    private fun root() = requireNotNull(boundRoot) { "JVM descriptor filesystem is not root-bound" }
}

private class JvmDescriptorFileV2(private val channel: FileChannel) : DescriptorFileV2 {
    override fun read(bytes: ByteArray, offset: Int, count: Int) = channel.read(ByteBuffer.wrap(bytes, offset, count))
    override fun write(bytes: ByteArray, offset: Int, count: Int) { val buffer = ByteBuffer.wrap(bytes, offset, count); while (buffer.hasRemaining()) channel.write(buffer) }
    override fun sync() = channel.force(true)
    override fun close() = channel.close()
}

class SafeFilesystemV2(
    root: File,
    private val fault: DurableStoreFaultInjectorV2,
    backend: DescriptorFilesystemV2 = AndroidDescriptorFilesystemV2(),
) {
    private val rootPath = root.absoluteFile.toPath().normalize()
    private val backend = backend.bind(rootPath.toFile())

    fun child(vararg names: String): File {
        names.forEach(::validateSegment)
        return rootPath.resolve(names.joinToString(File.separator)).normalize().toFile().also(::requireContained)
    }
    fun ensureDirectory(directory: File) = backend.ensureDirectory(relative(directory))
    fun isFile(file: File) = backend.isRegularFile(relative(file))
    fun length(file: File) = backend.size(relative(file))
    fun readBytes(file: File): ByteArray {
        val output = ByteArrayOutputStream(); backend.openRead(relative(file)).use { descriptor ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = descriptor.read(buffer, 0, buffer.size); if (read < 0) break; if (read > 0) output.write(buffer, 0, read) }
        }; return output.toByteArray()
    }
    fun readLines(file: File) = readBytes(file).toString(Charsets.UTF_8).lines().dropLastWhile(String::isEmpty)
    fun list(directory: File): List<File> = backend.list(relative(directory)).map { name -> validateSegment(name); File(directory, name).also(::requireContained) }
    fun walk(directory: File): Sequence<File> = sequence {
        yield(directory); if (backend.isDirectory(relative(directory))) for (child in list(directory)) { yield(child); if (backend.isDirectory(relative(child))) yieldAll(walk(child).drop(1)) }
    }
    fun writeExclusive(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        prepareParent(file); fault.at(point); backend.createExclusive(relative(file)).use { it.write(bytes, 0, bytes.size); it.sync() }; syncParent(file)
    }
    fun writeImmutable(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        if (isFile(file)) { check(readBytes(file).contentEquals(bytes)); return }; writeExclusive(file, bytes, point)
    }
    fun atomicReplace(file: File, bytes: ByteArray, replacePoint: DurableStoreFaultPointV2) {
        prepareParent(file); val parent = parentSegments(file); val temporary = ".${file.name}.part"; backend.delete(parent + temporary)
        backend.createExclusive(parent + temporary).use { it.write(bytes, 0, bytes.size); it.sync() }
        fault.at(replacePoint); backend.atomicReplace(parent, temporary, file.name)
        try { fault.at(DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC); backend.syncDirectory(parent) }
        catch (error: Throwable) { throw PointerDirectorySyncUnknownV2(error) }
    }
    fun moveAtomic(from: File, to: File) {
        prepareParent(to); fault.at(DurableStoreFaultPointV2.ASSET_RENAME)
        backend.atomicMove(parentSegments(from), from.name, parentSegments(to), to.name)
        fault.at(DurableStoreFaultPointV2.ASSET_DIRECTORY_SYNC); backend.syncDirectory(parentSegments(to))
    }
    fun streamExclusive(file: File, input: InputStream): Pair<Long, ByteArray> {
        prepareParent(file); fault.at(DurableStoreFaultPointV2.PART_CREATE); val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
        input.use { source -> backend.createExclusive(relative(file)).use { descriptor ->
            val buffer = ByteArray(64 * 1024); while (true) { val read = source.read(buffer); if (read < 0) break; fault.at(DurableStoreFaultPointV2.PART_WRITE); descriptor.write(buffer, 0, read); digest.update(buffer, 0, read); length = Math.addExact(length, read.toLong()) }
            fault.at(DurableStoreFaultPointV2.PART_FILE_SYNC); descriptor.sync()
        } }; fault.at(DurableStoreFaultPointV2.PART_HASH); backend.syncDirectory(parentSegments(file)); return length to digest.digest()
    }
    fun digestAndLength(file: File): Pair<Long, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256"); var length = 0L; backend.openRead(relative(file)).use { descriptor ->
            val buffer = ByteArray(64 * 1024); while (true) { val read = descriptor.read(buffer, 0, buffer.size); if (read < 0) break; if (read > 0) { digest.update(buffer, 0, read); length = Math.addExact(length, read.toLong()) } }
        }; return length to digest.digest()
    }
    fun allocateExclusive(file: File, bytes: Long) {
        require(bytes > 0); prepareParent(file); val buffer = ByteArray(64 * 1024); backend.createExclusive(relative(file)).use { descriptor ->
            var remaining = bytes; while (remaining > 0) { val count = minOf(buffer.size.toLong(), remaining).toInt(); descriptor.write(buffer, 0, count); remaining -= count }; descriptor.sync()
        }; check(length(file) == bytes); syncParent(file)
    }
    fun delete(file: File, point: DurableStoreFaultPointV2) { fault.at(point); backend.delete(relative(file)); backend.syncDirectory(parentSegments(file)) }
    fun deleteTree(directory: File, point: DurableStoreFaultPointV2) {
        fault.at(point); if (!backend.isDirectory(relative(directory))) { backend.delete(relative(directory)); return }
        walk(directory).toList().asReversed().forEach { backend.delete(relative(it)) }; directory.parentFile?.let { backend.syncDirectory(relative(it)) }
    }
    private fun prepareParent(file: File) { requireContained(file); backend.ensureDirectory(parentSegments(file)) }
    private fun syncParent(file: File) = backend.syncDirectory(parentSegments(file))
    private fun parentSegments(file: File) = relative(requireNotNull(file.parentFile))
    private fun relative(file: File): List<String> {
        requireContained(file)
        val path = rootPath.relativize(file.absoluteFile.toPath().normalize())
        if (path.toString().isEmpty()) return emptyList()
        return path.map { it.toString() }.toList().onEach(::validateSegment)
    }
    private fun requireContained(file: File) { require(file.absoluteFile.toPath().normalize().startsWith(rootPath)) }
    private fun validateSegment(name: String) { require(name.matches(SEGMENT) && name != "." && name != "..") }
    companion object { private val SEGMENT = Regex("[A-Za-z0-9._-]{1,160}") }
}
