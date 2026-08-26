package com.uhg0.ar_flutter_plugin_2.capture

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Real app-private Linux descriptor and parent-directory fsync integration gate. */
class SafeFilesystemV2AndroidTest {
    @Test fun descriptorNoFollowAtomicReplaceAndParentFsyncCompleteOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "issue100-${System.nanoTime()}")
        try {
            val files = SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, AndroidDescriptorFilesystemV2)
            val accepted = files.child("accepted.properties")
            val pointer = files.child("root-A.ptr")
            files.writeExclusive(accepted, "accepted\n".toByteArray(), DurableStoreFaultPointV2.ACCEPTED_RECORD)
            files.atomicReplace(pointer, "pointer\n".toByteArray(), DurableStoreFaultPointV2.POINTER_SLOT_REPLACE)
            assertArrayEquals("accepted\n".toByteArray(), files.readBytes(accepted))
            assertArrayEquals("pointer\n".toByteArray(), files.readBytes(pointer))
            assertFalse(files.isFile(files.child("missing")))
        } finally {
            root.deleteRecursively()
        }
    }
}
