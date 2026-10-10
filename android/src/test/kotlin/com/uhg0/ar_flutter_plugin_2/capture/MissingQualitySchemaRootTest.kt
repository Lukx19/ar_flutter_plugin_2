package com.uhg0.ar_flutter_plugin_2.capture

import java.security.MessageDigest
import org.junit.Assert.assertNull
import org.junit.Test

/** Preimage-compatible behavioral red: compiles without proposed quality types. */
class MissingQualitySchemaRootTest {
    @Test fun `new root without quality evidence fails closed`() {
        val bytes = "schema=6\nrevision=1\nrequest=${"a".repeat(64)}\nprevious=-\nsecondPrevious=-\ncomponent=JPEG:6:${"b".repeat(64)}\n".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toCaptureHashHex()
        assertNull(bytes.inputStream().use { CaptureRootMetadataReaderV2().root(hash, it::read) })
    }
}
