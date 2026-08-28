package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3SurfaceOwnershipTest {
    @Test
    fun `signed voxel boundaries derive stable region and x-fastest page ownership`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("boundaries")))
        val rows = accepted(owner.apply(M3SurfaceOwnershipCommand("boundaries", listOf(
            candidate(M3Voxel(-1, -1, -1)), candidate(M3Voxel(-30, -30, -30)), candidate(M3Voxel(29, 29, 29)), candidate(M3Voxel(30, 30, 30)),
        )))).owners
        assertEquals(M3StorageRegion(-1, -1, -1), rows[0].region)
        assertEquals(26, rows[0].page)
        assertEquals(M3StorageRegion(-1, -1, -1), rows[1].region)
        assertEquals(0, rows[1].page)
        assertEquals(M3StorageRegion(0, 0, 0), rows[2].region)
        assertEquals(26, rows[2].page)
        assertEquals(M3StorageRegion(1, 1, 1), rows[3].region)
        assertEquals(0, rows[3].page)
    }

    @Test
    fun `relocation changes signed ownership but preserves qualified identity`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("move")))
        val initial = accepted(owner.apply(M3SurfaceOwnershipCommand("create", listOf(candidate(M3Voxel(0, 0, 0)))))).owners.single()
        val moved = accepted(owner.apply(M3SurfaceOwnershipCommand("move", listOf(candidate(M3Voxel(-31, 30, 0), initial.id.value, 127, 0, 255))))).owners.single()
        assertEquals(initial.id, moved.id)
        assertNotEquals(initial.region, moved.region)
        assertEquals(M3StorageRegion(-2, 1, 0), moved.region)
        assertEquals(2, moved.page)
    }

    @Test
    fun `normal oct bytes and reliability bands preserve exact boundaries`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("normal")))
        val first = accepted(owner.apply(M3SurfaceOwnershipCommand("first", listOf(candidate(M3Voxel(0, 0, 0), normalOctX = 127, normalOctY = -127, confidence = 64)))))
        val refined = accepted(owner.apply(M3SurfaceOwnershipCommand("refine", listOf(candidate(M3Voxel(0, 0, 0), first.owners.single().id.value, 127, -127, 192)))))
        assertEquals(first.owners.single().packedNormal, refined.owners.single().packedNormal)
        assertEquals(0x7f81, refined.owners.single().packedNormal)
        assertEquals(192, refined.owners.single().normalConfidence)
        assertEquals(M3NormalReliabilityBand.STRONG, refined.owners.single().reliabilityBand)

        val bands = accepted(owner.apply(M3SurfaceOwnershipCommand("bands", listOf(
            candidate(M3Voxel(1, 0, 0), normalOctX = 0, normalOctY = 0, confidence = 0),
            candidate(M3Voxel(2, 0, 0), confidence = 1), candidate(M3Voxel(3, 0, 0), confidence = 63),
            candidate(M3Voxel(4, 0, 0), confidence = 64), candidate(M3Voxel(5, 0, 0), confidence = 191),
            candidate(M3Voxel(6, 0, 0), confidence = 192), candidate(M3Voxel(7, 0, 0), confidence = 255),
        )))).owners
        assertEquals(listOf(
            M3NormalReliabilityBand.UNKNOWN, M3NormalReliabilityBand.WEAK, M3NormalReliabilityBand.WEAK,
            M3NormalReliabilityBand.RELIABLE, M3NormalReliabilityBand.RELIABLE,
            M3NormalReliabilityBand.STRONG, M3NormalReliabilityBand.STRONG,
        ), bands.map { it.reliabilityBand })
        assertEquals(0, bands.first().packedNormal)

        val invalid = refused(owner.apply(M3SurfaceOwnershipCommand("bad", listOf(candidate(M3Voxel(8, 0, 0), normalOctX = -128)))))
        assertEquals(M3SurfaceOwnershipRefusal.INVALID_NORMAL, invalid.reason)
    }

    @Test
    fun `capacity ownership conflict and invalid configuration refuse atomically`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("atomic"), M3SurfaceOwnershipConfiguration(surfaceCapacity = 1)))
        val first = accepted(owner.apply(M3SurfaceOwnershipCommand("first", listOf(candidate(M3Voxel(0, 0, 0))))))
        val capacity = refused(owner.apply(M3SurfaceOwnershipCommand("second", listOf(candidate(M3Voxel(1, 0, 0))))))
        assertEquals(M3SurfaceOwnershipRefusal.CAPACITY, capacity.reason)
        assertEquals(first.receipt.nextSurfaceIdHighWater, capacity.receipt.nextSurfaceIdHighWater)
        val collision = refused(owner.apply(M3SurfaceOwnershipCommand("collision", listOf(candidate(M3Voxel(0, 0, 0))))))
        assertEquals(M3SurfaceOwnershipRefusal.OWNERSHIP_CONFLICT, collision.reason)
        val bad = M3SurfaceOwnership.inMemory(M3SurfaceGroup("bad"), M3SurfaceOwnershipConfiguration(regionMicrometers = 2_000_000)) as M3SurfaceOwnershipOpenResult.Refused
        assertEquals(M3SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION, bad.reason)
    }

    @Test
    fun `group scoped IDs are independent and close is deterministic`() {
        val first = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("one")))
        val second = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("two")))
        val firstRow = accepted(first.apply(M3SurfaceOwnershipCommand("same", listOf(candidate(M3Voxel(0, 0, 0)))))).owners.single()
        val secondRow = accepted(second.apply(M3SurfaceOwnershipCommand("same", listOf(candidate(M3Voxel(0, 0, 0)))))).owners.single()
        assertEquals(1, firstRow.id.value); assertEquals(1, secondRow.id.value); assertNotEquals(firstRow.group, secondRow.group)
        assertEquals(M3SurfaceOwnershipCloseResult.Closed, first.close())
        assertEquals(M3SurfaceOwnershipCloseResult.AlreadyClosed, first.close())
        val afterClose = refused(first.apply(M3SurfaceOwnershipCommand("later", listOf(candidate(M3Voxel(1, 0, 0))))))
        assertEquals(M3SurfaceOwnershipRefusal.CLOSED, afterClose.reason)
        assertTrue(afterClose.receipt.liveSurfaceCount == 1)
    }

    @Test
    fun `concurrent apply is unique and close is a terminal serialized cut`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("concurrent")))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<M3SurfaceOwnershipResult>())
            val tasks = (0 until 32).map { index ->
                executor.submit {
                    start.await()
                    results += owner.apply(M3SurfaceOwnershipCommand("concurrent-$index", listOf(candidate(M3Voxel(index, 0, 0)))))
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            val acceptedIds = results.map { accepted(it).owners.single().id.value }
            assertEquals(32, acceptedIds.toSet().size)
            assertEquals((1L..32L).toList(), acceptedIds.sorted())

            val race = CountDownLatch(1)
            val applyFuture = executor.submit<M3SurfaceOwnershipResult> {
                race.await()
                owner.apply(M3SurfaceOwnershipCommand("close-race", listOf(candidate(M3Voxel(100, 0, 0)))))
            }
            val closeFuture = executor.submit<M3SurfaceOwnershipCloseResult> {
                race.await()
                owner.close()
            }
            race.countDown()
            val racedApply = applyFuture.get(5, TimeUnit.SECONDS)
            assertTrue(racedApply is M3SurfaceOwnershipResult.Accepted || (racedApply as M3SurfaceOwnershipResult.Refused).reason == M3SurfaceOwnershipRefusal.CLOSED)
            assertEquals(M3SurfaceOwnershipCloseResult.Closed, closeFuture.get(5, TimeUnit.SECONDS))
            repeat(8) { index ->
                assertEquals(M3SurfaceOwnershipRefusal.CLOSED, refused(owner.apply(M3SurfaceOwnershipCommand("after-close-$index", listOf(candidate(M3Voxel(200 + index, 0, 0)))))).reason)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun candidate(voxel: M3Voxel, id: Long? = null, normalOctX: Int = 0, normalOctY: Int = 0, confidence: Int = 192) = M3SurfaceCandidate(id, voxel, normalOctX, normalOctY, confidence)
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
    private fun refused(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Refused
}
