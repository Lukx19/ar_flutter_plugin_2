package com.uhg0.ar_flutter_plugin_2.pointcloud

const val COVERAGE_RENDERER_STYLE_ROW_BYTES = 16
const val COVERAGE_RENDERER_MAX_STYLE_PATCH_ROWS = 2_048
const val COVERAGE_RENDERER_NO_DIRECTION = 0xff
private const val COVERAGE_RENDERER_STYLE_VERSION = 1
private const val COVERAGE_RENDERER_U32_MAX = 0xffff_ffffL

enum class CoverageRendererSemantic(val code: Int) {
    CONFIRMED(0),
    AMBIGUOUS(1),
    SUPPRESSED_DEBUG(2),
}

enum class CoverageRendererCoverage(val code: Int) {
    UNCOVERED(0),
    PARTIAL(1),
    COMPLETE(2),
}

enum class CoverageRendererPalette(val code: Int) {
    UNIFORM(0),
    COVERAGE(1),
    NORMAL(2),
    OCCUPANCY(3),
    LINEAGE(4),
    AGE(5),
    SOURCE_HEALTH(6),
    RESIDENCY(7),
    DIRECTION(8),
}

enum class CoverageRendererCut(val code: Int) {
    EXACT_CURRENT(0),
    STALE_DISPLAY(1),
    LOWER_BOUND(2),
    COVERAGE_PENDING(3),
    INDETERMINATE_HISTORY(4),
    UNAVAILABLE(5),
}

enum class CoverageRendererResidency(val code: Int) {
    ACTIVE_L0(0),
    WARM_L1(1),
    COLD_L2(2),
}

enum class CoverageRendererTarget(val code: Int) {
    NONE(0),
    PRIMARY(1),
    HALO(2),
}

enum class CoverageRendererGlyph(val code: Int) {
    NONE(0),
    NORMAL(1),
    DESIRED_DIRECTION(2),
    VIEW_ROSE(3),
}

enum class CoverageRendererAge(val code: Int) {
    FRESH(0),
    RECENT(1),
    AGING(2),
    OLD(3),
}

enum class CoverageRendererSourceHealth(val code: Int) {
    HEALTHY(0),
    FEATURE_ONLY(1),
    TRANSIENT_UNAVAILABLE(2),
    FAILED(3),
    UNSUPPORTED(4),
}

/**
 * Fixed-width renderer projection of one committed semantic/style cut.
 *
 * This is disposable presentation state, never semantic authority. Every enum
 * uses an explicit byte code, the two generations are unsigned 32-bit values,
 * lineage is an unsigned 16-bit count, and all reserved bytes must be zero.
 */
data class CoverageRendererStyleRowV1(
    val semanticGeneration: Long = 0,
    val styleGeneration: Long = 0,
    val semantic: CoverageRendererSemantic = CoverageRendererSemantic.CONFIRMED,
    val coverage: CoverageRendererCoverage = CoverageRendererCoverage.UNCOVERED,
    val palette: CoverageRendererPalette = CoverageRendererPalette.COVERAGE,
    val cut: CoverageRendererCut = CoverageRendererCut.EXACT_CURRENT,
    val residency: CoverageRendererResidency = CoverageRendererResidency.ACTIVE_L0,
    val target: CoverageRendererTarget = CoverageRendererTarget.NONE,
    val directionBin: Int = COVERAGE_RENDERER_NO_DIRECTION,
    val glyph: CoverageRendererGlyph = CoverageRendererGlyph.NONE,
    val lineageCount: Int = 0,
    val age: CoverageRendererAge = CoverageRendererAge.FRESH,
    val sourceHealth: CoverageRendererSourceHealth = CoverageRendererSourceHealth.HEALTHY,
) {
    init {
        require(semanticGeneration in 0..COVERAGE_RENDERER_U32_MAX)
        require(styleGeneration in 0..COVERAGE_RENDERER_U32_MAX)
        require(lineageCount in 0..0xffff)
        val hasDirection = directionBin in 0..23
        require(hasDirection || directionBin == COVERAGE_RENDERER_NO_DIRECTION)
        require(
            when (glyph) {
                CoverageRendererGlyph.NONE,
                CoverageRendererGlyph.NORMAL,
                -> !hasDirection
                CoverageRendererGlyph.DESIRED_DIRECTION,
                CoverageRendererGlyph.VIEW_ROSE,
                -> hasDirection
            },
        )
    }

    /**
     * Encodes one row into a fresh 16-byte buffer for compatibility callers.
     * Hot paths should pass a caller-owned buffer to [encodeInto] so a style
     * update does not allocate a temporary ByteBuffer and byte array per row.
     */
    fun encode(): ByteArray = ByteArray(COVERAGE_RENDERER_STYLE_ROW_BYTES).also { encodeInto(it) }

    /** Writes this row directly into [destination], preserving bytes outside the row. */
    fun encodeInto(destination: ByteArray, offset: Int = 0) {
        require(offset >= 0 && destination.size - offset >= COVERAGE_RENDERER_STYLE_ROW_BYTES)
        destination[offset] = COVERAGE_RENDERER_STYLE_VERSION.toByte()
        destination[offset + 1] = (
            semantic.code or
                (coverage.code shl 2) or
                (residency.code shl 4) or
                (target.code shl 6)
            ).toByte()
        destination[offset + 2] = (palette.code or (cut.code shl 4)).toByte()
        destination[offset + 3] = (
            glyph.code or
                (age.code shl 2) or
                (sourceHealth.code shl 4)
            ).toByte()
        destination[offset + 4] = directionBin.toByte()
        destination[offset + 5] = 0
        putU16(destination, offset + 6, lineageCount)
        putU32(destination, offset + 8, semanticGeneration)
        putU32(destination, offset + 12, styleGeneration)
    }

    /** Deterministic ARGB projection used by the current point/cube material. */
    fun packedColor(): Int {
        when (cut) {
            CoverageRendererCut.COVERAGE_PENDING -> return 0xffffa000.toInt()
            CoverageRendererCut.INDETERMINATE_HISTORY -> return 0xff616161.toInt()
            CoverageRendererCut.UNAVAILABLE -> return 0x00000000
            CoverageRendererCut.STALE_DISPLAY -> return 0xff8d6e63.toInt()
            CoverageRendererCut.LOWER_BOUND -> return 0xff5c6bc0.toInt()
            CoverageRendererCut.EXACT_CURRENT -> Unit
        }
        return when (palette) {
            CoverageRendererPalette.UNIFORM -> 0xffffffff.toInt()
            CoverageRendererPalette.COVERAGE -> when (coverage) {
                CoverageRendererCoverage.UNCOVERED -> 0xffd50000.toInt()
                CoverageRendererCoverage.PARTIAL -> 0xffffab00.toInt()
                CoverageRendererCoverage.COMPLETE -> 0xff00c853.toInt()
            }
            CoverageRendererPalette.NORMAL -> 0xff42a5f5.toInt()
            CoverageRendererPalette.OCCUPANCY -> when (semantic) {
                CoverageRendererSemantic.CONFIRMED -> 0xff1e88e5.toInt()
                CoverageRendererSemantic.AMBIGUOUS -> 0xfffb8c00.toInt()
                CoverageRendererSemantic.SUPPRESSED_DEBUG -> 0xff8e24aa.toInt()
            }
            CoverageRendererPalette.LINEAGE -> when (lineageCount) {
                0 -> 0xff78909c.toInt()
                1 -> 0xff3949ab.toInt()
                else -> 0xff6a1b9a.toInt()
            }
            CoverageRendererPalette.AGE -> when (age) {
                CoverageRendererAge.FRESH -> 0xff26c6da.toInt()
                CoverageRendererAge.RECENT -> 0xff66bb6a.toInt()
                CoverageRendererAge.AGING -> 0xffffca28.toInt()
                CoverageRendererAge.OLD -> 0xff8d6e63.toInt()
            }
            CoverageRendererPalette.SOURCE_HEALTH -> when (sourceHealth) {
                CoverageRendererSourceHealth.HEALTHY -> 0xff00c853.toInt()
                CoverageRendererSourceHealth.FEATURE_ONLY -> 0xff039be5.toInt()
                CoverageRendererSourceHealth.TRANSIENT_UNAVAILABLE -> 0xffffa000.toInt()
                CoverageRendererSourceHealth.FAILED -> 0xffd50000.toInt()
                CoverageRendererSourceHealth.UNSUPPORTED -> 0xff757575.toInt()
            }
            CoverageRendererPalette.RESIDENCY -> when (residency) {
                CoverageRendererResidency.ACTIVE_L0 -> 0xff26a69a.toInt()
                CoverageRendererResidency.WARM_L1 -> 0xffffb300.toInt()
                CoverageRendererResidency.COLD_L2 -> 0xff78909c.toInt()
            }
            CoverageRendererPalette.DIRECTION -> when (directionBin / 8) {
                0 -> 0xff5c6bc0.toInt()
                1 -> 0xff29b6f6.toInt()
                else -> 0xff26a69a.toInt()
            }
        }
    }

    companion object {
        /** Full row validation without constructing a DTO, including rows with no glyph. */
        internal fun validateEncoded(bytes: ByteArray, offset: Int = 0) {
            require(offset >= 0 && bytes.size - offset >= COVERAGE_RENDERER_STYLE_ROW_BYTES)
            require(bytes[offset].u8() == COVERAGE_RENDERER_STYLE_VERSION)
            val semantics = bytes[offset + 1].u8()
            semanticByCode(semantics and 3)
            coverageByCode((semantics ushr 2) and 3)
            residencyByCode((semantics ushr 4) and 3)
            targetByCode((semantics ushr 6) and 3)
            val paletteCut = bytes[offset + 2].u8()
            paletteByCode(paletteCut and 15)
            cutByCode((paletteCut ushr 4) and 7)
            require(paletteCut and 128 == 0)
            val glyphAgeHealth = bytes[offset + 3].u8()
            val glyph = glyphByCode(glyphAgeHealth and 3)
            ageByCode((glyphAgeHealth ushr 2) and 3)
            sourceHealthByCode((glyphAgeHealth ushr 4) and 7)
            require(glyphAgeHealth and 128 == 0)
            val direction = bytes[offset + 4].u8()
            val hasDirection = direction in 0..23
            require(hasDirection || direction == COVERAGE_RENDERER_NO_DIRECTION)
            require(when (glyph) {
                CoverageRendererGlyph.NONE, CoverageRendererGlyph.NORMAL -> !hasDirection
                CoverageRendererGlyph.DESIRED_DIRECTION, CoverageRendererGlyph.VIEW_ROSE -> hasDirection
            })
            require(bytes[offset + 5].u8() == 0)
            // Remaining columns are unsigned u16/u32 values: every bit pattern
            // is valid once the complete row extent has been checked above.
        }

        internal fun validatedGlyph(bytes: ByteArray, offset: Int = 0): CoverageRendererGlyph {
            validateEncoded(bytes, offset)
            return glyphByCode(bytes[offset + 3].u8() and 3)
        }

        /** Same validated color projection as [packedColor], without a row DTO. */
        internal fun validatedPackedColor(bytes: ByteArray, offset: Int = 0): Int {
            validateEncoded(bytes, offset)
            val flags = bytes[offset + 1].u8()
            val paletteCut = bytes[offset + 2].u8()
            val glyphAgeHealth = bytes[offset + 3].u8()
            when ((paletteCut ushr 4) and 7) {
                1 -> return 0xff8d6e63.toInt()
                2 -> return 0xff5c6bc0.toInt()
                3 -> return 0xffffa000.toInt()
                4 -> return 0xff616161.toInt()
                5 -> return 0x00000000
            }
            return when (paletteCut and 15) {
                0 -> 0xffffffff.toInt()
                1 -> when ((flags ushr 2) and 3) {
                    0 -> 0xffd50000.toInt(); 1 -> 0xffffab00.toInt(); else -> 0xff00c853.toInt()
                }
                2 -> 0xff42a5f5.toInt()
                3 -> when (flags and 3) {
                    0 -> 0xff1e88e5.toInt(); 1 -> 0xfffb8c00.toInt(); else -> 0xff8e24aa.toInt()
                }
                4 -> when (u16(bytes, offset + 6)) {
                    0 -> 0xff78909c.toInt(); 1 -> 0xff3949ab.toInt(); else -> 0xff6a1b9a.toInt()
                }
                5 -> when ((glyphAgeHealth ushr 2) and 3) {
                    0 -> 0xff26c6da.toInt(); 1 -> 0xff66bb6a.toInt()
                    2 -> 0xffffca28.toInt(); else -> 0xff8d6e63.toInt()
                }
                6 -> when ((glyphAgeHealth ushr 4) and 7) {
                    0 -> 0xff00c853.toInt(); 1 -> 0xff039be5.toInt(); 2 -> 0xffffa000.toInt()
                    3 -> 0xffd50000.toInt(); else -> 0xff757575.toInt()
                }
                7 -> when ((flags ushr 4) and 3) {
                    0 -> 0xff26a69a.toInt(); 1 -> 0xffffb300.toInt(); else -> 0xff78909c.toInt()
                }
                else -> when (bytes[offset + 4].u8() / 8) {
                    0 -> 0xff5c6bc0.toInt(); 1 -> 0xff29b6f6.toInt(); else -> 0xff26a69a.toInt()
                }
            }
        }

        /** A renderer packet may contain many rows, but only one committed cut. */
        fun hasCoherentGenerations(rows: Iterable<CoverageRendererStyleRowV1>): Boolean {
            var semanticGeneration: Long? = null
            var styleGeneration: Long? = null
            rows.forEach { row ->
                if (semanticGeneration == null) {
                    semanticGeneration = row.semanticGeneration
                    styleGeneration = row.styleGeneration
                } else if (
                    row.semanticGeneration != semanticGeneration ||
                    row.styleGeneration != styleGeneration
                ) {
                    return false
                }
            }
            return true
        }

        fun decode(bytes: ByteArray, offset: Int = 0): CoverageRendererStyleRowV1 {
            require(offset >= 0 && bytes.size - offset >= COVERAGE_RENDERER_STYLE_ROW_BYTES)
            require(bytes[offset].u8() == COVERAGE_RENDERER_STYLE_VERSION)
            val semanticBits = bytes[offset + 1].u8()
            val semantic = semanticByCode(semanticBits and 0x3)
            val coverage = coverageByCode((semanticBits ushr 2) and 0x3)
            val residency = residencyByCode((semanticBits ushr 4) and 0x3)
            val target = targetByCode((semanticBits ushr 6) and 0x3)
            val paletteCutBits = bytes[offset + 2].u8()
            val palette = paletteByCode(paletteCutBits and 0xf)
            val cut = cutByCode((paletteCutBits ushr 4) and 0x7)
            require(paletteCutBits and 0x80 == 0)
            val glyphAgeHealthBits = bytes[offset + 3].u8()
            val glyph = glyphByCode(glyphAgeHealthBits and 0x3)
            val age = ageByCode((glyphAgeHealthBits ushr 2) and 0x3)
            val sourceHealth = sourceHealthByCode((glyphAgeHealthBits ushr 4) and 0x7)
            require(glyphAgeHealthBits and 0x80 == 0)
            val directionBin = bytes[offset + 4].u8()
            require(bytes[offset + 5].u8() == 0)
            val lineageCount = u16(bytes, offset + 6)
            val semanticGeneration = u32(bytes, offset + 8)
            val styleGeneration = u32(bytes, offset + 12)
            return CoverageRendererStyleRowV1(
                semanticGeneration = semanticGeneration,
                styleGeneration = styleGeneration,
                semantic = semantic,
                coverage = coverage,
                palette = palette,
                cut = cut,
                residency = residency,
                target = target,
                directionBin = directionBin,
                glyph = glyph,
                lineageCount = lineageCount,
                age = age,
                sourceHealth = sourceHealth,
            )
        }

        private fun semanticByCode(code: Int): CoverageRendererSemantic = when (code) {
            0 -> CoverageRendererSemantic.CONFIRMED
            1 -> CoverageRendererSemantic.AMBIGUOUS
            2 -> CoverageRendererSemantic.SUPPRESSED_DEBUG
            else -> reservedStyleCode(code)
        }

        private fun coverageByCode(code: Int): CoverageRendererCoverage = when (code) {
            0 -> CoverageRendererCoverage.UNCOVERED
            1 -> CoverageRendererCoverage.PARTIAL
            2 -> CoverageRendererCoverage.COMPLETE
            else -> reservedStyleCode(code)
        }

        private fun paletteByCode(code: Int): CoverageRendererPalette = when (code) {
            0 -> CoverageRendererPalette.UNIFORM
            1 -> CoverageRendererPalette.COVERAGE
            2 -> CoverageRendererPalette.NORMAL
            3 -> CoverageRendererPalette.OCCUPANCY
            4 -> CoverageRendererPalette.LINEAGE
            5 -> CoverageRendererPalette.AGE
            6 -> CoverageRendererPalette.SOURCE_HEALTH
            7 -> CoverageRendererPalette.RESIDENCY
            8 -> CoverageRendererPalette.DIRECTION
            else -> reservedStyleCode(code)
        }

        private fun cutByCode(code: Int): CoverageRendererCut = when (code) {
            0 -> CoverageRendererCut.EXACT_CURRENT
            1 -> CoverageRendererCut.STALE_DISPLAY
            2 -> CoverageRendererCut.LOWER_BOUND
            3 -> CoverageRendererCut.COVERAGE_PENDING
            4 -> CoverageRendererCut.INDETERMINATE_HISTORY
            5 -> CoverageRendererCut.UNAVAILABLE
            else -> reservedStyleCode(code)
        }

        private fun residencyByCode(code: Int): CoverageRendererResidency = when (code) {
            0 -> CoverageRendererResidency.ACTIVE_L0
            1 -> CoverageRendererResidency.WARM_L1
            2 -> CoverageRendererResidency.COLD_L2
            else -> reservedStyleCode(code)
        }

        private fun targetByCode(code: Int): CoverageRendererTarget = when (code) {
            0 -> CoverageRendererTarget.NONE
            1 -> CoverageRendererTarget.PRIMARY
            2 -> CoverageRendererTarget.HALO
            else -> reservedStyleCode(code)
        }

        private fun glyphByCode(code: Int): CoverageRendererGlyph = when (code) {
            0 -> CoverageRendererGlyph.NONE
            1 -> CoverageRendererGlyph.NORMAL
            2 -> CoverageRendererGlyph.DESIRED_DIRECTION
            3 -> CoverageRendererGlyph.VIEW_ROSE
            else -> reservedStyleCode(code)
        }

        private fun ageByCode(code: Int): CoverageRendererAge = when (code) {
            0 -> CoverageRendererAge.FRESH
            1 -> CoverageRendererAge.RECENT
            2 -> CoverageRendererAge.AGING
            3 -> CoverageRendererAge.OLD
            else -> reservedStyleCode(code)
        }

        private fun sourceHealthByCode(code: Int): CoverageRendererSourceHealth = when (code) {
            0 -> CoverageRendererSourceHealth.HEALTHY
            1 -> CoverageRendererSourceHealth.FEATURE_ONLY
            2 -> CoverageRendererSourceHealth.TRANSIENT_UNAVAILABLE
            3 -> CoverageRendererSourceHealth.FAILED
            4 -> CoverageRendererSourceHealth.UNSUPPORTED
            else -> reservedStyleCode(code)
        }

        private fun reservedStyleCode(code: Int): Nothing =
            throw IllegalArgumentException("Reserved renderer style enum code $code")
    }
}

private fun Byte.u8(): Int = toInt() and 0xff

private fun u16(bytes: ByteArray, offset: Int): Int =
    bytes[offset].u8() or (bytes[offset + 1].u8() shl 8)

private fun u32(bytes: ByteArray, offset: Int): Long =
    bytes[offset].u8().toLong() or
        (bytes[offset + 1].u8().toLong() shl 8) or
        (bytes[offset + 2].u8().toLong() shl 16) or
        (bytes[offset + 3].u8().toLong() shl 24)

private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

enum class VoxelRenderMode(val wireName: String) {
    POINTS("points"),
    CENTROIDS("centroids"),
    CUBES("cubes"),
    ;

    companion object {
        fun fromWire(value: String): VoxelRenderMode =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException(
                    "voxelRenderMode must be points, centroids, or cubes",
                )
    }
}

data class PointCloudNativeConfig(
    val renderCapacity: Int = 100_000,
    val pointSizePx: Float = 6f,
    val enabled: Boolean = true,
    val voxelRenderMode: VoxelRenderMode = VoxelRenderMode.POINTS,
    val voxelSizeMeters: Float = 0.1f,
    val cubeSizeFactor: Float = 1f,
    /** Identifies one requested Compose renderer resource generation. */
    val rendererGeneration: Long = 0L,
) {
    init {
        require(renderCapacity in 1..100_000)
        require(pointSizePx.isFinite() && pointSizePx > 0f)
        require(voxelSizeMeters.isFinite() && voxelSizeMeters > 0f)
        require(cubeSizeFactor.isFinite() && cubeSizeFactor in 0.1f..1f)
        require(rendererGeneration >= 0L)
    }
}

data class CoveragePointRenderSnapshot(
    val revision: Long,
    val enabled: Boolean,
    val capacity: Int,
    val count: Int,
    val keys: LongArray,
    /** Stable canonical surface identities; legacy raw snapshots use keys. */
    val surfaceIds: LongArray = keys.copyOf(),
    val positions: FloatArray,
    val colors: IntArray,
    val styleRows: ByteArray = ByteArray(0),
    val gridRotationWorld: FloatArray = identityGridRotation(),
    val update: CoveragePointRenderUpdate? = null,
    /** V2 canonical qualifiers; legacy/raw snapshots leave these at zero. */
    val bindingGeneration: Long = 0L,
    val groupGeneration: Long = 0L,
    val transactionId: Long = 0L,
    val geometryRevision: Long = 0L,
    val styleRevision: Long = 0L,
    /** Renderer-local palette epoch; changes even when canonical geometry is unchanged. */
    val paletteRevision: Long = 0L,
) {
    init {
        require(bindingGeneration >= 0L)
        require(groupGeneration >= 0L)
        require(transactionId >= 0L)
        require(geometryRevision >= 0L)
        require(styleRevision >= 0L)
        require(paletteRevision >= 0L)
        require(surfaceIds.size == count)
        require(styleRows.isEmpty() || styleRows.size == count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
    }
}

/**
 * Qualifier for a synchronous borrow from the canonical visibility renderer.
 * A borrower must reject the view when any field no longer names the
 * committed cut that the consumer installed.
 */
data class CoverageRowsQualifier(
    val bindingGeneration: Long,
    val groupGeneration: Long,
    val rendererGeneration: Long,
    val transactionId: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
) {
    init {
        require(bindingGeneration >= 0L)
        require(groupGeneration >= 0L)
        require(rendererGeneration >= 0L)
        require(transactionId >= 0L)
        require(geometryRevision >= 0L)
        require(styleRevision >= 0L)
    }
}

/** One row borrowed from canonical state; it is valid only during the borrow callback. */
data class CoverageCommittedRow(
    val surfaceId: Long,
    val key: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val color: Int,
    val style: CoverageRendererStyleRowV1,
)

/**
 * Synchronous, non-owning view over canonical rows. Implementations must not
 * retain the view or rows after [CoverageCommittedRowsBorrower] returns.
 */
interface CoverageCommittedRows {
    val count: Int
    val capacity: Int
    val qualifier: CoverageRowsQualifier
    fun rowAt(index: Int): CoverageCommittedRow

    // Compatibility borrowers may materialize rowAt; production overrides these
    // scalar/copy reads without creating rows, styles or coordinate arrays.
    fun surfaceIdAt(index: Int): Long = rowAt(index).surfaceId
    fun keyAt(index: Int): Long = rowAt(index).key
    fun positionComponentAt(index: Int, component: Int): Float {
        require(component in 0..2)
        val row = rowAt(index)
        return when (component) { 0 -> row.x; 1 -> row.y; else -> row.z }
    }
    fun colorAt(index: Int): Int = rowAt(index).color
    /** Validated V1 semantic/coverage/residency/target bit fields (encoded byte1). */
    fun styleFlagsAt(index: Int): Int = rowAt(index).style.let {
        it.semantic.code or (it.coverage.code shl 2) or (it.residency.code shl 4) or (it.target.code shl 6)
    }
    fun copyStyleAt(index: Int, destination: ByteArray, offset: Int) {
        rowAt(index).style.encodeInto(destination, offset)
    }
    /** Zero confidence explicitly marks unavailable canonical normal metadata. */
    fun packedNormalAt(index: Int): Int { require(index in 0 until count); return 0 }
    fun normalConfidenceAt(index: Int): Int { require(index in 0 until count); return 0 }

    /**
     * Visits canonical rows without creating a source-sized collection.  The
     * default keeps test and compatibility borrowers source-compatible while
     * allowing the production projection to stream a 100k source cut through
     * a bounded presentation selector.
     */
    fun forEachRow(block: (CoverageCommittedRow) -> Unit) {
        repeat(count) { index -> block(rowAt(index)) }
    }

    /** Optional qualifier-matched dirty ranges; values never cross this seam. */
    val update: CoveragePointRenderUpdate?
        get() = null
}

/**
 * State-only renderer seam. The callback runs while the authoritative state
 * is fenced; it receives no full source snapshot and cannot outlive the call.
 */
fun interface CoverageCommittedRowsBorrower {
    fun withCommittedRows(
        expected: CoverageRowsQualifier,
        block: (CoverageCommittedRows) -> Unit,
    ): Boolean
}

/**
 * Authoritative immutable hand-off copy for renderer consumers.  All nested
 * buffers, including dirty spans, are detached from the producer snapshot.
 */
internal fun CoveragePointRenderSnapshot.deepCopy(): CoveragePointRenderSnapshot = copy(
    keys = keys.copyOf(),
    surfaceIds = surfaceIds.copyOf(),
    positions = positions.copyOf(),
    colors = colors.copyOf(),
    styleRows = styleRows.copyOf(),
    gridRotationWorld = gridRotationWorld.copyOf(),
    update = update?.copy(
        spans = update.spans.map { span ->
            span.copy(
                positions = span.positions.copyOf(),
                colors = span.colors.copyOf(),
                styleRows = span.styleRows.copyOf(),
            )
        },
    ),
)

/** Immutable renderer-plan copy: one bounded row buffer plus range-only dirties. */
internal fun CoveragePointRenderSnapshot.deepCopyWithoutSpanValues(): CoveragePointRenderSnapshot = copy(
    keys = keys.copyOf(),
    surfaceIds = surfaceIds.copyOf(),
    positions = positions.copyOf(),
    colors = colors.copyOf(),
    styleRows = styleRows.copyOf(),
    gridRotationWorld = gridRotationWorld.copyOf(),
    update = update?.copy(spans = update.spans.map { it.rangeOnly() }),
)

/**
 * Rewrites palette-derived buffers without changing presentation slots or
 * dirty-span membership.  The caller owns the returned immutable snapshot.
 */
internal fun CoveragePointRenderSnapshot.rewritePaletteBuffers(
    palette: CoverageRendererPalette,
    fullSpanOnPaletteChange: Boolean = false,
): CoveragePointRenderSnapshot {
    val recolored = recolorStyleBuffers(styleRows, colors, palette)
    val styledRows = recolored.styleRows
    val styledColors = recolored.colors
    val styledSpans = update?.let { sourceUpdate ->
        if (fullSpanOnPaletteChange) {
            if (count == 0) {
                emptyList()
            } else {
                listOf(
                    CoveragePointSpan(
                        startSlot = 0,
                        positions = positions.copyOf(),
                        colors = styledColors.copyOf(),
                        styleRows = styledRows.copyOf(),
                    ),
                )
            }
        } else {
            sourceUpdate.spans.map { span ->
                val spanRecolored = recolorStyleBuffers(span.styleRows, span.colors, palette)
                span.copy(
                    colors = spanRecolored.colors,
                    styleRows = spanRecolored.styleRows,
                )
            }
        }
    }
    return copy(
        colors = styledColors,
        styleRows = styledRows,
        update = update?.copy(spans = styledSpans.orEmpty()),
    )
}

private data class RecoloredStyleBuffers(
    val styleRows: ByteArray,
    val colors: IntArray,
)

/** Recolors one immutable style/color buffer pair without mutating its owner. */
private fun recolorStyleBuffers(
    styleRows: ByteArray,
    colors: IntArray,
    palette: CoverageRendererPalette,
): RecoloredStyleBuffers {
    val recoloredRows = styleRows.copyOf()
    val recoloredColors = colors.copyOf()
    if (recoloredRows.isEmpty()) return RecoloredStyleBuffers(recoloredRows, recoloredColors)
    require(recoloredRows.size == recoloredColors.size * COVERAGE_RENDERER_STYLE_ROW_BYTES)
    repeat(recoloredColors.size) { index ->
        val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
        val style = CoverageRendererStyleRowV1.decode(recoloredRows, offset).copy(palette = palette)
        style.encodeInto(recoloredRows, offset)
        recoloredColors[index] = style.packedColor()
    }
    return RecoloredStyleBuffers(recoloredRows, recoloredColors)
}

/** Compound mesh upload identity; palette recolors must not be deduplicated. */
internal data class CoveragePointUploadQualifier(
    val revision: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
    val paletteRevision: Long,
)

internal fun CoveragePointRenderSnapshot.uploadQualifier(): CoveragePointUploadQualifier =
    CoveragePointUploadQualifier(
        revision = revision,
        geometryRevision = geometryRevision,
        styleRevision = styleRevision,
        paletteRevision = paletteRevision,
    )

fun identityGridRotation(): FloatArray = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f,
)

data class CoveragePointSpan(
    val startSlot: Int,
    val positions: FloatArray,
    val colors: IntArray,
    val styleRows: ByteArray = ByteArray(0),
    /** Exclusive range end for immutable range-only dirty spans. */
    val endSlotExclusive: Int = startSlot + colors.size,
) {
    init {
        require(startSlot >= 0)
        require(endSlotExclusive >= startSlot)
        require(endSlotExclusive == startSlot + colors.size || colors.isEmpty())
        require(
            styleRows.isEmpty() ||
                styleRows.size == colors.size * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
    }

    val rowCount: Int get() = endSlotExclusive - startSlot

    fun rangeOnly(): CoveragePointSpan = CoveragePointSpan(
        startSlot = startSlot,
        positions = FloatArray(0),
        colors = IntArray(0),
        endSlotExclusive = endSlotExclusive,
    )
}

data class CoveragePointRenderUpdate(
    val geometryRevision: Long,
    val visibilityRevision: Long,
    val enabled: Boolean,
    val count: Int,
    val spans: List<CoveragePointSpan>,
    val reset: Boolean,
)

/** Drops mutable span payloads while preserving the authoritative dirty ranges. */
internal fun CoveragePointRenderUpdate.rangeOnly(): CoveragePointRenderUpdate = copy(
    spans = spans.map { it.rangeOnly() },
)
