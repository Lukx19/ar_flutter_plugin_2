package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.nio.file.Files
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
    private fun owner() = NativeCapturePreviewOwnerV2(root) { _, _, target ->
        requireNotNull(target.parentFile).mkdirs(); target.writeBytes(bytes); true
    }
    @Before fun initialize() { owner().reconcile(emptyList()) }
    @After fun cleanup() { root.deleteRecursively() }

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
        val owner = NativeCapturePreviewOwnerV2(root) { _, _, target ->
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
        val restarted = NativeCapturePreviewOwnerV2(files) { _, _, target ->
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
