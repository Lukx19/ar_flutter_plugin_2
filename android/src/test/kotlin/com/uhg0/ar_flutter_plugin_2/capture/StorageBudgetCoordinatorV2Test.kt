package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBudgetCoordinatorV2Test {
    @Test fun `identity bound verified reclaim is exact once across coordinator restart`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(64 * 1024, 0)
        val id = "ab".repeat(32)
        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 1_000_000 }.use { first ->
            val reservation = requireNotNull(first.reserve("canonical-surface:canonical:test", 4_096))
            first.commit(reservation, 4_096)
            first.reclaimVerifiedOnce(id, 4_096)
            assertEquals(0L, first.committedBytes())
        }
        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 1_000_000 }.use { reopened ->
            reopened.reclaimVerifiedOnce(id, 4_096)
            assertEquals(0L, reopened.committedBytes())
            reopened.forgetVerifiedReclaim(id)
            assertTrue(!File(root, "reclaims-v2/verified-$id.reclaim").exists())
        }
    }

    @Test fun `deterministic candidate target admits one durable winner across coordinators`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(1_000_000, 0)
        val first = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }
        val second = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }
        val target = File(root, "deterministic-target")
        val winner = first.reserveCandidateExclusive(
            "winner", File(root, "winner-staging"), target, mapOf("payload" to 1L), 16_384L,
        ) as StorageBudgetCandidateReservationV2.Reserved
        assertTrue(second.reserveCandidateExclusive(
            "loser", File(root, "loser-staging"), target, mapOf("payload" to 1L), 16_384L,
        ) is StorageBudgetCandidateReservationV2.TargetReserved)
        second.close()

        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }.use { reopened ->
            assertTrue(reopened.reserveCandidateExclusive(
                "reopened-loser", File(root, "reopened-loser-staging"), target,
                mapOf("payload" to 1L), 16_384L,
            ) is StorageBudgetCandidateReservationV2.TargetReserved)
        }
        assertTrue(first.releaseCandidate(winner.reservation, File(root, "winner-staging")))
        assertEquals(0L, first.reservedBytes())
        first.close()
    }

    @Test fun `group scoped candidates share one ledger and recover without crossing groups`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(1_000_000, 0)
        val groupA = File(root, "a".repeat(32)).apply { assertTrue(mkdirs()) }
        val groupB = File(root, "b".repeat(32)).apply { assertTrue(mkdirs()) }
        val first = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }
        val aStaging = File(groupA, "candidate-a.staging")
        val aTarget = File(groupA, "candidate-a")
        val a = requireNotNull(first.reserveCandidate(
            "canonical-surface:canonical:v6-migration", aStaging, aTarget, mapOf("root" to 1L), 16_384L,
        ))
        first.publishCandidate(a, aStaging, aTarget)
        val chargedA = first.verifyCandidate(a, aTarget)
        first.commit(a, chargedA)

        val orphan = File(groupB, "canonical-surface-canonical-v6-${"d".repeat(64)}.staging-1-1")
        val b = requireNotNull(first.reserveCandidate(
            "canonical-surface:canonical:v6-migration", orphan, File(groupB, "candidate-b"),
            mapOf("root" to 1L), 16_384L,
        ))
        val metadata = File(root, "reservations-v2/${b.token}.reservation")
        assertTrue(metadata.delete())
        first.close()

        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }.use { reopened ->
            assertEquals(chargedA, reopened.committedBytes())
            assertTrue(aTarget.isDirectory)
            assertTrue(!orphan.exists())
            assertTrue(groupA.listFiles().orEmpty().all { it.parentFile == groupA })
            assertTrue(groupB.listFiles().orEmpty().all { it.parentFile == groupB })
        }
    }

    @Test fun `group candidate authority refuses traversal and deeper targets`() {
        val root = directory(); val coordinator = StorageBudgetCoordinatorV2(
            root, StorageBudgetPolicyV2(1_000_000, 0), physicalFilesystem(),
        ) { 10_000_000 }
        val group = File(root, "c".repeat(32)).apply { assertTrue(mkdirs()) }
        val deeper = File(group, "nested").apply { assertTrue(mkdirs()) }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.reserveCandidate(
                "canonical-surface:canonical:v6-migration", File(deeper, "staging"), File(deeper, "target"),
                mapOf("root" to 1L), 16_384L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.reserveCandidate(
                "canonical-surface:canonical:v6-migration", File(root.parentFile, "escape-staging"),
                File(root.parentFile, "escape-target"), mapOf("root" to 1L), 16_384L,
            )
        }
        coordinator.close()
    }

    private val directories = mutableListOf<File>()
    @After fun cleanUp() { directories.forEach { it.deleteRecursively() } }

    @Test fun `physically persisted reservations serialize global quota and survive a new coordinator`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(100, 10)
        val first = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 100 }
        val token = first.reserve("capture:session:one", 80)!!
        assertEquals(80L, token.bytes)
        assertTrue(first.physicallyAllocatedBytes(token.token) >= token.bytes)
        assertNull(first.reserve("capture:session:two", 11))

        val restarted = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 100 }
        assertEquals(80, restarted.reservedBytes())
        restarted.commit(token, 60)
        assertEquals(60, restarted.committedBytes())
        restarted.reclaimVerified(60)
        assertEquals(0, restarted.committedBytes())
    }

    @Test fun `candidate tree is the sole physical backing through publication and crash recovery`() {
        val root = directory()
        val policy = StorageBudgetPolicyV2(1_000_000, 0)
        val coordinator = StorageBudgetCoordinatorV2(
            root, policy, physicalFilesystem(),
        ) { 10_000_000 }
        val staging = File(root, "candidate.staging-1")
        val target = File(root, "candidate-v6")
        val reservation = coordinator.reserveCandidate(
            "canonical-surface:canonical:v6-migration", staging, target,
            mapOf("root.v6" to 4096L, "pages.v6" to 8192L), 64_000L,
        )!!
        assertTrue(staging.isDirectory)
        assertTrue(!File(root, "reservations-v2/${reservation.token}.allocation").exists())
        val staged = coordinator.verifyCandidate(reservation, staging)
        assertTrue(reservation.bytes >= staged + 3L * 4_096L)
        coordinator.publishCandidate(reservation, staging, target)
        assertEquals(staged, coordinator.verifyCandidate(reservation, target))

        // Independently encode the durable COMMITTING cut: target renamed, ledger still old.
        File(root, "reservations-v2/${reservation.token}.reservation").writeText(
            "${reservation.owner}\n${reservation.bytes}\n${staging.name}\n${target.name}\nCOMMITTING:$staged:0\n"
        )
        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }.use {
            assertEquals(staged, it.committedBytes())
            assertEquals(0L, it.reservedBytes())
            assertTrue(target.isDirectory)
        }
    }

    @Test fun `candidate allocation rechecks free floor after every physical file cut`() {
        val root = directory()
        var checks = 0
        val coordinator = StorageBudgetCoordinatorV2(
            root, StorageBudgetPolicyV2(1_000_000, 10_000), physicalFilesystem(),
        ) { if (++checks < 4) 1_000_000 else 9_999 }
        val staging = File(root, "canonical-surface-canonical-v6-${"a".repeat(64)}.staging-1-1")
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.reserveCandidate(
                "canonical-surface:canonical:v6-migration", staging, File(root, "candidate-v6"),
                mapOf("one" to 4096L, "two" to 4096L), 64_000L,
            )
        }
        assertTrue(!staging.exists())
        assertTrue(File(root, "reservations-v2").listFiles().orEmpty().isEmpty())
    }

    @Test fun `pointer committing record completes exactly once across process reopen`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(1_000_000, 0)
        val first = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }
        val reservation = first.reservePointerPublication(
            "canonical-surface:canonical:selector", "a".repeat(64), 1,
            rootBeforeBytes = 0L, slotBeforeBytes = 4_096L, selectorBeforeBytes = 4_096L,
            commitBytes = 4_096L, maximumPhysicalBytes = 32_768L,
        )!!
        val metadata = File(root, "reservations-v2/${reservation.token}.reservation")
        metadata.appendText("COMMITTING:4096:0\n")
        first.close()

        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }.use { restarted ->
            assertEquals(4_096L, restarted.committedBytes())
            assertEquals(0L, restarted.reservedBytes())
            assertTrue(!metadata.exists())
            assertTrue(!File(root, "reservations-v2/${reservation.token}.allocation").exists())
        }
        StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 10_000_000 }.use { repeated ->
            assertEquals(4_096L, repeated.committedBytes())
            assertEquals(0L, repeated.reservedBytes())
        }
    }

    @Test fun `host and Android allocation seams report authoritative units`() {
        assertEquals(4_096L, androidPhysicalBytesFromStatBlocks(8))
        assertThrows(ArithmeticException::class.java) {
            androidPhysicalBytesFromStatBlocks(Long.MAX_VALUE)
        }
        val root = directory()
        SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, physicalFilesystem()).use {
            val file = it.child("tiny")
            it.writeExclusive(file, byteArrayOf(1), DurableStoreFaultPointV2.ACCEPTED_RECORD)
            val unit = it.allocationUnit(file)
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) {
                assertEquals(4_096L, unit)
                assertEquals(4_096L, it.allocatedLength(file))
            } else assertEquals(4_096L, unit)
        }
    }

    @Test fun `construction performs no reservation directory scan`() {
        val root = directory()
        val reservations = File(root, "reservations-v2").apply { assertTrue(mkdirs()) }
        repeat(100) { File(reservations, "junk-$it").writeText("not authority") }
        var lists = 0
        val coordinator = StorageBudgetCoordinatorV2(
            root,
            StorageBudgetPolicyV2(100, 0),
            JvmDescriptorFilesystemV2(onList = { lists++ }, authoritativeAllocationUnit = { 4_096L }),
        ) { 100 }
        assertEquals(0, lists)
        coordinator.close()
    }

    @Test fun `independent live coordinators refresh one process global ledger before every operation`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(100, 0)
        val first = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 100 }
        val second = StorageBudgetCoordinatorV2(root, policy, physicalFilesystem()) { 100 }
        val one = first.reserve("capture:session:one", 60)!!
        assertEquals(60L, second.reservedBytes())
        assertNull(second.reserve("capture:session:two", 41))
        assertTrue(second.release(one))
        assertEquals(0L, first.reservedBytes())
        assertEquals(100L, first.reserve("capture:session:three", 100)!!.bytes)
    }

    @Test fun `release is idempotent and cannot free a changed token`() {
        val coordinator = StorageBudgetCoordinatorV2(directory(), StorageBudgetPolicyV2(100, 0), physicalFilesystem()) { 100 }
        val reservation = coordinator.reserve("capture:session:one", 20)!!
        assertTrue(coordinator.release(reservation))
        assertTrue(!coordinator.release(reservation))
    }

    @Test fun `safe filesystem syncs the actual parent after authority replacement`() {
        val root = directory(); val synced = mutableListOf<File>()
        val files = SafeFilesystemV2(
            root,
            DurableStoreFaultInjectorV2 { },
            JvmDescriptorFilesystemV2(onDirectorySync = { synced += it.canonicalFile }),
        )
        val pointer = files.child("root-A.ptr")
        files.atomicReplace(pointer, "authority\n".toByteArray(), DurableStoreFaultPointV2.POINTER_SLOT_REPLACE)
        assertEquals("authority\n", files.readBytes(pointer).toString(Charsets.UTF_8))
        assertEquals(listOf(root.canonicalFile), synced)
    }

    @Test fun `JVM descriptor fake refuses replacement between containment and exclusive open`() {
        val root = directory(); var replaced = false
        val backend = JvmDescriptorFilesystemV2(beforeComponentOpen = { file ->
            if (!replaced && file.name == "accepted.properties") {
                replaced = true
                Files.write(file.toPath(), "attacker".toByteArray())
            }
        })
        val files = SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, backend)
        val accepted = files.child("accepted.properties")
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            files.writeExclusive(accepted, "authority".toByteArray(), DurableStoreFaultPointV2.ACCEPTED_RECORD)
        }
        assertEquals("attacker", String(Files.readAllBytes(accepted.toPath())))
    }

    @Test fun `JVM descriptor fake refuses an intermediate component replacement`() {
        val root = directory(); var armed = false; var replaced = false
        val backend = JvmDescriptorFilesystemV2(beforeComponentOpen = { component ->
            if (armed && !replaced && component.name == "attempts") {
                replaced = true
                component.deleteRecursively()
                Files.write(component.toPath(), "intermediate attacker".toByteArray())
            }
        })
        val files = SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, backend)
        val target = files.child("sessions", "session", "attempts", "attempt", "accepted.properties")
        files.ensureDirectory(requireNotNull(target.parentFile))
        armed = true
        assertThrows(IllegalStateException::class.java) {
            files.writeExclusive(target, "authority".toByteArray(), DurableStoreFaultPointV2.ACCEPTED_RECORD)
        }
        assertTrue(replaced)
        assertEquals("intermediate attacker", File(root, "sessions/session/attempts").readText())
        assertTrue(!target.exists())
    }

    @Test fun `root ownership closes once across repeated bindings and rejects use after close`() {
        val root = directory(); val closedRoots = mutableListOf<File>()
        val borrowedFactory = JvmDescriptorFilesystemV2(onRootClose = { closedRoots += it })
        repeat(24) { index ->
            SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, borrowedFactory).use { files ->
                val value = files.child("cycle-$index")
                files.writeExclusive(value, byteArrayOf(index.toByte()), DurableStoreFaultPointV2.ACCEPTED_RECORD)
            }
        }
        assertEquals(24, closedRoots.size)

        val files = SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, borrowedFactory)
        val existing = files.child("cycle-0")
        files.close()
        files.close()
        assertEquals(25, closedRoots.size)
        assertThrows(IllegalStateException::class.java) { files.isFile(existing) }

        // SafeFilesystem closes only each independently bound owner, never the injected factory.
        SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, borrowedFactory).close()
        assertEquals(26, closedRoots.size)
    }

    private fun physicalFilesystem() =
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L })

    private fun directory(): File = File.createTempFile("storage-budget-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
}
