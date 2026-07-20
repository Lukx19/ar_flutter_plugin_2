package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Reusable native upload buffers for the fixed-capacity coverage mesh.
 *
 * The AR frame callback is on the SceneView/UI path. Allocating direct buffers
 * for every point-cloud revision adds native pressure and can block rendering
 * while the buffers are collected. The capacity is fixed for the lifetime of
 * the renderer; only the active limits change per snapshot.
 */
internal class CoveragePointUploadBuffers(capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val positionStorage = ByteBuffer
        .allocateDirect(capacity * POSITION_COMPONENTS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val colorStorage = ByteBuffer
        .allocateDirect(capacity * COLOR_COMPONENTS)
        .order(ByteOrder.nativeOrder())

    val positionBuffer: FloatBuffer
        get() = positionStorage

    val colorBuffer: ByteBuffer
        get() = colorStorage

    fun write(positions: FloatArray, colors: IntArray) {
        require(positions.size % POSITION_COMPONENTS == 0)
        require(colors.size * POSITION_COMPONENTS == positions.size)
        require(colors.size <= colorStorage.capacity() / COLOR_COMPONENTS)

        positionStorage.clear()
        positionStorage.put(positions)
        positionStorage.flip()

        colorStorage.clear()
        colors.forEach { color ->
            colorStorage.put((color shr 16 and 0xFF).toByte())
            colorStorage.put((color shr 8 and 0xFF).toByte())
            colorStorage.put((color and 0xFF).toByte())
            colorStorage.put((color ushr 24 and 0xFF).toByte())
        }
        colorStorage.flip()
    }

    fun writeRange(
        positions: FloatArray,
        colors: IntArray,
        startSlot: Int,
        endSlotExclusive: Int,
    ) {
        require(startSlot >= 0 && endSlotExclusive >= startSlot)
        require(endSlotExclusive <= colors.size)
        val first = startSlot * POSITION_COMPONENTS
        val last = endSlotExclusive * POSITION_COMPONENTS
        require(last <= positions.size)
        val positionTarget = positionStorage.duplicate()
        positionTarget.clear()
        positionTarget.position(first)
        positionTarget.put(positions, first, last - first)
        // The exposed storage buffer retains the previous upload's range.
        // Reset it before selecting a later disjoint span; otherwise a
        // larger start slot can exceed the stale limit and crash the UI.
        positionStorage.clear()
        positionStorage.position(first)
        positionStorage.limit(last)

        val colorFirst = startSlot * COLOR_COMPONENTS
        val colorLast = endSlotExclusive * COLOR_COMPONENTS
        val colorTarget = colorStorage.duplicate()
        colorTarget.clear()
        colorTarget.position(colorFirst)
        for (slot in startSlot until endSlotExclusive) {
            val color = colors[slot]
            colorTarget.put((color shr 16 and 0xFF).toByte())
            colorTarget.put((color shr 8 and 0xFF).toByte())
            colorTarget.put((color and 0xFF).toByte())
            colorTarget.put((color ushr 24 and 0xFF).toByte())
        }
        colorStorage.clear()
        colorStorage.position(colorFirst)
        colorStorage.limit(colorLast)
    }

    private companion object {
        const val POSITION_COMPONENTS = 3
        const val COLOR_COMPONENTS = 4
    }
}
