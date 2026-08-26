package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Injectable cut points used only by deterministic JVM fault adapters. */
enum class DurableStoreFaultPointV2 {
    ACCEPTED_RECORD, COMPONENT_OPEN, PART_CREATE, PART_WRITE, PART_HASH, LENGTH_CHECK,
    PART_FILE_SYNC, ASSET_RENAME, ASSET_DIRECTORY_SYNC, ROOT_WRITE, ROOT_FILE_SYNC,
    RECEIPT_WRITE, RECEIPT_FILE_SYNC, POINTER_SLOT_REPLACE, POINTER_DIRECTORY_SYNC,
    ABANDONMENT_RECORD, ABANDONMENT_CLEANUP, TOMBSTONE_RECORD, TOMBSTONE_DIRECTORY_SYNC,
    DELETE_RECLAIM, RECOVERY,
}

fun interface DurableStoreFaultInjectorV2 { fun at(point: DurableStoreFaultPointV2) }

/**
 * The only filesystem primitive used by the V2 store.  It accepts generated
 * canonical segments only, rejects symlink traversal, and refuses a fallback
 * when atomic same-directory replacement is unavailable.
 */
class SafeFilesystemV2(private val root: File, private val fault: DurableStoreFaultInjectorV2) {
    init { require(root.exists() || root.mkdirs()); verify(root) }

    fun child(vararg names: String): File {
        var result = root
        names.forEach { name ->
            require(name.matches(Regex("[A-Za-z0-9._-]{1,160}")) && name != "." && name != "..") { "Unsafe durable path segment" }
            result = File(result, name)
        }
        require(result.toPath().normalize().startsWith(root.toPath().normalize())) { "Durable path escapes root" }
        var current: File? = result.parentFile
        while (current != null && current.toPath().normalize().startsWith(root.toPath().normalize())) { verify(current); current = current.parentFile }
        return result
    }

    fun writeExclusive(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2) {
        requireContained(file); file.parentFile?.mkdirs(); verify(file.parentFile)
        check(!file.exists()) { "Immutable durable record already exists" }
        fault.at(point); FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        syncDirectory(file.parentFile, point)
    }
    fun atomicReplace(file: File, bytes: ByteArray, replacePoint: DurableStoreFaultPointV2) {
        requireContained(file); file.parentFile?.mkdirs(); verify(file.parentFile)
        val temporary = File(file.parentFile, ".${file.name}.part")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        fault.at(replacePoint)
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic same-directory replace unavailable") }
        syncDirectory(file.parentFile, DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC)
    }
    fun moveSameDirectory(from: File, to: File) {
        requireContained(from); requireContained(to); verify(from); verify(to.parentFile)
        fault.at(DurableStoreFaultPointV2.ASSET_RENAME)
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic same-filesystem move unavailable") }
        syncDirectory(to.parentFile, DurableStoreFaultPointV2.ASSET_DIRECTORY_SYNC)
    }
    fun syncDirectory(directory: File, point: DurableStoreFaultPointV2) {
        requireContained(directory); fault.at(point)
        FileOutputStream(File(directory, ".sync")).use { it.fd.sync() }
        File(directory, ".sync").delete()
    }
    fun delete(file: File, point: DurableStoreFaultPointV2) { requireContained(file); fault.at(point); if (file.exists() && !file.delete()) error("Cannot delete durable file") }
    private fun requireContained(file: File) { require(file.toPath().normalize().startsWith(root.toPath().normalize())) { "Durable file escapes root" }; verify(file.parentFile) }
    private fun verify(file: File?) { if (file != null && file.exists()) check(!Files.isSymbolicLink(file.toPath())) { "Symlink durable path is refused" } }
}
