package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceOwnershipTest {
    @Test
    fun `signed voxel boundaries derive stable region and x-fastest page ownership`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("boundaries")))
        val rows = accepted(owner.apply(SurfaceOwnershipCommand("boundaries", listOf(
            candidate(Voxel(-1, -1, -1)), candidate(Voxel(-30, -30, -30)), candidate(Voxel(29, 29, 29)), candidate(Voxel(30, 30, 30)),
        )))).owners
        assertEquals(StorageRegion(-1, -1, -1), rows[0].region)
        assertEquals(26, rows[0].page)
        assertEquals(StorageRegion(-1, -1, -1), rows[1].region)
        assertEquals(0, rows[1].page)
        assertEquals(StorageRegion(0, 0, 0), rows[2].region)
        assertEquals(26, rows[2].page)
        assertEquals(StorageRegion(1, 1, 1), rows[3].region)
        assertEquals(0, rows[3].page)
    }

    @Test
    fun `relocation changes signed ownership but preserves qualified identity`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("move")))
        val initial = accepted(owner.apply(SurfaceOwnershipCommand("create", listOf(candidate(Voxel(0, 0, 0)))))).owners.single()
        val moved = accepted(owner.apply(SurfaceOwnershipCommand("move", listOf(candidate(Voxel(-31, 30, 0), initial.id.value, 127, 0, 255))))).owners.single()
        assertEquals(initial.id, moved.id)
        assertNotEquals(initial.region, moved.region)
        assertEquals(StorageRegion(-2, 1, 0), moved.region)
        assertEquals(2, moved.page)
    }

    @Test
    fun `normal oct bytes and reliability bands preserve exact boundaries`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("normal")))
        val first = accepted(owner.apply(SurfaceOwnershipCommand("first", listOf(candidate(Voxel(0, 0, 0), normalOctX = 127, normalOctY = -127, confidence = 64)))))
        val refined = accepted(owner.apply(SurfaceOwnershipCommand("refine", listOf(candidate(Voxel(0, 0, 0), first.owners.single().id.value, 127, -127, 192)))))
        assertEquals(first.owners.single().packedNormal, refined.owners.single().packedNormal)
        assertEquals(0x7f81, refined.owners.single().packedNormal)
        assertEquals(192, refined.owners.single().normalConfidence)
        assertEquals(NormalReliabilityBand.STRONG, refined.owners.single().reliabilityBand)

        val bands = accepted(owner.apply(SurfaceOwnershipCommand("bands", listOf(
            candidate(Voxel(1, 0, 0), normalOctX = 0, normalOctY = 0, confidence = 0),
            candidate(Voxel(2, 0, 0), confidence = 1), candidate(Voxel(3, 0, 0), confidence = 63),
            candidate(Voxel(4, 0, 0), confidence = 64), candidate(Voxel(5, 0, 0), confidence = 191),
            candidate(Voxel(6, 0, 0), confidence = 192), candidate(Voxel(7, 0, 0), confidence = 255),
        )))).owners
        assertEquals(listOf(
            NormalReliabilityBand.UNKNOWN, NormalReliabilityBand.WEAK, NormalReliabilityBand.WEAK,
            NormalReliabilityBand.RELIABLE, NormalReliabilityBand.RELIABLE,
            NormalReliabilityBand.STRONG, NormalReliabilityBand.STRONG,
        ), bands.map { it.reliabilityBand })
        assertEquals(0, bands.first().packedNormal)

        val invalid = refused(owner.apply(SurfaceOwnershipCommand("bad", listOf(candidate(Voxel(8, 0, 0), normalOctX = -128)))))
        assertEquals(SurfaceOwnershipRefusal.INVALID_NORMAL, invalid.reason)
    }

    @Test
    fun `capacity ownership conflict and invalid configuration refuse atomically`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("atomic"), SurfaceOwnershipConfiguration(surfaceCapacity = 1)))
        val first = accepted(owner.apply(SurfaceOwnershipCommand("first", listOf(candidate(Voxel(0, 0, 0))))))
        val capacity = refused(owner.apply(SurfaceOwnershipCommand("second", listOf(candidate(Voxel(1, 0, 0))))))
        assertEquals(SurfaceOwnershipRefusal.CAPACITY, capacity.reason)
        assertEquals(first.receipt.nextSurfaceIdHighWater, capacity.receipt.nextSurfaceIdHighWater)
        val collision = refused(owner.apply(SurfaceOwnershipCommand("collision", listOf(candidate(Voxel(0, 0, 0))))))
        assertEquals(SurfaceOwnershipRefusal.OWNERSHIP_CONFLICT, collision.reason)
        val bad = SurfaceOwnership.inMemory(SurfaceGroup("bad"), SurfaceOwnershipConfiguration(regionMicrometers = 2_000_000)) as SurfaceOwnershipOpenResult.Refused
        assertEquals(SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION, bad.reason)
    }

    @Test
    fun `group scoped IDs are independent and close is deterministic`() {
        val first = opened(SurfaceOwnership.inMemory(SurfaceGroup("one")))
        val second = opened(SurfaceOwnership.inMemory(SurfaceGroup("two")))
        val firstRow = accepted(first.apply(SurfaceOwnershipCommand("same", listOf(candidate(Voxel(0, 0, 0)))))).owners.single()
        val secondRow = accepted(second.apply(SurfaceOwnershipCommand("same", listOf(candidate(Voxel(0, 0, 0)))))).owners.single()
        assertEquals(1, firstRow.id.value); assertEquals(1, secondRow.id.value); assertNotEquals(firstRow.group, secondRow.group)
        assertEquals(SurfaceOwnershipCloseResult.Closed, first.close())
        assertEquals(SurfaceOwnershipCloseResult.AlreadyClosed, first.close())
        val afterClose = refused(first.apply(SurfaceOwnershipCommand("later", listOf(candidate(Voxel(1, 0, 0))))))
        assertEquals(SurfaceOwnershipRefusal.CLOSED, afterClose.reason)
        assertTrue(afterClose.receipt.liveSurfaceCount == 1)
    }

    @Test
    fun `concurrent apply is unique and close is a terminal serialized cut`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("concurrent")))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<SurfaceOwnershipResult>())
            val tasks = (0 until 32).map { index ->
                executor.submit {
                    start.await()
                    results += owner.apply(SurfaceOwnershipCommand("concurrent-$index", listOf(candidate(Voxel(index, 0, 0)))))
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            val acceptedIds = results.map { accepted(it).owners.single().id.value }
            assertEquals(32, acceptedIds.toSet().size)
            assertEquals((1L..32L).toList(), acceptedIds.sorted())

            val race = CountDownLatch(1)
            val applyFuture = executor.submit<SurfaceOwnershipResult> {
                race.await()
                owner.apply(SurfaceOwnershipCommand("close-race", listOf(candidate(Voxel(100, 0, 0)))))
            }
            val closeFuture = executor.submit<SurfaceOwnershipCloseResult> {
                race.await()
                owner.close()
            }
            race.countDown()
            val racedApply = applyFuture.get(5, TimeUnit.SECONDS)
            assertTrue(racedApply is SurfaceOwnershipResult.Accepted || (racedApply as SurfaceOwnershipResult.Refused).reason == SurfaceOwnershipRefusal.CLOSED)
            assertEquals(SurfaceOwnershipCloseResult.Closed, closeFuture.get(5, TimeUnit.SECONDS))
            repeat(8) { index ->
                assertEquals(SurfaceOwnershipRefusal.CLOSED, refused(owner.apply(SurfaceOwnershipCommand("after-close-$index", listOf(candidate(Voxel(200 + index, 0, 0)))))).reason)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun candidate(voxel: Voxel, id: Long? = null, normalOctX: Int = 0, normalOctY: Int = 0, confidence: Int = 192) = SurfaceCandidate(id, voxel, normalOctX, normalOctY, confidence)
    private fun opened(result: SurfaceOwnershipOpenResult) = (result as SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: SurfaceOwnershipResult) = result as SurfaceOwnershipResult.Accepted
    private fun refused(result: SurfaceOwnershipResult) = result as SurfaceOwnershipResult.Refused
}
