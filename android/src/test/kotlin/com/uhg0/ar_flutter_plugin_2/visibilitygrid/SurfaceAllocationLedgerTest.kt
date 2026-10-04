package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceAllocationLedgerTest {
    @Test
    fun `restart and aborted reservation burn IDs without reuse`() {
        val directory = Files.createTempDirectory("canonical-surface-ledger-").toFile()
        try {
            val group = SurfaceGroup("group-a")
            val faulted = opened(SurfaceOwnership.open(group, directory, fault = SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH))
            val aborted = refused(faulted.apply(command("one", voxel = Voxel(0, 0, 0))))
            assertEquals(SurfaceOwnershipRefusal.DURABILITY_FAILURE, aborted.reason)
            assertEquals(2, aborted.receipt.nextSurfaceIdHighWater)
            faulted.close()

            val restarted = opened(SurfaceOwnership.open(group, directory))
            val accepted = accepted(restarted.apply(command("two", voxel = Voxel(1, 0, 0))))
            assertEquals(2, accepted.owners.single().id.value)
            assertEquals(3, accepted.receipt.nextSurfaceIdHighWater)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `exact replay returns the stored receipt while changed replay conflicts`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("replay")))
        val first = accepted(owner.apply(command("same", voxel = Voxel(0, 0, 0))))
        val replay = accepted(owner.apply(command("same", voxel = Voxel(0, 0, 0))))
        assertEquals(first, replay)
        val conflict = refused(owner.apply(command("same", voxel = Voxel(1, 0, 0))))
        assertEquals(SurfaceOwnershipRefusal.IDENTITY_CONFLICT, conflict.reason)
        assertEquals(2, conflict.receipt.nextSurfaceIdHighWater)
    }

    @Test
    fun `restore rejects fork corruption and final exhaustion without mutation`() {
        val directory = Files.createTempDirectory("canonical-surface-restore-").toFile()
        try {
            val group = SurfaceGroup("fork")
            val owner = opened(SurfaceOwnership.open(group, directory))
            accepted(owner.apply(command("one", voxel = Voxel(0, 0, 0))))
            owner.close()
            val ledger = requireNotNull(directory.listFiles { file -> file.name.endsWith(".ledger") }).single()
            ledger.writeBytes(ByteArray(0))
            assertEquals(SurfaceOwnershipRestoreRefusal.FORK, (SurfaceOwnership.open(group, directory) as SurfaceOwnershipOpenResult.Refused).reason)
            ledger.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(SurfaceOwnershipRestoreRefusal.CORRUPT, (SurfaceOwnership.open(group, directory) as SurfaceOwnershipOpenResult.Refused).reason)
        } finally { directory.deleteRecursively() }

        val exhaustedDirectory = Files.createTempDirectory("canonical-surface-exhausted-").toFile()
        try {
            val group = SurfaceGroup("exhausted")
            val ledger = exhaustedDirectory.resolve("canonical-surface-surface-${testSha256(group.value.encodeToByteArray()).toLowerHex()}.ledger")
            ledger.writeBytes(reservationFixture(group, endExclusive = 0x1_0000_0001L))
            val refusal = SurfaceOwnership.open(group, exhaustedDirectory) as SurfaceOwnershipOpenResult.Refused
            assertEquals(SurfaceOwnershipRestoreRefusal.CORRUPT, refusal.reason)
        } finally { exhaustedDirectory.deleteRecursively() }

        val maximumDirectory = Files.createTempDirectory("canonical-surface-maximum-").toFile()
        try {
            val group = SurfaceGroup("maximum")
            val ledger = maximumDirectory.resolve("canonical-surface-surface-${testSha256(group.value.encodeToByteArray()).toLowerHex()}.ledger")
            ledger.writeBytes(reservationFixture(group, endExclusive = 0x1_0000_0000L))
            val owner = opened(SurfaceOwnership.open(group, maximumDirectory))
            val refusal = refused(owner.apply(command("past-maximum", Voxel(0, 0, 0))))
            assertEquals(SurfaceOwnershipRefusal.EXHAUSTED, refusal.reason)
            assertEquals(0x1_0000_0000L, refusal.receipt.nextSurfaceIdHighWater)
        } finally { maximumDirectory.deleteRecursively() }
    }

    @Test
    fun `file restart exercises real codec and durable fault cut`() {
        val directory = Files.createTempDirectory("canonical-surface-codec-").toFile()
        try {
            val group = SurfaceGroup("codec")
            val owner = opened(SurfaceOwnership.open(group, directory))
            val original = accepted(owner.apply(command("first", voxel = Voxel(-1, -30, 29))))
            owner.close()
            val restored = opened(SurfaceOwnership.open(group, directory))
            val replay = accepted(restored.apply(command("first", voxel = Voxel(-1, -30, 29))))
            assertEquals(original.receipt, replay.receipt)
            assertEquals(original.owners.single().id, replay.owners.single().id)
            assertEquals(original.owners.single().voxel, replay.owners.single().voxel)
            assertTrue(requireNotNull(directory.listFiles()).any { it.name.endsWith(".ledger") && it.length() > 0 })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `snapshot declared counts are bounded before materialization`() {
        val configuration = SurfaceOwnershipConfiguration(surfaceCapacity = 1, receiptCapacity = 1)
        listOf(2 to 0, 0 to 2).forEachIndexed { index, (rowCount, receiptCount) ->
            val directory = Files.createTempDirectory("canonical-surface-bounded-$index-").toFile()
            try {
                val group = SurfaceGroup("bounded-$index")
                val snapshot = directory.resolve("canonical-surface-surface-${testSha256(group.value.encodeToByteArray()).toLowerHex()}.snapshot")
                snapshot.writeBytes(snapshotCountFixture(rowCount, receiptCount))
                val refusal = SurfaceOwnership.open(group, directory, configuration) as SurfaceOwnershipOpenResult.Refused
                assertEquals(SurfaceOwnershipRestoreRefusal.CORRUPT, refusal.reason)
            } finally { directory.deleteRecursively() }
        }
    }

    private fun command(id: String, voxel: Voxel) = SurfaceOwnershipCommand(id, listOf(SurfaceCandidate(null, voxel, 0, 0, 192)))
    private fun reservationFixture(group: SurfaceGroup, endExclusive: Long): ByteArray {
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d33524c)
                data.writeInt(1)
                data.writeLong(1)
                data.writeLong(1)
                data.writeLong(endExclusive)
                data.write(testSha256(group.value.encodeToByteArray()))
                data.write(ByteArray(32))
                data.write(ByteArray(32))
                data.write(ByteArray(32))
            }
            output.toByteArray()
        }
        return body + testSha256(body)
    }
    private fun snapshotCountFixture(rowCount: Int, receiptCount: Int): ByteArray {
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d33534f)
                data.writeInt(1)
                data.writeLong(1)
                data.writeInt(rowCount)
                if (rowCount == 0) data.writeInt(receiptCount)
            }
            output.toByteArray()
        }
        return body + testSha256(body)
    }
    private fun opened(result: SurfaceOwnershipOpenResult) = (result as SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: SurfaceOwnershipResult) = result as SurfaceOwnershipResult.Accepted
    private fun refused(result: SurfaceOwnershipResult) = result as SurfaceOwnershipResult.Refused
}
