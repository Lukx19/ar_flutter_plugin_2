package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCanonicalStoreFaultTest {
    @Test
    fun `quota refusal writes nothing and leaves legacy readable`() {
        val directory = Files.createTempDirectory("canonical-surface-quota-refusal").toFile()
        try {
            val group = SurfaceGroup("quota-refusal")
            val owner =
                (SurfaceOwnership.open(group, directory) as SurfaceOwnershipOpenResult.Opened)
                    .ownership
            owner.apply(
                SurfaceOwnershipCommand(
                    "seed",
                    listOf(
                        SurfaceCandidate(
                            voxel = Voxel(0, 0, 0),
                            normalOctX = 0,
                            normalOctY = 0,
                            normalConfidence = 192,
                        )
                    ),
                )
            )
            owner.close()
            val result =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    object : CanonicalStorageBudget {
                        override fun reserve(bytes: Long): Any? = null
                        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
                            CanonicalCandidateReservation.QuotaRefused

                        override fun commit(token: Any, actualBytes: Long) =
                            error("must not commit")

                        override fun release(token: Any) = error("must not release")

                        override fun allocationUnitBytes(path: File) = 4_096L
                    },
                )
            assertEquals(
                CompactCanonicalRefusal.QUOTA_REFUSED,
                (result as CompactCanonicalMigrationResult.Refused).reason,
            )
            assertTrue(
                directory.listFiles().orEmpty().none { it.name.startsWith("canonical-surface-canonical-v6-") }
            )
            assertTrue(
                SurfaceOwnership.open(group, directory) is SurfaceOwnershipOpenResult.Opened
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `wrong root cursor and close fail without sink publication`() {
        val directory = Files.createTempDirectory("canonical-surface-cursor-close").toFile()
        try {
            val group = SurfaceGroup("cursor-close")
            val owner =
                (SurfaceOwnership.open(group, directory) as SurfaceOwnershipOpenResult.Opened)
                    .ownership
            val accepted =
                owner.apply(
                    SurfaceOwnershipCommand(
                        "seed",
                        listOf(
                            SurfaceCandidate(
                                voxel = Voxel(0, 0, 0),
                                normalOctX = 0,
                                normalOctY = 0,
                                normalConfidence = 192,
                            )
                        ),
                    )
                ) as SurfaceOwnershipResult.Accepted
            owner.close()
            val result =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as CompactCanonicalMigrationResult.Prepared
            val store =
                (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                        as CompactCanonicalOpenResult.Opened)
                    .store
            var calls = 0
            val stale =
                store.visitSourceSupport(
                    accepted.owners.single().id,
                    SourceSupportCursor(
                        CanonicalReceiptBytes(ByteArray(32)),
                        accepted.owners.single().id,
                        0,
                        0,
                    ),
                ) {
                    calls++
                    true
                }
            assertEquals(
                CompactCanonicalRefusal.STALE_CURSOR,
                (stale as SourceSupportRead.Refused).reason,
            )
            assertEquals(0, calls)
            store.close()
            val closed =
                store.visitSourceSupport(accepted.owners.single().id, null) {
                    calls++
                    true
                }
            assertEquals(
                CompactCanonicalRefusal.CLOSED,
                (closed as SourceSupportRead.Refused).reason,
            )
            assertEquals(0, calls)
            assertTrue(result.storage.allocatedBytes > 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `open rejects every corrupted v6 authority file and reads revalidate pages`() {
        listOf("root.v6", "resident.v6", "directory.v6", "sources.v6.pages").forEach { damaged ->
            val directory = Files.createTempDirectory("canonical-surface-corrupt-$damaged").toFile()
            try {
                val group = SurfaceGroup("corrupt-$damaged")
                val owner =
                    (SurfaceOwnership.open(group, directory)
                            as SurfaceOwnershipOpenResult.Opened)
                        .ownership
                val accepted =
                    owner.apply(
                        SurfaceOwnershipCommand(
                            "seed",
                            listOf(
                                SurfaceCandidate(
                                    voxel = Voxel(0, 0, 0),
                                    normalOctX = 0,
                                    normalOctY = 0,
                                    normalConfidence = 192,
                                )
                            ),
                        )
                    ) as SurfaceOwnershipResult.Accepted
                owner.close()
                val prepared =
                    CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as CompactCanonicalMigrationResult.Prepared
                if (damaged == "sources.v6.pages") {
                    val store =
                        (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                                as CompactCanonicalOpenResult.Opened)
                            .store
                    flip(prepared.candidateDirectory.resolve(damaged))
                    val read = store.readSourceById(accepted.owners.single().id)
                    assertEquals(
                        CompactCanonicalRefusal.CORRUPT,
                        (read as CanonicalPageRead.Refused).reason,
                    )
                } else {
                    flip(prepared.candidateDirectory.resolve(damaged))
                    assertTrue(
                        CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                            is CompactCanonicalOpenResult.Refused
                    )
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun acceptingBudget() =
        object : CanonicalStorageBudget {
            override fun reserve(bytes: Long): Any = bytes
            override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
                CanonicalCandidateReservation.QuotaRefused

            override fun commit(token: Any, actualBytes: Long) = Unit

            override fun release(token: Any) = Unit

            override fun allocationUnitBytes(path: File) = 4_096L
        }

    private fun flip(file: File) {
        val bytes = file.readBytes()
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x55).toByte()
        file.writeBytes(bytes)
    }
}
