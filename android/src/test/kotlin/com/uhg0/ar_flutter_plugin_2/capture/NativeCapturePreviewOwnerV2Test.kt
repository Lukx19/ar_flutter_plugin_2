package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class NativeCapturePreviewOwnerV2Test {
    private val root = Files.createTempDirectory("preview-owner").toFile()
    private val manifest = "a".repeat(64)
    private val capture = "b".repeat(64)
    private val other = "c".repeat(64)
    private val reference = "native-v2://$manifest/$capture"
    private val bytes = byteArrayOf(1, 2, 3)
    private fun owner() = NativeCapturePreviewOwnerV2(root) { _, _, target, _, _ ->
        requireNotNull(target.parentFile).mkdirs(); target.writeBytes(bytes); true
    }
    @Before fun initialize() { owner().reconcile(emptyList()) }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun `HEIC immutable originals keep their extension and survive deletion rollback`() {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toCaptureHashHex()
        val request = "d".repeat(64)
        val source = "immutable-heic-original".toByteArray()
        val sourceHash = hash(source)
        val record = "schema=8\nrevision=1\nrequest=$request\nprevious=-\nsecondPrevious=-\ncomponent=HEIC:${source.size}:$sourceHash\n"
        val manifestId = hash(record.toByteArray())
        val session = File(root.canonicalFile, "capture-v2-native/store/sessions/session").apply { mkdirs() }
        File(session, "root-A.ptr").writeText("1\n$manifestId\n${"e".repeat(64)}\n")
        File(session, "objects/$manifestId.root").apply { parentFile!!.mkdirs(); writeText(record) }
        val original = File(session, "assets/$capture/heic.$sourceHash.blob").apply { parentFile!!.mkdirs(); writeBytes(source) }
        File(session, "attempts/accepted/receipt.properties").apply {
            parentFile!!.mkdirs()
            writeText("kind=COMMITTED_PICTURE\nrootHash=$manifestId\ncaptureId=$capture\nrequestHash=$request\nrevision=1\nheicSizeBytes=${source.size}\nreceiptHash=${hash("$request|$manifestId".toByteArray())}\n")
        }
        val nativeReference = "native-v2://$manifestId/$capture"
        val owner = NativeCapturePreviewOwnerV2(root)
        owner.reconcile(listOf(nativeReference))
        val path = requireNotNull(owner.materialize(manifestId, capture, edge = 0))
        assertTrue(path.endsWith(".original.heic"))
        assertArrayEquals(source, File(path).readBytes())
        val token = owner.beginDeletion(listOf(nativeReference))
        assertFalse(File(path).exists())
        owner.rollbackDeletion(token)
        assertArrayEquals(source, File(path).readBytes())
        assertArrayEquals(source, original.readBytes())
        owner.commitDeletion(owner.beginDeletion(listOf(nativeReference)))
        assertNull(owner.materialize(manifestId, capture, edge = 0))
        assertArrayEquals(source, original.readBytes())
    }

    @Test fun `original inspection copies share detail bound and deletion rollback fences all variants`() {
        val owner = owner()
        val thumbnail = requireNotNull(owner.materialize(manifest, capture))
        val original = requireNotNull(owner.materialize(manifest, capture, edge = 0))
        val detail = requireNotNull(owner.materialize(manifest, capture, edge = 1024))
        val token = owner.beginDeletion(listOf(reference))
        assertFalse(File(thumbnail).exists()); assertFalse(File(original).exists()); assertFalse(File(detail).exists())
        assertNull(owner.materialize(manifest, capture, edge = 0))
        owner().rollbackDeletion(token)
        assertArrayEquals(bytes, File(original).readBytes())
        for (id in listOf("c", "d", "e", "f")) owner.materialize(manifest, id.repeat(64), edge = 0)
        val copies = File(root, "capture-v2-previews").listFiles().orEmpty()
        assertTrue(File(thumbnail).exists())
        assertEquals(3, copies.count { it.name.endsWith(".original.jpg") || it.name.endsWith(".detail.jpg") })
        owner.commitDeletion(owner.beginDeletion(listOf(reference)))
        assertNull(owner().materialize(manifest, capture, edge = 0))
    }

    @Test fun `session deletion reaches the durable store with the session id and refuses an empty id`() {
        val deleted = mutableListOf<String>()
        val owner = NativeCapturePreviewOwnerV2(root, { deleted += it; 7L }) { _, _, _, _, _ -> false }
        assertEquals(7L, owner.deleteSession("session-1"))
        assertThrows(IllegalArgumentException::class.java) { owner.deleteSession("") }
        assertEquals(listOf("session-1"), deleted)
    }

    @Test fun `committed deletion survives owner restart and isolates other captures`() {
        val owner = owner()
        val path = requireNotNull(owner.materialize(manifest, capture))
        val otherPath = requireNotNull(owner.materialize(manifest, other))
        val token = owner.beginDeletion(listOf(reference))
        assertFalse(File(path).exists())
        owner.commitDeletion(token)
        assertNull(owner().materialize(manifest, capture))
        assertArrayEquals(bytes, File(otherPath).readBytes())
        assertFalse(File(root, "capture-v2-previews").walkTopDown().any { it.isFile && it.extension == "jpg" && it.name.contains(capture) })
    }

    @Test fun `rollback after owner restart restores display copy and load authority`() {
        val path = requireNotNull(owner().materialize(manifest, capture))
        val token = owner().beginDeletion(listOf(reference))
        assertNull(owner().materialize(manifest, capture))
        owner().rollbackDeletion(token)
        assertArrayEquals(bytes, File(path).readBytes())
        assertEquals(path, owner().materialize(manifest, capture))
    }

    @Test fun `late queued load cannot recreate a preview after deletion`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = NativeCapturePreviewOwnerV2(root) { _, _, target, _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            requireNotNull(target.parentFile).mkdirs(); target.writeBytes(bytes); true
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val loading = executor.submit<String?> { owner.materialize(manifest, capture) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deleting = executor.submit<String> { owner.beginDeletion(listOf(reference)) }
            release.countDown()
            val path = requireNotNull(loading.get(5, TimeUnit.SECONDS))
            owner.commitDeletion(deleting.get(5, TimeUnit.SECONDS))
            assertFalse(File(path).exists())
            assertNull(owner().materialize(manifest, capture))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `untrusted paths and malformed references cannot delete unrelated files`() {
        val unrelated = File(root, "unrelated.jpg").apply { writeBytes(bytes) }
        assertThrows(IllegalStateException::class.java) { owner().beginDeletion(listOf("native-v2://../unrelated.jpg")) }
        assertThrows(IllegalArgumentException::class.java) { owner().commitDeletion("../../") }
        assertArrayEquals(bytes, unrelated.readBytes())
    }

    @Test fun `rollback of duplicate deletion never revives committed reference`() {
        val owner = owner()
        owner.materialize(manifest, capture)
        owner.commitDeletion(owner.beginDeletion(listOf(reference)))
        owner.rollbackDeletion(owner.beginDeletion(listOf(reference)))
        assertNull(owner.materialize(manifest, capture))
    }

    @Test fun `startup restores interrupted deletion before metadata commit`() {
        val path = requireNotNull(owner().materialize(manifest, capture))
        owner().beginDeletion(listOf(reference))
        owner().reconcile(listOf(reference))
        assertArrayEquals(bytes, File(path).readBytes())
        assertEquals(path, owner().materialize(manifest, capture))
        assertFalse(File(root, "capture-v2-previews").listFiles().orEmpty().any { it.isDirectory })
    }

    @Test fun `startup finalizes interrupted deletion after metadata commit`() {
        val path = requireNotNull(owner().materialize(manifest, capture))
        owner().beginDeletion(listOf(reference))
        // Recovery also removes an owned copy left at its original location.
        File(path).writeBytes(bytes)
        owner().reconcile(emptyList())
        assertFalse(File(path).exists())
        assertNull(owner().materialize(manifest, capture))
        assertFalse(File(root, "capture-v2-previews").walkTopDown().any { it.isFile && it.extension == "jpg" })
    }

    @Test fun `deletion and rollback fence both small thumbnail and lazy detailed copy`() {
        val owner = owner()
        val thumbnail = requireNotNull(owner.materialize(manifest, capture))
        val detailed = requireNotNull(owner.materialize(manifest, capture, edge = 1024))
        val token = owner.beginDeletion(listOf(reference))
        assertFalse(File(thumbnail).exists()); assertFalse(File(detailed).exists())
        assertNull(owner.materialize(manifest, capture, edge = 1024))
        owner.rollbackDeletion(token)
        assertArrayEquals(bytes, File(thumbnail).readBytes()); assertArrayEquals(bytes, File(detailed).readBytes())
        owner.commitDeletion(owner.beginDeletion(listOf(reference)))
        assertNull(owner.materialize(manifest, capture)); assertNull(owner.materialize(manifest, capture, edge = 1024))
    }

    @Test fun `detailed disk LRU keeps three copies without evicting persistent thumbnails`() {
        val owner = owner()
        repeat(5) { index ->
            val id = index.toString().padStart(64, '0')
            owner.materialize(manifest, id)
            owner.materialize(manifest, id, edge = 1024)
        }
        val files = File(root, "capture-v2-previews").listFiles().orEmpty()
        assertEquals(3, files.count { it.name.endsWith(".detail.jpg") })
        assertEquals(5, files.count { it.name.endsWith(".jpg") && !it.name.endsWith(".detail.jpg") })
    }

    @Test fun `startup reconciles each mixed transaction key independently and repeats safely`() {
        val keptPath = requireNotNull(owner().materialize(manifest, capture))
        val removedPath = requireNotNull(owner().materialize(manifest, other))
        owner().beginDeletion(listOf(reference, "native-v2://$manifest/$other"))
        repeat(2) { owner().reconcile(listOf(reference)) }
        assertArrayEquals(bytes, File(keptPath).readBytes())
        assertFalse(File(removedPath).exists())
        assertNull(owner().materialize(manifest, other))
        assertEquals(keptPath, owner().materialize(manifest, capture))
    }

    @Test fun `startup never removes a previously committed tombstone`() {
        owner().materialize(manifest, capture)
        owner().commitDeletion(owner().beginDeletion(listOf(reference)))
        repeat(2) { owner().reconcile(listOf(reference)) }
        assertNull(owner().materialize(manifest, capture))
    }

    @Test fun `materialization waits for valid authoritative startup reconciliation`() {
        val files = File(root, "fresh-process")
        var copies = 0
        val restarted = NativeCapturePreviewOwnerV2(files) { _, _, target, _, _ ->
            copies++; target.writeBytes(bytes); true
        }
        assertNull(restarted.materialize(manifest, capture))
        assertThrows(IllegalStateException::class.java) { restarted.reconcile(listOf("bad-reference")) }
        assertNull(restarted.materialize(manifest, capture))
        assertEquals(0, copies)
        restarted.reconcile(listOf(reference))
        assertNotNull(restarted.materialize(manifest, capture))
        assertEquals(1, copies)
    }
}
