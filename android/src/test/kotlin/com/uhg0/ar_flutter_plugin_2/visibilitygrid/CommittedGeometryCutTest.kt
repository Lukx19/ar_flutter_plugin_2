package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CoordinateFrameTransforms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommittedGeometryCutTest {
    @Test
    fun `cut defensively copies values and compares removals by content`() {
        val rows = mutableListOf(row(1, 2))
        val removals = longArrayOf(7, 8)
        val first = cut(upserts = rows, removals = removals)
        val equal = cut(upserts = rows.toList(), removals = removals.copyOf())

        rows.clear()
        removals[0] = 99

        assertEquals(listOf(row(1, 2)), first.upserts)
        assertEquals(listOf(7L, 8L), first.removedSurfaceIds.toList())
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(first.ownership.groupFrame, ownership().groupFrame)
        assertNotEquals(first.ownership.groupFrame, VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(), identityVisibilityGridTransform(), 2_000, 10,
        ))
    }

    @Test
    fun `cut rejects ambiguous identities and invalid revision transitions`() {
        assertThrows(IllegalArgumentException::class.java) {
            cut(upserts = listOf(row(1, 1), row(1, 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(removals = longArrayOf(1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(removals = longArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(upserts = listOf(row(1, 1)), removals = longArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(base = 4, target = 6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(base = Long.MAX_VALUE, target = Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(base = 4, target = 4, reset = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(base = 4, target = 6, reset = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(base = 0, target = 0, reset = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cut(base = 0, target = Long.MAX_VALUE, reset = true)
        }
        assertEquals(1, cut(base = 0, target = 1, reset = true).geometryRevision)
    }

    @Test
    fun `group frame rejects zero capacity and asymmetric approximate inverses`() {
        val identity = identityVisibilityGridTransform()
        assertThrows(IllegalArgumentException::class.java) {
            VisibilityGroupFrame.copyOf(identity, identity, 1_000, 0)
        }
        val groupFromWorld = identity.copyOf().also { it[0] = 1e8 }
        val worldFromGroup = identity.copyOf().also {
            it[0] = 1e-8
            it[1] = 1e-7
        }
        assertFalse(CoordinateFrameTransforms.areFiniteAffineInverses(groupFromWorld, worldFromGroup))
        assertThrows(IllegalArgumentException::class.java) {
            VisibilityGroupFrame.copyOf(groupFromWorld, worldFromGroup, 1_000, 10)
        }
    }

    private fun cut(
        base: Long = 4,
        target: Long = 5,
        reset: Boolean = false,
        upserts: List<CommittedGeometryRow> = emptyList(),
        removals: LongArray = LongArray(0),
    ) = CommittedGeometryCut(
        ownership(), 9, base, target, 3, reset, upserts, removals,
    )

    private fun row(id: Long, x: Int) = CommittedGeometryRow(id, Voxel(x, 0, 0), 12, 34, 5)

    private fun ownership() = VisibilityObservationOwnership(
        sessionId = "01".repeat(16), sessionGeneration = 1,
        captureGroupId = "02".repeat(16), groupGeneration = 1, coverageEpoch = 1,
        arSessionIdentity = "03".repeat(16), viewInstanceId = "04".repeat(16), viewGeneration = 1,
        nativeStreamToken = "05".repeat(16), workerBindingToken = "06".repeat(16),
        bindingGeneration = 1, lifecycleSequence = 1, operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(), identityVisibilityGridTransform(), 1_000, 10,
        ),
    )
}
