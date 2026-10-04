package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilitystorage.AndroidDirectorySync
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Platform-correct directory durability for canonical visibility state. */
internal object VisibilityGridDirectorySync {
    internal enum class Backend { ANDROID, HOST_JVM, WINDOWS_NO_OP }

    fun sync(directory: File) {
        require(directory.isDirectory) {
            "Cannot fsync a missing visibility-grid directory: ${directory.path}"
        }
        when (backend()) {
            Backend.ANDROID -> AndroidDirectorySync.sync(directory)
            Backend.HOST_JVM -> FileChannel.open(
                directory.toPath(),
                StandardOpenOption.READ,
            ).use { it.force(true) }
            Backend.WINDOWS_NO_OP -> Unit
        }
    }

    internal fun backend(
        runtimeName: String = System.getProperty("java.runtime.name").orEmpty(),
        vmName: String = System.getProperty("java.vm.name").orEmpty(),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Backend = when {
        runtimeName.contains("Android Runtime", ignoreCase = true) ||
            vmName.contains("Dalvik", ignoreCase = true) -> Backend.ANDROID
        osName.startsWith("Windows", ignoreCase = true) -> Backend.WINDOWS_NO_OP
        else -> Backend.HOST_JVM
    }
}
