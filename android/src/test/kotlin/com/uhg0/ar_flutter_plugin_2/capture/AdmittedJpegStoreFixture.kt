package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** Real temporary store plus one nonempty original JPEG with color and EXIF bytes. */
internal class AdmittedJpegStoreFixture(private val directory: File, lane: CaptureLane) : AutoCloseable {
    companion object {
        private const val COMPONENT_BOUND_BYTES = 1024L * 1024L
        private const val ORIGINAL_JPEG_BASE64 =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QBiRXhpZgAATU0AKgAAAAgAAgEOAAIAAAAhAAAAJgExAAIAAAAS" +
            "AAAASAAAAABvcmlnaW5hbCBjb2xvciByZXRlbnRpb24gZml4dHVyZQAAcmV0ZW50aW9uIGZpeHR1cmUA" +
            "/9sAQwADAgIDAgIDAwMDBAMDBAUIBQUEBAUKBwcGCAwKDAwLCgsLDQ4SEA0OEQ4LCxAWEBETFBUVFQwP" +
            "FxgWFBgSFBUU/9sAQwEDBAQFBAUJBQUJFA0LDRQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBQUFBQUFBQUFBQUFBQU/8AAEQgAEAAQAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAAB" +
            "AgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEV" +
            "UtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6" +
            "g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn" +
            "6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQH" +
            "BQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1" +
            "Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWm" +
            "p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/aAAwDAQACEQMR" +
            "AD8A+WdE8H42/JjHt/n0/Ttj5e/0Twfjb8mMe3+fT9O2Pl7HRPB+NvyY/D/Pp+nbHy+gaJ4Pxt+TH4f5" +
            "9P07Y+X2Mfne+phwjxF8PvH/2Q=="
    }

    val original = Base64.getDecoder().decode(ORIGINAL_JPEG_BASE64)
    val closed = AtomicInteger()
    val storeRoot = File(directory, "store")
    val request: CaptureCommitRequest
    val template: CapturePostOutputTemplateV2
    private val qualifier: CaptureAttemptQualifierV2
    private var componentsIssued = false
    var budget = newBudget()
        private set
    var store = newStore()
        private set

    init {
        val cut = CaptureLifecycleCut("retained-original-session", 1, "retained-original-group", 1,
            "ar", "view", 1, "binding", 1, 1)
        val identity = CaptureAttemptIdentity("retained-original-attempt", "retained-original-commit", 1, cut)
        qualifier = CaptureAttemptQualifierV2(identity, cut, 1)
        val profile = CaptureComponentProfile("jpeg", setOf(CaptureComponentKind.JPEG),
            COMPONENT_BOUND_BYTES, JpegBlurAnalyzer.WorkingMemoryBytes)
        val reservation = CaptureReservationLiability(JpegBlurAnalyzer.WorkingMemoryBytes,
            NativeCaptureReservationBoundsV2.physicalBytes(COMPONENT_BOUND_BYTES, 1), 1, 1,
            NativeCaptureReservationBoundsV2.rollbackBytes(COMPONENT_BOUND_BYTES), true)
        val accepted = CaptureAcceptedAttempt(identity, lane, profile, reservation,
            sha("retained-original-intent".toByteArray()), sha("retained-original-acceptance".toByteArray()))
        request = CaptureCommitRequest(accepted,
            listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, original.size.toLong(),
                sha(original), shaHex(original))), 1_000_000L,
            sha("pose".toByteArray()), sha("camera".toByteArray()),
            sha("validation".toByteArray()), sha("ledger".toByteArray()))
        template = CapturePostOutputTemplateV2(accepted, request.poseRecordHash, request.cameraModelHash,
            request.validationRecordHash, request.ledgerRecordHash)
    }

    fun components(): SharedCameraComponentSetV2 {
        check(!componentsIssued)
        componentsIssued = true
        return SharedCameraComponentSetV2(qualifier, listOf(CaptureComponentStreamV2(
            CaptureComponentKind.JPEG, CameraPlaneInputStreamV2(ByteBuffer.wrap(original)) {
                closed.incrementAndGet()
            })), request.exposureTimestampNanoseconds)
    }

    fun assertOriginal(receipt: CaptureReceipt) {
        val terminal = requireNotNull(receipt.terminal)
        assertEquals(original.size.toLong(), terminal.jpegSizeBytes)
        val manifest = requireNotNull(terminal.manifestId)
        val root = storeRoot.walkTopDown().single { it.isFile && it.name == "$manifest.root" }
        val metadata = root.inputStream().use { input ->
            CaptureRootMetadataReaderV2().root(manifest, input::read)
        }
        assertNotNull("Durable root must validate its receipt-bound SHA-256", metadata)
        assertEquals(receipt.requestHash, requireNotNull(metadata).request)
        assertEquals(receipt.requestHash, terminal.canonicalTerminalHash)
        val component = metadata.components.single { it.kind == CaptureComponentKind.JPEG.name }
        assertEquals(original.size.toLong(), component.length)
        assertEquals(shaHex(original), component.hash)
        val session = requireNotNull(root.parentFile.parentFile)
        val source = File(session, "assets/${terminal.captureId}/jpeg.${component.hash}.blob")
        assertTrue("Receipt-bound original asset must be durable", source.isFile)
        assertArrayEquals("Color, EXIF and compressed bytes must remain unchanged", original, source.readBytes())
    }

    fun reopen() {
        store.close()
        budget.close()
        budget = newBudget()
        store = newStore()
    }

    private fun newBudget() = StorageBudgetCoordinatorV2(File(directory, "budget"),
        StorageBudgetPolicyV2(16L * 1024L * 1024L, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4096L })) { 64L * 1024L * 1024L }

    private fun newStore() = DurableSessionStoreV2(storeRoot, budget,
        filesystemBackend = JvmDescriptorFilesystemV2())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
    private fun shaHex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    override fun close() {
        try { store.close() } finally { budget.close() }
    }
}
