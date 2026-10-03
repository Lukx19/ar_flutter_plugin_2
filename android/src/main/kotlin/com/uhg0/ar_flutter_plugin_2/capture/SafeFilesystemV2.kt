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
import java.util.concurrent.atomic.AtomicBoolean

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

/** Immutable lexical components. Reusing these never skips backend no-follow traversal. */
internal class RelativeComponentsV2 private constructor(
    internal val nativeArray: Array<String>,
) : AbstractList<String>() {
    override val size: Int get() = nativeArray.size
    override fun get(index: Int): String = nativeArray[index]
    constructor(segments: List<String>) : this(segments.toTypedArray())
    fun child(names: Array<out String>) = RelativeComponentsV2(
        Array(size + names.size) { if (it < size) nativeArray[it] else names[it - size] },
    )
}

private fun List<String>.nativeComponents(): Array<String> =
    if (this is RelativeComponentsV2) nativeArray else toTypedArray()

private class RootRelativeFileV2(
    path: String,
    val owner: Any,
    val components: RelativeComponentsV2,
    private val rootedParent: File?,
) : File(path) {
    override fun getParentFile(): File? = rootedParent
}

/** A backend is rebound once to a trusted root; all later operations are root-relative. */
interface DescriptorFilesystemV2 : AutoCloseable {
    /** Returns a new backend that exclusively owns the root resource. The receiver remains borrowed. */
    fun bind(root: File): DescriptorFilesystemV2
    fun ensureDirectory(segments: List<String>)
    fun isRegularFile(segments: List<String>): Boolean
    fun isDirectory(segments: List<String>): Boolean
    fun size(segments: List<String>): Long
    /** Physical filesystem blocks retained by this file or directory. */
    fun allocatedSize(segments: List<String>): Long
    /** Allocation unit used by the filesystem containing this rooted path. */
    fun allocationUnit(segments: List<String>): Long = 4_096L
    fun openRead(segments: List<String>): DescriptorFileV2
    fun createExclusive(segments: List<String>): DescriptorFileV2
    /** Creates a physically-backed file of exactly [bytes] bytes. */
    fun allocateExclusive(segments: List<String>, bytes: Long) {
        require(bytes > 0)
        val buffer = ByteArray(64 * 1024)
        createExclusive(segments).use { descriptor ->
            var remaining = bytes
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                descriptor.write(buffer, 0, count)
                remaining -= count
            }
            descriptor.sync()
        }
    }
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
    external fun nativeAllocatedBlocks(root: Long, segments: Array<String>): Long
    external fun nativeAllocationUnit(root: Long, segments: Array<String>): Long
    external fun nativeOpenRead(root: Long, segments: Array<String>): Long
    external fun nativeCreateExclusive(root: Long, segments: Array<String>): Long
    external fun nativeAllocateExclusive(root: Long, segments: Array<String>, bytes: Long)
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
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    constructor() : this(null)
    override fun bind(root: File): DescriptorFilesystemV2 = synchronized(lock) {
        check(!closed.get()) { "Descriptor filesystem is closed" }
        check(rootDescriptor == null) { "Descriptor filesystem is already root-bound" }
        val descriptor = AndroidDescriptorNativeV2.nativeOpenRoot(root.absolutePath)
        try { AndroidDescriptorFilesystemV2(descriptor) }
        catch (error: Throwable) { AndroidDescriptorNativeV2.nativeClose(descriptor); throw error }
    }
    private fun root(): Long {
        check(!closed.get()) { "Descriptor filesystem is closed" }
        return requireNotNull(rootDescriptor) { "Descriptor filesystem is not root-bound" }
    }
    private inline fun <T> withRoot(block: (Long) -> T): T = synchronized(lock) { block(root()) }
    override fun ensureDirectory(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeEnsureDirectory(it, segments.nativeComponents()) }
    override fun isRegularFile(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeIsRegular(it, segments.nativeComponents()) }
    override fun isDirectory(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeIsDirectory(it, segments.nativeComponents()) }
    override fun size(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeSize(it, segments.nativeComponents()) }
    override fun allocatedSize(segments: List<String>) = withRoot {
        androidPhysicalBytesFromStatBlocks(
            AndroidDescriptorNativeV2.nativeAllocatedBlocks(it, segments.nativeComponents())
        )
    }
    override fun allocationUnit(segments: List<String>) = withRoot {
        AndroidDescriptorNativeV2.nativeAllocationUnit(it, segments.nativeComponents())
    }
    override fun openRead(segments: List<String>): DescriptorFileV2 =
        withRoot { AndroidNativeFileV2(AndroidDescriptorNativeV2.nativeOpenRead(it, segments.nativeComponents())) }
    override fun createExclusive(segments: List<String>): DescriptorFileV2 =
        withRoot { AndroidNativeFileV2(AndroidDescriptorNativeV2.nativeCreateExclusive(it, segments.nativeComponents())) }
    override fun allocateExclusive(segments: List<String>, bytes: Long) = withRoot {
        AndroidDescriptorNativeV2.nativeAllocateExclusive(it, segments.nativeComponents(), bytes)
    }
    override fun atomicReplace(parent: List<String>, from: String, to: String) = withRoot { AndroidDescriptorNativeV2.nativeAtomicReplace(it, parent.nativeComponents(), from, to) }
    override fun atomicMove(fromParent: List<String>, from: String, toParent: List<String>, to: String) = withRoot { AndroidDescriptorNativeV2.nativeAtomicMove(it, fromParent.nativeComponents(), from, toParent.nativeComponents(), to) }
    override fun delete(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeDelete(it, segments.nativeComponents()) }
    override fun list(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeList(it, segments.nativeComponents()).toList() }
    override fun syncDirectory(segments: List<String>) = withRoot { AndroidDescriptorNativeV2.nativeSyncDirectory(it, segments.nativeComponents()) }
    override fun close() = synchronized(lock) {
        if (closed.compareAndSet(false, true)) rootDescriptor?.let(AndroidDescriptorNativeV2::nativeClose)
    }
}

/** Android stat.st_blocks is defined in 512-byte units regardless of filesystem block size. */
internal fun androidPhysicalBytesFromStatBlocks(blocks: Long): Long {
    require(blocks >= 0)
    return Math.multiplyExact(blocks, 512L)
}

private class AndroidNativeFileV2(private val descriptor: Long) : DescriptorFileV2 {
    private val closed = AtomicBoolean(false)
    private fun openDescriptor(): Long { check(!closed.get()) { "File descriptor is closed" }; return descriptor }
    override fun read(bytes: ByteArray, offset: Int, count: Int) = AndroidDescriptorNativeV2.nativeRead(openDescriptor(), bytes, offset, count)
    override fun write(bytes: ByteArray, offset: Int, count: Int) = AndroidDescriptorNativeV2.nativeWrite(openDescriptor(), bytes, offset, count)
    override fun sync() = AndroidDescriptorNativeV2.nativeSync(openDescriptor())
    override fun close() { if (closed.compareAndSet(false, true)) AndroidDescriptorNativeV2.nativeClose(descriptor) }
}

/** JVM model: serialized component-by-component NOFOLLOW checks and exclusive final opens. */
class JvmDescriptorFilesystemV2(
    private val onDirectorySync: (File) -> Unit = { },
    private val beforeComponentOpen: (File) -> Unit = { },
    private val onRootClose: (File) -> Unit = { },
    private val onList: (File) -> Unit = { },
    private val boundRoot: File? = null,
    private val authoritativeAllocationUnit: ((File) -> Long)? = null,
) : DescriptorFilesystemV2 {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    override fun bind(root: File): DescriptorFilesystemV2 {
        check(!closed.get()) { "JVM descriptor filesystem is closed" }
        check(boundRoot == null) { "JVM descriptor filesystem is already root-bound" }
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root.toPath())
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        return JvmDescriptorFilesystemV2(
            onDirectorySync, beforeComponentOpen, onRootClose, onList, root.canonicalFile,
            authoritativeAllocationUnit,
        )
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
    override fun allocatedSize(segments: List<String>) = synchronized(lock) {
        val file = requireNotNull(resolveExisting(segments))
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", true)
        val unixBlocks = if (windows) null else try {
            (Files.getAttribute(file.toPath(), "unix:blocks", LinkOption.NOFOLLOW_LINKS) as Number)
                .toLong() * 512L
        } catch (_: Exception) { null }
        unixBlocks ?: run {
            val block = allocationUnitFor(file, windows)
            val logical = if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) block
                else Files.size(file.toPath())
            if (logical == 0L) 0L else Math.multiplyExact((logical - 1L) / block + 1L, block)
        }
    }
    override fun allocationUnit(segments: List<String>) = synchronized(lock) {
        val file = requireNotNull(resolveExisting(segments))
        allocationUnitFor(
            file,
            System.getProperty("os.name").orEmpty().startsWith("Windows", true),
        )
    }
    private fun allocationUnitFor(file: File, windows: Boolean): Long {
        authoritativeAllocationUnit?.invoke(file)?.let { return it.also { unit -> require(unit > 0) } }
        require(!windows) {
            "Windows allocation units require an authoritative injected filesystem probe"
        }
        return Files.getFileStore(file.toPath()).blockSize.coerceAtLeast(1L)
    }
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
        val directory = requireNotNull(resolveDirectory(segments)); onList(directory)
        Files.newDirectoryStream(directory.toPath()).use { it.map { child -> child.fileName.toString() }.sorted().toList() }
    }
    override fun syncDirectory(segments: List<String>) = synchronized(lock) { onDirectorySync(requireNotNull(resolveDirectory(segments)).canonicalFile) }
    private fun openChannel(segments: List<String>, vararg options: StandardOpenOption): FileChannel {
        val parent = requireNotNull(resolveParent(segments)); val final = File(parent, segments.last()); beforeComponentOpen(final)
        val openOptions: Array<OpenOption> =
            (options.map { it as OpenOption } + LinkOption.NOFOLLOW_LINKS).toTypedArray()
        return FileChannel.open(final.toPath(), *openOptions)
    }
    private fun resolveParent(segments: List<String>) = if (segments.isEmpty()) null else resolveDirectory(segments.dropLast(1))
    private fun resolveExisting(segments: List<String>): File? {
        if (segments.isEmpty()) return root()
        val parent = resolveParent(segments) ?: return null
        val value = File(parent, segments.last())
        return value.takeIf { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    }
    private fun resolveDirectory(segments: List<String>): File? {
        var current = root()
        segments.forEach { segment ->
            val next = File(current, segment); beforeComponentOpen(next)
            if (!Files.isDirectory(next.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            current = next
        }
        return current
    }
    private fun root(): File {
        check(!closed.get()) { "JVM descriptor filesystem is closed" }
        return requireNotNull(boundRoot) { "JVM descriptor filesystem is not root-bound" }
    }
    override fun close() = synchronized(lock) {
        if (closed.compareAndSet(false, true)) boundRoot?.let(onRootClose)
    }
}

private class JvmDescriptorFileV2(private val channel: FileChannel) : DescriptorFileV2 {
    private val closed = AtomicBoolean(false)
    private fun openChannel(): FileChannel { check(!closed.get()) { "File descriptor is closed" }; return channel }
    override fun read(bytes: ByteArray, offset: Int, count: Int) = openChannel().read(ByteBuffer.wrap(bytes, offset, count))
    override fun write(bytes: ByteArray, offset: Int, count: Int) { val buffer = ByteBuffer.wrap(bytes, offset, count); val output = openChannel(); while (buffer.hasRemaining()) output.write(buffer) }
    override fun sync() = openChannel().force(true)
    override fun close() { if (closed.compareAndSet(false, true)) channel.close() }
}

class SafeFilesystemV2(
    root: File,
    private val fault: DurableStoreFaultInjectorV2,
    backend: DescriptorFilesystemV2 = AndroidDescriptorFilesystemV2(),
) : AutoCloseable {
    private val rootPath = root.absoluteFile.toPath().normalize()
    private val closed = AtomicBoolean(false)
    private val backend = backend.bind(rootPath.toFile())
    // One synchronized workspace serves metadata reads, component copies, and
    // digest checks on this root; callers retain only independent result bytes.
    private val copyWorkspace = CaptureCopyWorkspace()
    private val pathOwner = Any()
    private val rootFile = RootRelativeFileV2(rootPath.toString(), pathOwner, RelativeComponentsV2(emptyList()), null)

    fun child(vararg names: String): File = child(rootFile, *names)

    fun child(base: File, vararg names: String): File {
        names.forEach(::validateSegment)
        var parent: File = ownedPath(base)
        for (name in names) {
            val components = (parent as RootRelativeFileV2).components.child(arrayOf(name))
            parent = RootRelativeFileV2(parent.path + File.separator + name, pathOwner, components, parent)
        }
        return parent
    }
    fun ensureDirectory(directory: File) = backend.ensureDirectory(relative(directory))
    fun isFile(file: File) = backend.isRegularFile(relative(file))
    fun isDirectory(file: File) = backend.isDirectory(relative(file))
    fun length(file: File) = backend.size(relative(file))
    fun allocatedLength(file: File) = backend.allocatedSize(relative(file))
    fun allocationUnit(file: File) = backend.allocationUnit(relative(file))
    fun allocatedTreeBytes(directory: File): Long =
        walk(directory).fold(0L) { total, file -> Math.addExact(total, allocatedLength(file)) }
    fun readBytes(file: File): ByteArray {
        val output = ByteArrayOutputStream()
        backend.openRead(relative(file)).use { descriptor ->
            copyWorkspace.transfer(
                read = { buffer, offset, count -> descriptor.read(buffer, offset, count) },
                write = { buffer, offset, count -> output.write(buffer, offset, count) },
            )
        }
        return output.toByteArray()
    }
    fun readLines(file: File) = readBytes(file).toString(Charsets.UTF_8).lines().dropLastWhile(String::isEmpty)
    internal fun <T> readDescriptor(file: File, read: (DescriptorFileV2) -> T): T =
        backend.openRead(relative(file)).use(read)
    fun list(directory: File): List<File> = backend.list(relative(directory)).map { name -> child(directory, name) }
    fun walk(directory: File): Sequence<File> = sequence {
        yield(directory); if (backend.isDirectory(relative(directory))) for (child in list(directory)) { yield(child); if (backend.isDirectory(relative(child))) yieldAll(walk(child).drop(1)) }
    }
    fun writeExclusive(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        prepareParent(file); fault.at(point); backend.createExclusive(relative(file)).use { it.write(bytes, 0, bytes.size); it.sync() }; syncParent(file)
    }
    fun writeImmutable(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        if (isFile(file)) { check(readBytes(file).contentEquals(bytes)); return }; writeExclusive(file, bytes, point)
    }
    fun atomicReplace(
        file: File,
        bytes: ByteArray,
        replacePoint: DurableStoreFaultPointV2?,
        directorySyncPoint: DurableStoreFaultPointV2? = DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC,
    ) {
        prepareParent(file); val parent = parentSegments(file); val temporary = ".${file.name}.part"; backend.delete(parent + temporary)
        backend.createExclusive(parent + temporary).use { it.write(bytes, 0, bytes.size); it.sync() }
        replacePoint?.let(fault::at); backend.atomicReplace(parent, temporary, file.name)
        try { directorySyncPoint?.let(fault::at); backend.syncDirectory(parent) }
        catch (error: Throwable) { throw PointerDirectorySyncUnknownV2(error) }
    }
    fun moveAtomic(from: File, to: File) {
        prepareParent(to); fault.at(DurableStoreFaultPointV2.ASSET_RENAME)
        backend.atomicMove(parentSegments(from), from.name, parentSegments(to), to.name)
        fault.at(DurableStoreFaultPointV2.ASSET_DIRECTORY_SYNC); backend.syncDirectory(parentSegments(to))
    }
    fun streamExclusive(file: File, input: InputStream): Pair<Long, ByteArray> {
        prepareParent(file); fault.at(DurableStoreFaultPointV2.PART_CREATE)
        val receipt = input.use { source ->
            backend.createExclusive(relative(file)).use { descriptor ->
                val measured = copyWorkspace.copy(
                    read = { buffer, offset, count -> source.read(buffer, offset, count) },
                    write = { buffer, offset, count ->
                        fault.at(DurableStoreFaultPointV2.PART_WRITE)
                        descriptor.write(buffer, offset, count)
                    },
                )
                fault.at(DurableStoreFaultPointV2.PART_FILE_SYNC)
                descriptor.sync()
                measured
            }
        }
        fault.at(DurableStoreFaultPointV2.PART_HASH)
        backend.syncDirectory(parentSegments(file))
        return receipt.length to receipt.digest
    }
    fun digestAndLength(file: File): Pair<Long, ByteArray> {
        return backend.openRead(relative(file)).use { descriptor ->
            copyWorkspace.digest { buffer, offset, count -> descriptor.read(buffer, offset, count) }
                .let { it.length to it.digest }
        }
    }

    fun allocateExclusive(file: File, bytes: Long) {
        require(bytes > 0); prepareParent(file); backend.allocateExclusive(relative(file), bytes); check(length(file) == bytes); syncParent(file)
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
        if (file is RootRelativeFileV2 && file.owner === pathOwner) return file.components
        val path = rootPath.relativize(containedPath(file))
        if (path.toString().isEmpty()) return rootFile.components
        return RelativeComponentsV2(path.map { it.toString().also(::validateSegment) })
    }
    private fun ownedPath(file: File): File =
        if (file is RootRelativeFileV2 && file.owner === pathOwner) file
        else child(*relative(file).toTypedArray())
    private fun containedPath(file: File): java.nio.file.Path {
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(rootPath))
        return path
    }
    private fun requireContained(file: File) {
        if (file !is RootRelativeFileV2 || file.owner !== pathOwner) containedPath(file)
    }
    private fun validateSegment(name: String) {
        // Authenticated group-local activation attempts need 174 ASCII characters. The 240
        // ceiling admits that canonical name while retaining margin below Windows' 255 limit.
        require(name.length in 1..240 && name != "." && name != "..")
        for (index in name.indices) {
            val character = name[index]
            require(character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character == '.' || character == '_' || character == '-')
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) backend.close() }
}
