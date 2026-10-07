package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Prepared on the binding executor. Renderer frames only copy bounded upload pages. */
internal class PreparedCoverageShapes(
    val header: RendererAttributeCodec.Header,
    val geometry: CoverageGeometryBuffers,
) {
    init {
        require(geometry.shapeCount + geometry.pointCount == header.total)
        require(geometry.vertexCount <= header.total * if (header.shape == RendererAttributeCodec.BALLS) 126 else CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH)
        require(geometry.indexCount <= header.total * if (header.shape == RendererAttributeCodec.BALLS) 456 else CoveragePlanarPatchGeometry.MAX_INDICES_PER_PATCH)
    }
    val portableBytes: Long get() = geometry.positions.size * 4L + geometry.colors.size * 4L +
        geometry.indices.size * 4L + geometry.pointPositions.size * 4L + geometry.pointColors.size * 4L + 64

    companion object {
        fun build(descriptor: PresentationDescriptor, table: RendererAttributeTable): PreparedCoverageShapes? {
            val matrix = descriptor.worldFromGroup ?: return null
            val size = descriptor.voxelSizeMeters
            if (!size.isFinite() || size <= 0) return null
            val balls = table.header.shape == RendererAttributeCodec.BALLS
            val count = table.header.total
            if (count > minOf(descriptor.count, if (balls) 256 else 4096) || count != table.rows.size / 24) return null
            val ids = LongArray(count)
            val centers = FloatArray(count * 3)
            val sizes = FloatArray(count) { size }
            val normals = IntArray(count)
            val confidence = IntArray(count)
            val colors = IntArray(count)
            val packed = if (balls) LongArray(count) else LongArray(0)
            val data = ByteBuffer.wrap(table.rows).order(ByteOrder.LITTLE_ENDIAN)
            val page = CoveragePresentationPage.allocate(minOf(512, count))
            val inverse = inverseAffine(matrix) ?: return null
            var first = 0
            while (first < count) {
                val n = minOf(512, count - first)
                if (!descriptor.withPageInto(descriptor.qualifier, first, n, page)) return null
                repeat(n) { index ->
                    val destination = first + index
                    val id = page.surfaceIds[index]
                    val offset = table.rowOffset(id)
                    if (offset < 0) return null
                    ids[destination] = id
                    for (axis in 0..2) {
                        centers[destination * 3 + axis] = (inverse[12 + axis] +
                            inverse[axis] * page.positions[index * 3] +
                            inverse[4 + axis] * page.positions[index * 3 + 1] +
                            inverse[8 + axis] * page.positions[index * 3 + 2]).toFloat()
                    }
                    normals[destination] = data.getShort(offset + 8).toInt() and 65535
                    confidence[destination] = table.rows[offset + 10].toInt() and 255
                    colors[destination] = page.colors[index]
                    if (balls) for (lane in 0..5) packed[destination] = packed[destination] or
                        ((table.rows[offset + 12 + lane].toLong() and 255L) shl (lane * 8))
                }
                first += n
            }
            val input = CoverageCellGeometryInput.copyOf(ids, centers, sizes, normals, confidence, colors, matrix)
            val output = CoverageGeometryBuffers(FloatArray(count * (if (balls) 126 else CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH) * 3),
                IntArray(count * if (balls) 126 else CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH), IntArray(count * if (balls) 456 else CoveragePlanarPatchGeometry.MAX_INDICES_PER_PATCH),
                FloatArray(count * 3), IntArray(count))
            if (balls) {
                val uniform = descriptor.palette == com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette.UNIFORM
                val directions = if (table.header.color == RendererAttributeCodec.OBSERVATIONS)
                    CoverageDirectionalColors.copyOfObservationClasses(ids, packed,
                        if (uniform) colors.firstOrNull() ?: 0xffffffff.toInt() else RendererAttributeCodec.ZERO_ARGB,
                        if (uniform) colors.firstOrNull() ?: 0xffffffff.toInt() else RendererAttributeCodec.ONE_ARGB,
                        if (uniform) colors.firstOrNull() ?: 0xffffffff.toInt() else RendererAttributeCodec.TWO_PLUS_ARGB,
                        if (uniform) colors.firstOrNull() ?: 0xffffffff.toInt() else RendererAttributeCodec.UNAVAILABLE_ARGB)
                else CoverageDirectionalColors.copyOfColors(ids, IntArray(count * 24) {
                    directionIdentityColor(it % 24)
                })
                CoverageDirectionalBallGeometry().write(input, directions, output)
            } else {
                // Matches the established guidance normal-eligibility threshold.
                CoveragePlanarPatchGeometry(minimumNormalConfidence = 64).write(input, output)
            }
            return PreparedCoverageShapes(RendererAttributeCodec.Header(table.header.bytes.copyOf()), output)
        }

        private fun directionIdentityColor(bin: Int): Int {
            // Stable 24 identities; color has no coverage/completion meaning.
            val lane = bin % 6; val band = bin / 6
            val high = 255 - band * 28; val low = 40 + band * 24
            return (255 shl 24) or ((if (lane in 0..2) high else low) shl 16) or
                ((if (lane in 2..4) high else low) shl 8) or (if (lane == 0 || lane >= 4) high else low)
        }

        private fun inverseAffine(m: DoubleArray): DoubleArray? {
            val a=m[0]; val b=m[4]; val c=m[8]; val d=m[1]; val e=m[5]; val f=m[9]
            val g=m[2]; val h=m[6]; val i=m[10]
            val determinant=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g)
            if (!determinant.isFinite() || determinant == 0.0) return null
            val result = doubleArrayOf((e*i-f*h)/determinant, (f*g-d*i)/determinant, (d*h-e*g)/determinant, 0.0,
                (c*h-b*i)/determinant, (a*i-c*g)/determinant, (b*g-a*h)/determinant, 0.0,
                (b*f-c*e)/determinant, (c*d-a*f)/determinant, (a*e-b*d)/determinant, 0.0, 0.0, 0.0, 0.0, 1.0)
            repeat(3) { axis -> result[12+axis] = -(result[axis]*m[12]+result[4+axis]*m[13]+result[8+axis]*m[14]) }
            return if (result.all { it.isFinite() }) result else null
        }
    }
}
