package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Aligned 1/2/4-voxel queries share one 64-bit voxel mask per four-voxel parent. */
internal fun depthBlockParentKey(x: Int, y: Int, z: Int, size: Int): Long = packVisibilityGridKey(
    Math.floorDiv(x * size, 4), Math.floorDiv(y * size, 4), Math.floorDiv(z * size, 4),
)

internal fun depthBlockQueryMask(x: Int, y: Int, z: Int, size: Int): Long {
    val offset = (Math.floorMod(x * size, 4) shl 4) or
        (Math.floorMod(y * size, 4) shl 2) or Math.floorMod(z * size, 4)
    return when (size) {
        4 -> -1L
        2 -> 0x00330033L shl offset
        1 -> 1L shl offset
        else -> error("unsupported depth block size")
    }
}

/** Avalanche every coordinate before masking, including small negative-coordinate tables. */
internal fun depthBlockHash(key: Long): Int {
    var mixed = key xor (key ushr 33)
    mixed *= -49064778989728563L
    mixed = mixed xor (mixed ushr 33)
    mixed *= -4265267296055464877L
    return (mixed xor (mixed ushr 33)).toInt()
}
