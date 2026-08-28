package com.uhg0.ar_flutter_plugin_2.capture

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Real app-private Linux descriptor and parent-directory fsync integration gate. */
class SafeFilesystemV2AndroidTest {
    @Test fun nestedOpenatRejectsIntermediateSymlinkAndFsyncsAtomicParent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "issue100-${System.nanoTime()}")
        val outside = File(context.cacheDir, "issue100-outside-${System.nanoTime()}")
        val sessions = File(root, "sessions")
        var intermediateSymlinkInstalled = false
        try {
            val files = SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, AndroidDescriptorFilesystemV2())
            try {
                val accepted = files.child("sessions", "session", "attempts", "attempt", "accepted.properties")
                val pointer = files.child("sessions", "session", "root-A.ptr")
                files.writeExclusive(accepted, "accepted\n".toByteArray(), DurableStoreFaultPointV2.ACCEPTED_RECORD)
                files.atomicReplace(pointer, "pointer\n".toByteArray(), DurableStoreFaultPointV2.POINTER_SLOT_REPLACE)
                assertArrayEquals("accepted\n".toByteArray(), files.readBytes(accepted))
                assertArrayEquals("pointer\n".toByteArray(), files.readBytes(pointer))

                val retained = File(root, "sessions-retained")
                check(outside.mkdir())
                check(sessions.renameTo(retained))
                Os.symlink(outside.absolutePath, sessions.absolutePath)
                intermediateSymlinkInstalled = true
                try {
                    files.writeExclusive(
                        files.child("sessions", "attacker", "accepted.properties"),
                        "escaped".toByteArray(),
                        DurableStoreFaultPointV2.ACCEPTED_RECORD,
                    )
                    fail("intermediate symlink must be rejected")
                } catch (_: java.io.IOException) {
                    // openat(O_DIRECTORY|O_NOFOLLOW) rejects the swapped component.
                }
                assertFalse(File(outside, "attacker/accepted.properties").exists())
                Os.remove(sessions.absolutePath)
                intermediateSymlinkInstalled = false
                check(retained.renameTo(sessions))
                assertArrayEquals("pointer\n".toByteArray(), files.readBytes(pointer))
            } finally {
                files.close()
            }
            files.close()
            try {
                files.isFile(File(root, "sessions/session/root-A.ptr"))
                fail("closed root descriptor must reject use")
            } catch (_: IllegalStateException) {
                // Kotlin ownership guard prevents any JNI call after nativeClose.
            }
            repeat(32) {
                SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, AndroidDescriptorFilesystemV2()).use { rebound ->
                    assertArrayEquals("pointer\n".toByteArray(), rebound.readBytes(File(root, "sessions/session/root-A.ptr")))
                }
            }
        } finally {
            if (intermediateSymlinkInstalled) Os.remove(sessions.absolutePath)
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
