package com.uhg0.ar_flutter_plugin_2.m0

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Android/Linux directory fsync used by the native schema-5 campaign. */
object M0AndroidDirectorySync {
    fun sync(directory: File) {
        require(directory.isDirectory) { "Cannot fsync a missing directory: ${directory.path}" }
        val descriptor = Os.open(
            directory.absolutePath,
            // Android's public OsConstants omits O_DIRECTORY; opening a
            // directory read-only is sufficient for fsync on the target
            // Linux filesystems.
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
