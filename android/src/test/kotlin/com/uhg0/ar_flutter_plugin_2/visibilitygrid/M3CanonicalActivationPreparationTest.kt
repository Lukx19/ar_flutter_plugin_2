package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalActivationPreparationTest {
    @Test
    fun `v1 through v5 prepare through the production codecs with exact current bytes`() {
        for (version in 1..5) withFixture("activation-v$version") { group, directory ->
            val expected = if (version == 1) null else writeVersionFixture(directory, group, version)
            val legacyBefore = legacyFiles(directory).mapValues { it.value.readBytes().toList() }
            val migration = migrated(group, directory)
            val siblingBefore = fileBytes(migration.candidateDirectory)
            val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            if (expected == null) assertEquals(M3CanonicalActivationCurrent.None, plan.current)
            else assertCurrent(expected, plan.current)
            assertEquals(legacyBefore, legacyFiles(directory).mapValues { it.value.readBytes().toList() })
            assertEquals(siblingBefore, fileBytes(migration.candidateDirectory))
            assertTrue(plan.receipt.retainedBytes <= 1_048_576L)
        }
    }

    @Test
    fun `selection coalesces exact duplicates and types every conflicting final cut`() {
        data class Case(val name: String, val nextHigh: Long = 1,
            val receipts: List<ReceiptSpec>, val refusal: M3CanonicalActivationRefusal?)
        val exact = ReceiptSpec(0x11, 0x21, "current")
        val cases = listOf(
            Case("duplicate", receipts = listOf(exact, exact), refusal = null),
            Case("changed", receipts = listOf(exact, exact.copy(command = "currenz")),
                refusal = M3CanonicalActivationRefusal.CHANGED_RECEIPT),
            Case("fork", receipts = listOf(exact, exact.copy(fingerprint = 0x22)),
                refusal = M3CanonicalActivationRefusal.FORKED_IDENTITY),
            Case("ambiguity", receipts = listOf(exact, ReceiptSpec(0x12, 0x22, "another")),
                refusal = M3CanonicalActivationRefusal.AMBIGUOUS_CURRENT),
            Case("live-mismatch", receipts = listOf(exact.copy(live = 1)),
                refusal = M3CanonicalActivationRefusal.INCOMPATIBLE_FINAL_CUT),
            Case("high-water-mismatch", nextHigh = 2, receipts = listOf(exact.copy(high = 1)),
                refusal = M3CanonicalActivationRefusal.INCOMPATIBLE_FINAL_CUT),
        )
        cases.forEach { case -> withFixture("activation-${case.name}") { group, directory ->
            val expected = writeVersionFixture(directory, group, 5, case.nextHigh, case.receipts)
            val legacyBefore = legacyFiles(directory).mapValues { it.value.readBytes().toList() }
            val migration = migrated(group, directory)
            val siblingBefore = fileBytes(migration.candidateDirectory)
            val result = M3CanonicalActivation.prepare(group, directory, budget())
            if (case.refusal == null) {
                val plan = prepared(result)
                assertCurrent(requireNotNull(expected), plan.current)
                assertEquals(2L, plan.receipt.scannedReceipts)
                assertEquals(2L, plan.receipt.finalCutReceipts)
            } else assertEquals(case.refusal,
                (result as M3CanonicalActivationPreparation.Refused).reason)
            assertEquals(legacyBefore, legacyFiles(directory).mapValues { it.value.readBytes().toList() })
            assertEquals(siblingBefore, fileBytes(migration.candidateDirectory))
        } }
    }

    @Test
    fun `truncated receipt and corrupt legacy evidence fail closed without changing authority`() {
        listOf("truncated-receipt", "truncated-body", "corrupt-legacy").forEach { fault ->
            withFixture("activation-$fault") { group, directory ->
                writeVersionFixture(directory, group, 5)
                val migration = migrated(group, directory)
                val siblingBefore = fileBytes(migration.candidateDirectory)
                val snapshot = legacyFiles(directory).getValue("snapshot")
                val payload = snapshot.readBytes().dropLast(32).toByteArray()
                val damaged = when (fault) {
                    "truncated-receipt" -> removeByte(payload, 56)
                    "truncated-body" -> removeByte(payload, payload.lastIndex - 1)
                    else -> payload.copyOf().also { it[20] = (it[20].toInt() xor 0x40).toByte() }
                }
                rewriteChecksummed(snapshot, damaged)
                val legacyBefore = snapshot.readBytes()
                val result = M3CanonicalActivation.prepare(group, directory, budget())
                assertEquals(M3CanonicalActivationRefusal.LEGACY_INVALID,
                    (result as M3CanonicalActivationPreparation.Refused).reason)
                assertArrayEquals(legacyBefore, snapshot.readBytes())
                assertEquals(siblingBefore, fileBytes(migration.candidateDirectory))
            }
        }
    }

    @Test
    fun `corrupt root resident directory and page evidence fail closed`() {
        listOf("root.v6", "resident.v6", "directory.v6", "sources.v6.pages").forEach { name ->
            withFixture("activation-sibling-${name.substringBefore('.')}") { group, directory ->
                val owner = opened(M3SurfaceOwnership.open(group, directory))
                accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
                owner.close()
                val migration = migrated(group, directory)
                val legacyBefore = legacyFiles(directory).mapValues { it.value.readBytes().toList() }
                val damaged = migration.candidateDirectory.resolve(name)
                val bytes = damaged.readBytes()
                damaged.writeBytes(bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x40).toByte() })
                val siblingBefore = fileBytes(migration.candidateDirectory)
                val result = M3CanonicalActivation.prepare(group, directory, budget())
                assertEquals(M3CanonicalActivationRefusal.SIBLING_INVALID,
                    (result as M3CanonicalActivationPreparation.Refused).reason)
                assertEquals(legacyBefore, legacyFiles(directory).mapValues { it.value.readBytes().toList() })
                assertEquals(siblingBefore, fileBytes(migration.candidateDirectory))
            }
        }
    }

    @Test
    fun `descriptor stays fixed and below one MiB while receipt history grows`() {
        withFixture("activation-bounded") { group, directory ->
            val receipts = List(24) { index -> ReceiptSpec(
                0x11 + index, 0x31 + index, "r$index",
                geometry = index.toLong(), lineage = index.toLong(),
            ) }
            val expected = writeVersionFixture(directory, group, 5, receipts = receipts)
            migrated(group, directory)
            val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            assertEquals(24L, plan.receipt.scannedReceipts)
            assertEquals(1L, plan.receipt.finalCutReceipts)
            assertEquals(512L, plan.receipt.retainedBytes)
            assertTrue(plan.receipt.retainedBytes <= 1_048_576L)
            assertCurrent(requireNotNull(expected), plan.current)
        }
    }

    @Test
    fun `preparation binds one exact final receipt as an immutable streaming source`() {
        val directory = Files.createTempDirectory("m3-activation-current").toFile()
        try {
            val group = M3SurfaceGroup("activation-current")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            val id = accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
            val committed = accepted(owner.transact(relocate("final", id, 1)))
            owner.close()
            migrated(group, directory)

            val prepared = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            assertEquals(group, prepared.siblingCut.group)
            assertEquals(committed.receipt.geometryRevision, prepared.siblingCut.geometryRevision)
            assertEquals(committed.receipt.lineageRevision, prepared.siblingCut.lineageRevision)
            assertEquals(committed.receipt.nextSurfaceIdHighWater, prepared.siblingCut.nextSurfaceIdHighWater)
            assertEquals(1, prepared.receipt.finalCutReceipts)
            assertTrue(prepared.receipt.retainedBytes <= 1_024L)
            val current = prepared.current as M3CanonicalActivationCurrent.Receipt
            val copied = ByteArrayOutputStream().also(current.source::writeTo).toByteArray()
            assertArrayEquals(committed.receipt.canonicalBytes.toByteArray(), copied)
            assertEquals(copied.size.toLong(), current.identity.canonicalLength)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preparation returns current none only when the final cut has no receipt`() {
        val directory = Files.createTempDirectory("m3-activation-none").toFile()
        try {
            val group = M3SurfaceGroup("activation-none")
            opened(M3SurfaceOwnership.open(group, directory)).close()
            migrated(group, directory)
            val prepared = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            assertEquals(M3CanonicalActivationCurrent.None, prepared.current)
            assertEquals(0L, prepared.receipt.finalCutReceipts)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt inactive sibling yields a typed refusal and leaves legacy authority readable`() {
        val directory = Files.createTempDirectory("m3-activation-corrupt").toFile()
        try {
            val group = M3SurfaceGroup("activation-corrupt")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
            owner.close()
            val migration = migrated(group, directory)
            assertTrue(File(migration.candidateDirectory, "root.v6").delete())

            val result = M3CanonicalActivation.prepare(group, directory, budget())
            assertEquals(
                M3CanonicalActivationRefusal.SIBLING_INVALID,
                (result as M3CanonicalActivationPreparation.Refused).reason,
            )
            assertTrue(M3SurfaceOwnershipLegacyCodec.readValidated(
                group, directory, M3SurfaceOwnershipConfiguration(),
            ).resident.rows == 1)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `individually valid legacy and sibling with different authority yield mismatch unchanged`() {
        val legacyDirectory = Files.createTempDirectory("m3-activation-mismatch-legacy").toFile()
        val siblingDirectory = Files.createTempDirectory("m3-activation-mismatch-sibling").toFile()
        try {
            val group = M3SurfaceGroup("activation-mismatch")
            opened(M3SurfaceOwnership.open(group, legacyDirectory)).close()
            val legacyMigration = migrated(group, legacyDirectory)

            val siblingOwner = opened(M3SurfaceOwnership.open(group, siblingDirectory))
            accepted(siblingOwner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
            siblingOwner.close()
            val differentMigration = migrated(group, siblingDirectory)

            legacyMigration.candidateDirectory.deleteRecursively()
            assertTrue(differentMigration.candidateDirectory.copyRecursively(
                legacyMigration.candidateDirectory,
            ))
            assertEquals(0, M3SurfaceOwnershipLegacyCodec.readValidated(
                group, legacyDirectory, M3SurfaceOwnershipConfiguration(),
            ).resident.rows)
            val validSibling = M3CompactCanonicalStore.openV6(
                group, legacyDirectory, budget(),
            ) as M3CompactCanonicalOpenResult.Opened
            assertEquals(1, validSibling.store.cut.liveSurfaceCount)
            validSibling.store.close()
            val legacyBefore = legacyFiles(legacyDirectory)
                .mapValues { it.value.readBytes().toList() }
            val siblingBefore = fileBytes(legacyMigration.candidateDirectory)

            val result = M3CanonicalActivation.prepare(group, legacyDirectory, budget())

            assertEquals(
                M3CanonicalActivationRefusal.SIBLING_MISMATCH,
                (result as M3CanonicalActivationPreparation.Refused).reason,
            )
            assertEquals(
                legacyBefore,
                legacyFiles(legacyDirectory).mapValues { it.value.readBytes().toList() },
            )
            assertEquals(siblingBefore, fileBytes(legacyMigration.candidateDirectory))
        } finally {
            legacyDirectory.deleteRecursively()
            siblingDirectory.deleteRecursively()
        }
    }

    private fun migrated(group: M3SurfaceGroup, directory: File):
        M3CompactCanonicalMigrationResult.Prepared {
        val result = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
        check(result is M3CompactCanonicalMigrationResult.Prepared) { "migration refused: $result" }
        return result
    }

    private fun budget() = object : M3CanonicalStorageBudget {
        override fun reserve(bytes: Long): Any = bytes
        override fun reserveCandidateExclusive(
            staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long,
        ) = M3CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }

    private fun candidate(x: Int) = M3SurfaceCandidate(
        voxel = M3Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192,
    )
    private fun relocate(command: String, id: M3SurfaceId, x: Int,
        geometry: Long = 0, lineage: Long = 0) = M3CanonicalTransactionCommand(
        command, M3CanonicalOperation.RELOCATION, geometry, lineage, listOf(id),
        listOf(M3CanonicalTarget(id, M3Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun opened(result: M3SurfaceOwnershipOpenResult) =
        (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
    private fun accepted(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Accepted
    private fun prepared(result: M3CanonicalActivationPreparation) =
        (result as M3CanonicalActivationPreparation.Prepared).plan

    private data class ReceiptSpec(val commandHash: Int, val fingerprint: Int,
        val command: String, val high: Long = 1, val live: Int = 0,
        val geometry: Long = 0, val lineage: Long = 0)

    private fun writeVersionFixture(directory: File, group: M3SurfaceGroup, version: Int,
        nextHigh: Long = 1, receipts: List<ReceiptSpec> = if (version == 1) emptyList()
            else listOf(ReceiptSpec(0x11, 0x21, "current"))): ByteArray? {
        require(version in 1..5)
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        if (nextHigh > 1) writeLedger(directory.resolve("m3-surface-$prefix.ledger"), group, nextHigh)
        val canonicals = receipts.map { emptyCanonicalReceipt(group, it) }
        writeSnapshot(directory.resolve("m3-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f); out.writeInt(version); out.writeLong(nextHigh)
            out.writeInt(0); out.writeInt(0)
            if (version >= 2) {
                out.writeLong(receipts.maxOfOrNull { it.geometry } ?: 0)
                out.writeLong(receipts.maxOfOrNull { it.lineage } ?: 0)
                out.writeInt(0)
                if (version >= 3) out.writeInt(0)
                out.writeInt(0); out.writeInt(receipts.size)
                receipts.forEachIndexed { index, receipt ->
                    out.write(ByteArray(32) { receipt.commandHash.toByte() })
                    out.write(ByteArray(32) { receipt.fingerprint.toByte() })
                    val canonical = canonicals[index]
                    if (version >= 4) { out.writeInt(canonical.size); out.write(canonical) }
                    else {
                        out.writeUTF(receipt.command); out.writeInt(M3CanonicalOperation.RELOCATION.ordinal)
                        out.writeInt(0); out.writeInt(0); out.writeInt(0)
                        out.writeLong(receipt.geometry); out.writeLong(receipt.lineage)
                        out.writeLong(receipt.high); out.writeInt(receipt.live)
                        if (version >= 3) { out.writeInt(0); out.writeInt(canonical.size); out.write(canonical) }
                    }
                }
                if (version >= 5) out.writeBoolean(false)
            }
        }
        return canonicals.lastOrNull()
    }

    private fun emptyCanonicalReceipt(group: M3SurfaceGroup, receipt: ReceiptSpec) =
        ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d334352); out.writeInt(1); out.writeUTF(group.value)
                out.writeUTF(receipt.command); out.writeInt(M3CanonicalOperation.RELOCATION.ordinal)
                out.writeLong(receipt.geometry); out.writeLong(receipt.lineage); out.writeLong(receipt.high)
                out.writeInt(receipt.live); repeat(4) { out.writeInt(0) }
            }; raw.toByteArray()
        }

    private fun writeLedger(file: File, group: M3SurfaceGroup, end: Long) {
        val body = ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d33524c); out.writeInt(1); out.writeLong(1); out.writeLong(1)
                out.writeLong(end); out.write(sha256(group.value.encodeToByteArray()))
                out.write(ByteArray(32) { 1 }); out.write(ByteArray(32) { 2 }); out.write(ByteArray(32))
            }; raw.toByteArray()
        }
        file.writeBytes(body + sha256(body))
    }

    private fun writeSnapshot(file: File, write: (DataOutputStream) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(file).use { output ->
            val digestOutput = DigestOutputStream(output, digest)
            val data = DataOutputStream(digestOutput)
            write(data); data.flush(); digestOutput.on(false); output.write(digest.digest())
        }
    }

    private fun rewriteChecksummed(file: File, payload: ByteArray) =
        file.writeBytes(payload + sha256(payload))

    private fun removeByte(bytes: ByteArray, offset: Int) =
        bytes.copyOfRange(0, offset) + bytes.copyOfRange(offset + 1, bytes.size)

    private fun assertCurrent(expected: ByteArray, current: M3CanonicalActivationCurrent) {
        val receipt = current as M3CanonicalActivationCurrent.Receipt
        val actual = ByteArrayOutputStream().also(receipt.source::writeTo).toByteArray()
        assertArrayEquals(expected, actual)
        assertEquals(expected.size.toLong(), receipt.identity.canonicalLength)
        assertEquals(M3CanonicalReceiptBytes(sha256(expected)), receipt.identity.canonicalHash)
    }

    private fun legacyFiles(directory: File): Map<String, File> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".snapshot") || it.name.endsWith(".ledger") }
        .associateBy { if (it.name.endsWith(".snapshot")) "snapshot" else "ledger" }
    private fun fileBytes(directory: File): Map<String, List<Byte>> = directory.listFiles().orEmpty()
        .filter(File::isFile).associate { it.name to it.readBytes().toList() }
    private fun <T> withFixture(name: String, block: (M3SurfaceGroup, File) -> T): T {
        val directory = Files.createTempDirectory(name).toFile()
        return try { block(M3SurfaceGroup(name), directory) } finally { directory.deleteRecursively() }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
