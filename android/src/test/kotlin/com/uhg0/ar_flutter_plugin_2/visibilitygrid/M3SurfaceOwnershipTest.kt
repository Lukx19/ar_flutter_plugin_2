package com.uhg0.ar_flutter_plugin_2.visibilitygrid

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
        val moved = accepted(owner.apply(M3SurfaceOwnershipCommand("move", listOf(candidate(M3Voxel(-31, 30, 0), initial.id.value, 1.0, 0.0, 0.0, 1.0))))).owners.single()
        assertEquals(initial.id, moved.id)
        assertNotEquals(initial.region, moved.region)
        assertEquals(M3StorageRegion(-2, 1, 0), moved.region)
        assertEquals(2, moved.page)
    }

    @Test
    fun `normal and confidence refine to canonical packed representation`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("normal")))
        val first = accepted(owner.apply(M3SurfaceOwnershipCommand("first", listOf(candidate(M3Voxel(0, 0, 0), normalX = 2.0, normalY = 0.0, normalZ = 0.0, confidence = 0.50001)))))
        val refined = accepted(owner.apply(M3SurfaceOwnershipCommand("refine", listOf(candidate(M3Voxel(0, 0, 0), first.owners.single().id.value, 1.0, 0.0, 0.0, 0.50001)))))
        assertEquals(first.owners.single().packedNormal, refined.owners.single().packedNormal)
        assertEquals(16384, refined.owners.single().confidenceQ15)
        val invalid = refused(owner.apply(M3SurfaceOwnershipCommand("bad", listOf(candidate(M3Voxel(1, 0, 0), normalX = Double.NaN)))))
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

    private fun candidate(voxel: M3Voxel, id: Long? = null, normalX: Double = 0.0, normalY: Double = 0.0, normalZ: Double = 1.0, confidence: Double = 0.75) = M3SurfaceCandidate(id, voxel, normalX, normalY, normalZ, confidence)
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
    private fun refused(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Refused
}
