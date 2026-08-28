package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CompactCanonicalStoreFaultTest {
    @Test
    fun `quota refusal writes nothing and leaves legacy readable`() {
        val directory = Files.createTempDirectory("m3-quota-refusal").toFile()
        try {
            val group = M3SurfaceGroup("quota-refusal")
            val owner =
                (M3SurfaceOwnership.open(group, directory) as M3SurfaceOwnershipOpenResult.Opened)
                    .ownership
            owner.apply(
                M3SurfaceOwnershipCommand(
                    "seed",
                    listOf(
                        M3SurfaceCandidate(
                            voxel = M3Voxel(0, 0, 0),
                            normalOctX = 0,
                            normalOctY = 0,
                            normalConfidence = 192,
                        )
                    ),
                )
            )
            owner.close()
            val result =
                M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    object : M3CanonicalStorageBudget {
                        override fun reserve(bytes: Long): Any? = null

                        override fun commit(token: Any, actualBytes: Long) =
                            error("must not commit")

                        override fun release(token: Any) = error("must not release")
                    },
                )
            assertEquals(
                M3CompactCanonicalRefusal.QUOTA_REFUSED,
                (result as M3CompactCanonicalMigrationResult.Refused).reason,
            )
            assertTrue(
                directory.listFiles().orEmpty().none { it.name.startsWith("m3-canonical-v6-") }
            )
            assertTrue(
                M3SurfaceOwnership.open(group, directory) is M3SurfaceOwnershipOpenResult.Opened
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `wrong root cursor and close fail without sink publication`() {
        val directory = Files.createTempDirectory("m3-cursor-close").toFile()
        try {
            val group = M3SurfaceGroup("cursor-close")
            val owner =
                (M3SurfaceOwnership.open(group, directory) as M3SurfaceOwnershipOpenResult.Opened)
                    .ownership
            val accepted =
                owner.apply(
                    M3SurfaceOwnershipCommand(
                        "seed",
                        listOf(
                            M3SurfaceCandidate(
                                voxel = M3Voxel(0, 0, 0),
                                normalOctX = 0,
                                normalOctY = 0,
                                normalConfidence = 192,
                            )
                        ),
                    )
                ) as M3SurfaceOwnershipResult.Accepted
            owner.close()
            val result =
                M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as M3CompactCanonicalMigrationResult.Prepared
            val store =
                (M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                        as M3CompactCanonicalOpenResult.Opened)
                    .store
            var calls = 0
            val stale =
                store.visitSourceSupport(
                    accepted.owners.single().id,
                    M3SourceSupportCursor(
                        M3CanonicalReceiptBytes(ByteArray(32)),
                        accepted.owners.single().id,
                        0,
                        0,
                    ),
                ) {
                    calls++
                    true
                }
            assertEquals(
                M3CompactCanonicalRefusal.STALE_CURSOR,
                (stale as M3SourceSupportRead.Refused).reason,
            )
            assertEquals(0, calls)
            store.close()
            val closed =
                store.visitSourceSupport(accepted.owners.single().id, null) {
                    calls++
                    true
                }
            assertEquals(
                M3CompactCanonicalRefusal.CLOSED,
                (closed as M3SourceSupportRead.Refused).reason,
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
            val directory = Files.createTempDirectory("m3-corrupt-$damaged").toFile()
            try {
                val group = M3SurfaceGroup("corrupt-$damaged")
                val owner =
                    (M3SurfaceOwnership.open(group, directory)
                            as M3SurfaceOwnershipOpenResult.Opened)
                        .ownership
                val accepted =
                    owner.apply(
                        M3SurfaceOwnershipCommand(
                            "seed",
                            listOf(
                                M3SurfaceCandidate(
                                    voxel = M3Voxel(0, 0, 0),
                                    normalOctX = 0,
                                    normalOctY = 0,
                                    normalConfidence = 192,
                                )
                            ),
                        )
                    ) as M3SurfaceOwnershipResult.Accepted
                owner.close()
                val prepared =
                    M3CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as M3CompactCanonicalMigrationResult.Prepared
                if (damaged == "sources.v6.pages") {
                    val store =
                        (M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                                as M3CompactCanonicalOpenResult.Opened)
                            .store
                    flip(prepared.candidateDirectory.resolve(damaged))
                    val read = store.readSourceById(accepted.owners.single().id)
                    assertEquals(
                        M3CompactCanonicalRefusal.CORRUPT,
                        (read as M3CanonicalPageRead.Refused).reason,
                    )
                } else {
                    flip(prepared.candidateDirectory.resolve(damaged))
                    assertTrue(
                        M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                            is M3CompactCanonicalOpenResult.Refused
                    )
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun acceptingBudget() =
        object : M3CanonicalStorageBudget {
            override fun reserve(bytes: Long): Any = bytes

            override fun commit(token: Any, actualBytes: Long) = Unit

            override fun release(token: Any) = Unit
        }

    private fun flip(file: File) {
        val bytes = file.readBytes()
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x55).toByte()
        file.writeBytes(bytes)
    }
}
