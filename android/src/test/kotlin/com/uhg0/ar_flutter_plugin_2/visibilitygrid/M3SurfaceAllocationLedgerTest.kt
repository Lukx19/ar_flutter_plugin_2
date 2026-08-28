package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3SurfaceAllocationLedgerTest {
    @Test
    fun `restart and aborted reservation burn IDs without reuse`() {
        val directory = Files.createTempDirectory("m3-ledger-").toFile()
        try {
            val group = M3SurfaceGroup("group-a")
            val faulted = opened(M3SurfaceOwnership.open(group, directory, fault = M3SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH))
            val aborted = refused(faulted.apply(command("one", voxel = M3Voxel(0, 0, 0))))
            assertEquals(M3SurfaceOwnershipRefusal.DURABILITY_FAILURE, aborted.reason)
            assertEquals(2, aborted.receipt.nextSurfaceIdHighWater)
            faulted.close()

            val restarted = opened(M3SurfaceOwnership.open(group, directory))
            val accepted = accepted(restarted.apply(command("two", voxel = M3Voxel(1, 0, 0))))
            assertEquals(2, accepted.owners.single().id.value)
            assertEquals(3, accepted.receipt.nextSurfaceIdHighWater)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `exact replay returns the stored receipt while changed replay conflicts`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("replay")))
        val first = accepted(owner.apply(command("same", voxel = M3Voxel(0, 0, 0))))
        val replay = accepted(owner.apply(command("same", voxel = M3Voxel(0, 0, 0))))
        assertEquals(first, replay)
        val conflict = refused(owner.apply(command("same", voxel = M3Voxel(1, 0, 0))))
        assertEquals(M3SurfaceOwnershipRefusal.IDENTITY_CONFLICT, conflict.reason)
        assertEquals(2, conflict.receipt.nextSurfaceIdHighWater)
    }

    @Test
    fun `restore rejects fork corruption and final exhaustion without mutation`() {
        val directory = Files.createTempDirectory("m3-restore-").toFile()
        try {
            val group = M3SurfaceGroup("fork")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            accepted(owner.apply(command("one", voxel = M3Voxel(0, 0, 0))))
            owner.close()
            val ledger = requireNotNull(directory.listFiles { file -> file.name.endsWith(".ledger") }).single()
            ledger.writeBytes(ByteArray(0))
            assertEquals(M3SurfaceOwnershipRestoreRefusal.FORK, (M3SurfaceOwnership.open(group, directory) as M3SurfaceOwnershipOpenResult.Refused).reason)
            ledger.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(M3SurfaceOwnershipRestoreRefusal.CORRUPT, (M3SurfaceOwnership.open(group, directory) as M3SurfaceOwnershipOpenResult.Refused).reason)
        } finally { directory.deleteRecursively() }

        val exhaustedDirectory = Files.createTempDirectory("m3-exhausted-").toFile()
        try {
            val group = M3SurfaceGroup("exhausted")
            val ledger = exhaustedDirectory.resolve("m3-surface-${sha256(group.value.encodeToByteArray()).hex()}.ledger")
            ledger.writeBytes(reservationFixture(group, endExclusive = 0x1_0000_0001L))
            val refusal = M3SurfaceOwnership.open(group, exhaustedDirectory) as M3SurfaceOwnershipOpenResult.Refused
            assertEquals(M3SurfaceOwnershipRestoreRefusal.EXHAUSTED, refusal.reason)
        } finally { exhaustedDirectory.deleteRecursively() }

        val maximumDirectory = Files.createTempDirectory("m3-maximum-").toFile()
        try {
            val group = M3SurfaceGroup("maximum")
            val ledger = maximumDirectory.resolve("m3-surface-${sha256(group.value.encodeToByteArray()).hex()}.ledger")
            ledger.writeBytes(reservationFixture(group, endExclusive = 0x1_0000_0000L))
            val owner = opened(M3SurfaceOwnership.open(group, maximumDirectory))
            val refusal = refused(owner.apply(command("past-maximum", M3Voxel(0, 0, 0))))
            assertEquals(M3SurfaceOwnershipRefusal.EXHAUSTED, refusal.reason)
            assertEquals(0x1_0000_0000L, refusal.receipt.nextSurfaceIdHighWater)
        } finally { maximumDirectory.deleteRecursively() }
    }

    @Test
    fun `file restart exercises real codec and durable fault cut`() {
        val directory = Files.createTempDirectory("m3-codec-").toFile()
        try {
            val group = M3SurfaceGroup("codec")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            val original = accepted(owner.apply(command("first", voxel = M3Voxel(-1, -30, 29))))
            owner.close()
            val restored = opened(M3SurfaceOwnership.open(group, directory))
            val replay = accepted(restored.apply(command("first", voxel = M3Voxel(-1, -30, 29))))
            assertEquals(original.receipt, replay.receipt)
            assertEquals(original.owners.single().id, replay.owners.single().id)
            assertEquals(original.owners.single().voxel, replay.owners.single().voxel)
            assertTrue(requireNotNull(directory.listFiles()).any { it.name.endsWith(".ledger") && it.length() > 0 })
        } finally { directory.deleteRecursively() }
    }

    private fun command(id: String, voxel: M3Voxel) = M3SurfaceOwnershipCommand(id, listOf(M3SurfaceCandidate(voxel = voxel, normalX = 0.0, normalY = 0.0, normalZ = 1.0, confidence = 0.75)))
    private fun reservationFixture(group: M3SurfaceGroup, endExclusive: Long): ByteArray {
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d33524c)
                data.writeInt(1)
                data.writeLong(1)
                data.writeLong(1)
                data.writeLong(endExclusive)
                data.write(sha256(group.value.encodeToByteArray()))
                data.write(ByteArray(32))
                data.write(ByteArray(32))
                data.write(ByteArray(32))
            }
            output.toByteArray()
        }
        return body + sha256(body)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
    private fun refused(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Refused
}
