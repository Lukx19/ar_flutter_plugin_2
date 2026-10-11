package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Selected-sample-only local plane fit. No Frame, image, history, or observation is retained. */
internal class DepthLocalPlaneEstimator(private val capacity: Int) : AutoCloseable {
    private var heads = IntArray(hashCapacity(capacity)) { -1 }
    private var next = IntArray(capacity)
    private var tileSize = 1
    // Four scalar selected-footprint bounds fit the existing 256-byte reserve.
    private var selectedMinimumX = 0
    private var selectedMinimumY = 0
    private var selectedMaximumX = 0
    private var selectedMaximumY = 0
    var pointX = 0.0; private set
    var pointY = 0.0; private set
    var pointZ = 0.0; private set
    var normalX = 0.0; private set
    var normalY = 0.0; private set
    var normalZ = 0.0; private set
    var workUnits = 0; private set
    var fittedSamples = 0; private set
    private var px = 0.0
    private var py = 0.0
    private var pz = 0.0

    fun index(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, configuration: DepthEvidenceConfiguration) {
        java.util.Arrays.fill(heads, -1)
        tileSize = sqrt(metadata.intrinsics.imageWidth.toDouble() * metadata.intrinsics.imageHeight /
            maxOf(1, samples.count)).toInt().coerceIn(2, 64)
        selectedMinimumX = Int.MAX_VALUE; selectedMinimumY = Int.MAX_VALUE
        selectedMaximumX = Int.MIN_VALUE; selectedMaximumY = Int.MIN_VALUE
        workUnits = heads.size
        fittedSamples = 0
        for (index in 0 until samples.count) {
            val x = samples.xAt(index); val y = samples.yAt(index)
            if (x !in 0 until metadata.intrinsics.imageWidth || y !in 0 until metadata.intrinsics.imageHeight ||
                samples.confidenceAt(index) < configuration.confidenceMinimum || samples.depthMillimetresAt(index) !in
                configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres) continue
            selectedMinimumX = minOf(selectedMinimumX, x); selectedMaximumX = maxOf(selectedMaximumX, x)
            selectedMinimumY = minOf(selectedMinimumY, y); selectedMaximumY = maxOf(selectedMaximumY, y)
            val slot = bucket(x / tileSize, y / tileSize)
            next[index] = heads[slot]
            heads[slot] = index
            workUnits++
        }
    }

    /** Wider support must pass encoding/residual uncertainty as well as numerical conditioning. */
    fun estimate(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess,
        index: Int, configuration: DepthEvidenceConfiguration,
        certificate: CurrentEstimatePlaneSupportWords? = null, certificateBase: Int = 0,
        populationResidualFloor: Double = 0.0): Boolean {
        require(populationResidualFloor.isFinite() && populationResidualFloor >= 0.0)
        val x = samples.xAt(index); val y = samples.yAt(index)
        val depth = samples.depthMillimetresAt(index)
        if (selectedMinimumX > selectedMaximumX || selectedMinimumY > selectedMaximumY) return false
        val supportRadius = minOf(50.0, metadata.groupFrame.voxelSizeMicrometres / 1_000.0)
        val radiusX = ceil(supportRadius * metadata.intrinsics.fx / depth).toInt()
        val radiusY = ceil(supportRadius * metadata.intrinsics.fy / depth).toInt()
        val tileX = x / tileSize; val tileY = y / tileSize
        val lowX = maxOf(0, selectedMinimumX, x - radiusX) / tileSize
        val highX = minOf(metadata.intrinsics.imageWidth - 1, selectedMaximumX, x + radiusX) / tileSize
        val lowY = maxOf(0, selectedMinimumY, y - radiusY) / tileSize
        val highY = minOf(metadata.intrinsics.imageHeight - 1, selectedMaximumY, y + radiusY) / tileSize
        var left = -1; var right = -1; var top = -1; var bottom = -1
        var leftSpan = 0; var rightSpan = 0; var topSpan = 0; var bottomSpan = 0
        var leftOff = Int.MAX_VALUE; var rightOff = Int.MAX_VALUE
        var topOff = Int.MAX_VALUE; var bottomOff = Int.MAX_VALUE
        val depthGate = minOf(30.0, metadata.groupFrame.voxelSizeMicrometres / 1_000.0 * 0.3)
        for (ySlot in 0..4) {
            if (duplicateTile(ySlot, lowY, tileY, highY)) continue
            val queryY = axisTile(ySlot, lowY, tileY, highY)
            for (xSlot in 0..4) {
                if (duplicateTile(xSlot, lowX, tileX, highX)) continue
                val queryX = axisTile(xSlot, lowX, tileX, highX)
                var candidate = heads[bucket(queryX, queryY)]
                var visits = 0
                workUnits++
                while (candidate != -1 && visits < MAXIMUM_BUCKET_VISITS) {
                    visits++; workUnits++
                    val candidateX = samples.xAt(candidate); val candidateY = samples.yAt(candidate)
                    if (candidate != index && candidateX / tileSize == queryX && candidateY / tileSize == queryY &&
                        candidateX in 0 until metadata.intrinsics.imageWidth &&
                        candidateY in 0 until metadata.intrinsics.imageHeight &&
                        samples.confidenceAt(candidate) >= configuration.confidenceMinimum &&
                        samples.depthMillimetresAt(candidate) in configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres &&
                        abs(samples.depthMillimetresAt(candidate) - depth) <= depthGate) {
                        val dx = candidateX - x; val dy = candidateY - y
                        if (abs(dx) <= radiusX && abs(dy) <= radiusY) {
                            if (abs(dy) * 2 <= abs(dx) && dx != 0) {
                                if (dx < 0 && better(-dx, abs(dy), candidateX, candidateY, leftSpan, leftOff, left, samples)) {
                                    left = candidate; leftSpan = -dx; leftOff = abs(dy)
                                }
                                if (dx > 0 && better(dx, abs(dy), candidateX, candidateY, rightSpan, rightOff, right, samples)) {
                                    right = candidate; rightSpan = dx; rightOff = abs(dy)
                                }
                            }
                            if (abs(dx) * 2 <= abs(dy) && dy != 0) {
                                if (dy < 0 && better(-dy, abs(dx), candidateX, candidateY, topSpan, topOff, top, samples)) {
                                    top = candidate; topSpan = -dy; topOff = abs(dx)
                                }
                                if (dy > 0 && better(dy, abs(dx), candidateX, candidateY, bottomSpan, bottomOff, bottom, samples)) {
                                    bottom = candidate; bottomSpan = dy; bottomOff = abs(dx)
                                }
                            }
                        }
                    }
                    candidate = next[candidate]
                }
            }
        }
        if ((left < 0 && right < 0) || (top < 0 && bottom < 0)) return false
        if (left < 0) left = index
        if (right < 0) right = index
        if (top < 0) top = index
        if (bottom < 0) bottom = index
        return qualify(metadata, samples, index, configuration, left, right, top, bottom,
            certificate, certificateBase, false, populationResidualFloor)
    }

    /** Reuse supplied current-estimate support; every new centre is qualified again. */
    fun tryReuse(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int,
        configuration: DepthEvidenceConfiguration, words: CurrentEstimatePlaneSupportWords, base: Int): Boolean {
        if (!eligible(metadata, samples, index, configuration)) return false
        val x = samples.xAt(index); val y = samples.yAt(index)
        if (!insideFootprint(words, base, x, y)) return false
        val left = words.readPlaneSupportWord(base + CurrentEstimatePlaneSupportLayout.LEFT)
        val right = words.readPlaneSupportWord(base + CurrentEstimatePlaneSupportLayout.RIGHT)
        val top = words.readPlaneSupportWord(base + CurrentEstimatePlaneSupportLayout.TOP)
        val bottom = words.readPlaneSupportWord(base + CurrentEstimatePlaneSupportLayout.BOTTOM)
        val seed = words.readPlaneSupportWord(base + CurrentEstimatePlaneSupportLayout.SEED)
        if (!sampleMatches(metadata, samples, configuration, left, words, base + 42) ||
            !sampleMatches(metadata, samples, configuration, right, words, base + 46) ||
            !sampleMatches(metadata, samples, configuration, top, words, base + 50) ||
            !sampleMatches(metadata, samples, configuration, bottom, words, base + 54) ||
            !sampleMatches(metadata, samples, configuration, seed, words, base + 58)) return false
        return qualify(metadata, samples, index, configuration, left, right, top, bottom, words, base, true)
    }

    private fun qualify(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int,
        configuration: DepthEvidenceConfiguration, left: Int, right: Int, top: Int, bottom: Int,
        words: CurrentEstimatePlaneSupportWords?, base: Int, reused: Boolean, populationResidualFloor: Double = 0.0): Boolean {
        val depth = samples.depthMillimetresAt(index)
        val depthGate = minOf(30.0, metadata.groupFrame.voxelSizeMicrometres / 1000.0 * 0.3)
        if (abs(samples.depthMillimetresAt(left) - depth) > depthGate ||
            abs(samples.depthMillimetresAt(right) - depth) > depthGate ||
            abs(samples.depthMillimetresAt(top) - depth) > depthGate ||
            abs(samples.depthMillimetresAt(bottom) - depth) > depthGate) return false
        val supportRadius = minOf(50.0, metadata.groupFrame.voxelSizeMicrometres / 1000.0)
        point(metadata, samples, index); val ix = px; val iy = py; val iz = pz
        supportPoint(metadata, samples, left, words, base + 14, reused); val lx = px; val ly = py; val lz = pz
        supportPoint(metadata, samples, right, words, base + 20, reused); val rx = px; val ry = py; val rz = pz
        supportPoint(metadata, samples, top, words, base + 26, reused); val tx = px; val ty = py; val tz = pz
        supportPoint(metadata, samples, bottom, words, base + 32, reused); val bx = px; val by = py; val bz = pz
        val radius2 = supportRadius * supportRadius
        if (distance2(ix, iy, iz, lx, ly, lz) > radius2 || distance2(ix, iy, iz, rx, ry, rz) > radius2 ||
            distance2(ix, iy, iz, tx, ty, tz) > radius2 || distance2(ix, iy, iz, bx, by, bz) > radius2) return false
        val ux = rx - lx; val uy = ry - ly; val uz = rz - lz
        val vx = bx - tx; val vy = by - ty; val vz = bz - tz
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        val uLength = sqrt(ux * ux + uy * uy + uz * uz)
        val vLength = sqrt(vx * vx + vy * vy + vz * vz)
        val tangentProduct = uLength * vLength
        if (!length.isFinite() || !tangentProduct.isFinite() || uLength <= 0.0 || vLength <= 0.0 ||
            length <= maxOf(1e-12, tangentProduct * 1e-12) || length / tangentProduct < 0.5) return false
        nx /= length; ny /= length; nz /= length
        val cx = (lx + rx + tx + bx) * 0.25
        val cy = (ly + ry + ty + by) * 0.25
        val cz = (lz + rz + tz + bz) * 0.25
        val residual = (ix - cx) * nx + (iy - cy) * ny + (iz - cz) * nz
        val maximumResidual = maxOf(abs(residual),
            maxOf(abs((lx - cx) * nx + (ly - cy) * ny + (lz - cz) * nz),
                abs((rx - cx) * nx + (ry - cy) * ny + (rz - cz) * nz)),
            maxOf(abs((tx - cx) * nx + (ty - cy) * ny + (tz - cz) * nz),
                abs((bx - cx) * nx + (by - cy) * ny + (bz - cz) * nz)))
        if (!maximumResidual.isFinite() || maximumResidual >
            minOf(12.0, metadata.groupFrame.voxelSizeMicrometres / 1000.0 * 0.12)) return false
        val uncertainty = 1.0 + maxOf(maximumResidual, populationResidualFloor)
        val raySumU = if (reused) CurrentEstimatePlaneSupportLayout.readDouble(requireNotNull(words), base + 38)
            else rayNorm(metadata, samples, left) + rayNorm(metadata, samples, right)
        val raySumV = if (reused) CurrentEstimatePlaneSupportLayout.readDouble(requireNotNull(words), base + 40)
            else rayNorm(metadata, samples, top) + rayNorm(metadata, samples, bottom)
        val eu = uncertainty * raySumU; val ev = uncertainty * raySumV
        val centerUncertainty = uncertainty * rayNorm(metadata, samples, index)
        val perturbation = eu * vLength + ev * uLength + eu * ev
        if (!centerUncertainty.isFinite() || !perturbation.isFinite() || perturbation / length > ANGULAR_DISCRIMINATION_SINE) return false
        if (reused && !localWitnesses(metadata, samples, index, configuration, ix, iy, iz, nx, ny, nz, cx, cy, cz, residual)) return false
        pointX = ix - residual * nx; pointY = iy - residual * ny; pointZ = iz - residual * nz
        val matrix = metadata.groupFromCameraGl
        if ((matrix[12] * 1000.0 - pointX) * nx + (matrix[13] * 1000.0 - pointY) * ny +
            (matrix[14] * 1000.0 - pointZ) * nz < 0.0) { nx = -nx; ny = -ny; nz = -nz }
        normalX = nx; normalY = ny; normalZ = nz
        val valid = pointX.isFinite() && pointY.isFinite() && pointZ.isFinite()
        if (valid && !reused) {
            fittedSamples++
            if (words != null) {
                words.writePlaneSupportWord(base + 3, index)
                words.writePlaneSupportWord(base + 4, left); words.writePlaneSupportWord(base + 5, right)
                words.writePlaneSupportWord(base + 6, top); words.writePlaneSupportWord(base + 7, bottom)
                writeFootprint(samples, words, base, index, left, right, top, bottom)
                writePoint(words, base + 14, lx, ly, lz); writePoint(words, base + 20, rx, ry, rz)
                writePoint(words, base + 26, tx, ty, tz); writePoint(words, base + 32, bx, by, bz)
                CurrentEstimatePlaneSupportLayout.writeDouble(words, base + 38, raySumU)
                CurrentEstimatePlaneSupportLayout.writeDouble(words, base + 40, raySumV)
                writeSample(samples, left, words, base + 42); writeSample(samples, right, words, base + 46)
                writeSample(samples, top, words, base + 50); writeSample(samples, bottom, words, base + 54)
                writeSample(samples, index, words, base + 58)
            }
        }
        return valid
    }

    /** Near witnesses veto discontinuities and incompatible local tangents; uncertainty falls back. */
    private fun localWitnesses(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int,
        configuration: DepthEvidenceConfiguration, ix: Double, iy: Double, iz: Double,
        nx: Double, ny: Double, nz: Double, cx: Double, cy: Double, cz: Double, centerResidual: Double): Boolean {
        val x = samples.xAt(index); val y = samples.yAt(index); val depth = samples.depthMillimetresAt(index)
        val tileX = x / tileSize; val tileY = y / tileSize
        val gate = minOf(30.0, metadata.groupFrame.voxelSizeMicrometres / 1000.0 * 0.3)
        var directions = 0
        for (dyTile in -1..1) for (dxTile in -1..1) {
            val queryX = tileX + dxTile; val queryY = tileY + dyTile
            var candidate = heads[bucket(queryX, queryY)]; var visits = 0
            workUnits++
            while (candidate != -1 && visits < MAXIMUM_BUCKET_VISITS) {
                visits++; workUnits++
                val ax = samples.xAt(candidate); val ay = samples.yAt(candidate)
                if (candidate != index && ax / tileSize == queryX && ay / tileSize == queryY && eligible(metadata, samples, candidate, configuration)) {
                    val dx = ax - x; val dy = ay - y
                    if (abs(dx) <= tileSize * 2 && abs(dy) <= tileSize * 2) {
                        if (abs(samples.depthMillimetresAt(candidate) - depth) > gate) return false
                        point(metadata, samples, candidate)
                        val signed = (px - cx) * nx + (py - cy) * ny + (pz - cz) * nz
                        val distance = sqrt(distance2(ix, iy, iz, px, py, pz))
                        if (!signed.isFinite() || !distance.isFinite() || distance <= 0.0 ||
                            abs(signed) > minOf(12.0, metadata.groupFrame.voxelSizeMicrometres / 1000.0 * 0.12) ||
                            abs(signed - centerResidual) > distance * ANGULAR_DISCRIMINATION_SINE) return false
                        if (abs(dy) * 2 <= abs(dx)) { if (dx < 0) directions = directions or 1; if (dx > 0) directions = directions or 2 }
                        if (abs(dx) * 2 <= abs(dy)) { if (dy < 0) directions = directions or 4; if (dy > 0) directions = directions or 8 }
                    }
                }
                candidate = next[candidate]
            }
        }
        val required = (if (x > selectedMinimumX) 1 else 0) or (if (x < selectedMaximumX) 2 else 0) or
            (if (y > selectedMinimumY) 4 else 0) or (if (y < selectedMaximumY) 8 else 0)
        return directions and required == required && directions and 3 != 0 && directions and 12 != 0
    }

    private fun supportPoint(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int,
        words: CurrentEstimatePlaneSupportWords?, offset: Int, cached: Boolean) {
        if (!cached) { point(metadata, samples, index); return }
        val source = requireNotNull(words)
        px = CurrentEstimatePlaneSupportLayout.readDouble(source, offset)
        py = CurrentEstimatePlaneSupportLayout.readDouble(source, offset + 2)
        pz = CurrentEstimatePlaneSupportLayout.readDouble(source, offset + 4)
    }

    private fun eligible(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int,
        configuration: DepthEvidenceConfiguration): Boolean = index in 0 until samples.count &&
        samples.xAt(index) in 0 until metadata.intrinsics.imageWidth && samples.yAt(index) in 0 until metadata.intrinsics.imageHeight &&
        samples.confidenceAt(index) >= configuration.confidenceMinimum &&
        samples.depthMillimetresAt(index) in configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres

    private fun sampleMatches(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess,
        configuration: DepthEvidenceConfiguration, index: Int, words: CurrentEstimatePlaneSupportWords, offset: Int): Boolean =
        eligible(metadata, samples, index, configuration) && samples.xAt(index) == words.readPlaneSupportWord(offset) &&
            samples.yAt(index) == words.readPlaneSupportWord(offset + 1) &&
            samples.depthMillimetresAt(index) == words.readPlaneSupportWord(offset + 2) &&
            samples.confidenceAt(index) == words.readPlaneSupportWord(offset + 3)

    private fun writeSample(samples: DepthEvidenceSampleAccess, index: Int, words: CurrentEstimatePlaneSupportWords, offset: Int) {
        words.writePlaneSupportWord(offset, samples.xAt(index)); words.writePlaneSupportWord(offset + 1, samples.yAt(index))
        words.writePlaneSupportWord(offset + 2, samples.depthMillimetresAt(index)); words.writePlaneSupportWord(offset + 3, samples.confidenceAt(index))
    }

    private fun writePoint(words: CurrentEstimatePlaneSupportWords, offset: Int, x: Double, y: Double, z: Double) {
        CurrentEstimatePlaneSupportLayout.writeDouble(words, offset, x)
        CurrentEstimatePlaneSupportLayout.writeDouble(words, offset + 2, y)
        CurrentEstimatePlaneSupportLayout.writeDouble(words, offset + 4, z)
    }

    /** Monotone hull of five points uses certificate words14..23 only before geometry is written. */
    private fun writeFootprint(samples: DepthEvidenceSampleAccess, words: CurrentEstimatePlaneSupportWords, base: Int,
        seed: Int, left: Int, right: Int, top: Int, bottom: Int) {
        for (position in 0..4) {
            val index = when (position) { 0 -> seed; 1 -> left; 2 -> right; 3 -> top; else -> bottom }
            words.writePlaneSupportWord(base + 9 + position, (samples.xAt(index) shl 16) or samples.yAt(index))
        }
        for (position in 1..4) {
            val value = words.readPlaneSupportWord(base + 9 + position); var destination = position
            while (destination > 0 && words.readPlaneSupportWord(base + 8 + destination) > value) {
                words.writePlaneSupportWord(base + 9 + destination, words.readPlaneSupportWord(base + 8 + destination)); destination--
            }
            words.writePlaneSupportWord(base + 9 + destination, value)
        }
        var unique = 0
        for (position in 0..4) {
            val value = words.readPlaneSupportWord(base + 9 + position)
            if (unique == 0 || value != words.readPlaneSupportWord(base + 8 + unique)) words.writePlaneSupportWord(base + 9 + unique++, value)
        }
        var count = 0
        for (position in 0 until unique) {
            val value = words.readPlaneSupportWord(base + 9 + position)
            while (count >= 2 && cross(words.readPlaneSupportWord(base + 12 + count), words.readPlaneSupportWord(base + 13 + count), value) <= 0L) count--
            words.writePlaneSupportWord(base + 14 + count++, value)
        }
        val threshold = count + 1
        for (position in unique - 2 downTo 0) {
            val value = words.readPlaneSupportWord(base + 9 + position)
            while (count >= threshold && cross(words.readPlaneSupportWord(base + 12 + count), words.readPlaneSupportWord(base + 13 + count), value) <= 0L) count--
            words.writePlaneSupportWord(base + 14 + count++, value)
        }
        if (count > 1) count--
        check(count <= 5)
        words.writePlaneSupportWord(base + 8, count)
        for (position in 0 until count) words.writePlaneSupportWord(base + 9 + position, words.readPlaneSupportWord(base + 14 + position))
    }

    private fun insideFootprint(words: CurrentEstimatePlaneSupportWords, base: Int, x: Int, y: Int): Boolean {
        val count = words.readPlaneSupportWord(base + 8)
        if (count !in 3..5) return false
        val point = (x shl 16) or y
        for (position in 0 until count) if (cross(words.readPlaneSupportWord(base + 9 + position),
                words.readPlaneSupportWord(base + 9 + (position + 1) % count), point) < 0L) return false
        return true
    }

    private fun cross(a: Int, b: Int, c: Int): Long {
        val ax = a ushr 16; val ay = a and 0xffff
        return (b.ushr(16) - ax).toLong() * ((c and 0xffff) - ay) -
            ((b and 0xffff) - ay).toLong() * (c.ushr(16) - ax)
    }

    /** Scalar extraction shared by the bulk prepass; never retains a sample or matrix. */
    fun loadMeasuredPoint(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int): Boolean {
        // Match the ordinary kernel's metres transform evaluation order exactly at cell boundaries.
        val depth = samples.depthMillimetresAt(index)
        val x = ((samples.xAt(index) - metadata.intrinsics.cx) * depth / metadata.intrinsics.fx) / 1_000.0
        val y = (-(samples.yAt(index) - metadata.intrinsics.cy) * depth / metadata.intrinsics.fy) / 1_000.0
        val z = -depth.toDouble() / 1_000.0
        val m = metadata.groupFromCameraGl
        val tx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val ty = m[1] * x + m[5] * y + m[9] * z + m[13]
        val tz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val tw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (!tx.isFinite() || !ty.isFinite() || !tz.isFinite() || !tw.isFinite() || abs(tw) <= 1e-12) return false
        pointX = tx * 1_000.0 / tw; pointY = ty * 1_000.0 / tw; pointZ = tz * 1_000.0 / tw
        return pointX.isFinite() && pointY.isFinite() && pointZ.isFinite()
    }

    fun setProjection(x: Double, y: Double, z: Double, nx: Double, ny: Double, nz: Double) {
        check(x.isFinite() && y.isFinite() && z.isFinite() && nx.isFinite() && ny.isFinite() && nz.isFinite())
        pointX = x; pointY = y; pointZ = z; normalX = nx; normalY = ny; normalZ = nz
    }

    /** Pixel conflicts use the existing index columns before rebuilding the support index. */
    fun indexPixelConflicts(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess,
        configuration: DepthEvidenceConfiguration): Boolean {
        java.util.Arrays.fill(heads, -1)
        workUnits = heads.size; fittedSamples = 0
        for (index in 0 until samples.count) {
            if (!eligiblePixel(metadata, samples, index, configuration)) continue
            val x = samples.xAt(index); val y = samples.yAt(index)
            var slot = bucket(x, y); var found = false
            for (probe in 0 until 64) {
                workUnits++
                val representative = heads[slot]
                if (representative == -1) { heads[slot] = index; next[index] = 0; found = true; break }
                if (samples.xAt(representative) == x && samples.yAt(representative) == y) {
                    if (samples.depthMillimetresAt(representative) != samples.depthMillimetresAt(index)) next[representative] = 1
                    found = true; break
                }
                slot = (slot + 1) and (heads.size - 1)
            }
            if (!found) return false
        }
        return true
    }

    fun pixelConflicted(samples: DepthEvidenceSampleAccess, index: Int): Boolean {
        val x = samples.xAt(index); val y = samples.yAt(index)
        var slot = bucket(x, y)
        for (probe in 0 until 64) {
            workUnits++
            val representative = heads[slot]
            check(representative != -1)
            if (samples.xAt(representative) == x && samples.yAt(representative) == y) return next[representative] != 0
            slot = (slot + 1) and (heads.size - 1)
        }
        error("qualified pixel index lookup exceeded its insertion bound")
    }

    private fun eligiblePixel(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int,
        configuration: DepthEvidenceConfiguration): Boolean = samples.xAt(index) in 0 until metadata.intrinsics.imageWidth &&
        samples.yAt(index) in 0 until metadata.intrinsics.imageHeight && samples.confidenceAt(index) >= configuration.confidenceMinimum &&
        samples.depthMillimetresAt(index) in configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres

    private fun axisTile(slot: Int, low: Int, center: Int, high: Int): Int = when (slot) {
        0 -> low; 1 -> (low + center) / 2; 2 -> center; 3 -> (center + high) / 2; else -> high
    }

    private fun duplicateTile(slot: Int, low: Int, center: Int, high: Int): Boolean {
        val value = axisTile(slot, low, center, high)
        for (prior in 0 until slot) if (axisTile(prior, low, center, high) == value) return true
        return false
    }

    private fun better(span: Int, off: Int, x: Int, y: Int, bestSpan: Int, bestOff: Int,
        best: Int, samples: DepthEvidenceSampleAccess): Boolean =
        span > bestSpan || (span == bestSpan && (off < bestOff || (off == bestOff &&
            (best < 0 || x < samples.xAt(best) || (x == samples.xAt(best) && y < samples.yAt(best))))))

    private fun distance2(x: Double, y: Double, z: Double, ax: Double, ay: Double, az: Double): Double =
        (x - ax) * (x - ax) + (y - ay) * (y - ay) + (z - az) * (z - az)

    private fun rayNorm(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int): Double {
        val x = (samples.xAt(index) - metadata.intrinsics.cx) / metadata.intrinsics.fx
        val y = (samples.yAt(index) - metadata.intrinsics.cy) / metadata.intrinsics.fy
        return sqrt(1.0 + x * x + y * y)
    }

    private fun point(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int) {
        val depth = samples.depthMillimetresAt(index).toDouble()
        val x = (samples.xAt(index) - metadata.intrinsics.cx) * depth / metadata.intrinsics.fx
        val y = -(samples.yAt(index) - metadata.intrinsics.cy) * depth / metadata.intrinsics.fy
        val z = -depth
        val m = metadata.groupFromCameraGl
        val w = (m[3] * x + m[7] * y + m[11] * z) / 1_000.0 + m[15]
        px = (m[0] * x + m[4] * y + m[8] * z + m[12] * 1_000.0) / w
        py = (m[1] * x + m[5] * y + m[9] * z + m[13] * 1_000.0) / w
        pz = (m[2] * x + m[6] * y + m[10] * z + m[14] * 1_000.0) / w
    }

    private fun bucket(x: Int, y: Int): Int = ((x * 73_856_093) xor (y * 19_349_663)) and (heads.size - 1)
    internal fun primitiveArraysForAccounting(): List<Any> = listOf(heads, next)
    override fun close() { heads = IntArray(0); next = IntArray(0) }
    internal companion object {
        const val MAXIMUM_BUCKET_VISITS = 16
        const val ANGULAR_DISCRIMINATION_SINE = 0.17364817766693033
        private fun hashCapacity(capacity: Int): Int {
            var value = 2
            while (value < capacity * 2) value *= 2
            return value
        }
    }
}
