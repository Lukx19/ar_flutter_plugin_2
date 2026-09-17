package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

data class RendererStyleCommandApplyResultV1(val acceptedStyleRevision: Long) {
    init { require(acceptedStyleRevision >= 0) }
}

/**
 * Bounded staging for one authenticated binding's renderer-style pages.
 *
 * Staging is deliberately keyed by binding identity, group generation, and
 * style revision.  A replacement binding therefore cannot complete a cut
 * admitted by its predecessor, even when the page ordinals happen to match.
 */
class RendererStyleCommandStagingV1(
    bindingIdentity: ByteArray,
    private val maximumRows: Int = RendererStyleCommandV1.MAX_ROWS,
    private val clockNanos: () -> Long = System::nanoTime,
    private val timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS,
) {
    init {
        require(maximumRows in 1..RendererStyleCommandV1.MAX_ROWS)
        require(timeoutNanos > 0) { "timeoutNanos must be positive" }
    }

    private val bindingKey = bindingIdentity.copyOf().asList()
    private val stages = linkedMapOf<Key, Stage>()

    sealed interface Result {
        data class Progress(val key: Key, val nextPageIndex: Int, val stagedRows: Int) : Result
        data class Complete(val key: Key, val cut: RendererStyleCutPayloadV1) : Result
    }

    data class Key(
        internal val bindingIdentity: List<Byte>,
        internal val captureGroupId: List<Byte>,
        val bindingGeneration: Long,
        val groupGeneration: Long,
        val styleRevision: Long,
    )

    fun accept(page: RendererStyleCommandV1.Page): Result {
        val nowNanos = clockNanos()
        expire(nowNanos)
        require(page.kind == RendererStyleCommandV1.KIND)
        require(page.totalRows <= maximumRows) { "Renderer-style cut exceeds the staging row limit" }
        val key = Key(
            bindingIdentity = bindingKey,
            captureGroupId = page.captureGroupId.asList(),
            bindingGeneration = page.bindingGeneration,
            groupGeneration = page.groupGeneration,
            styleRevision = page.styleRevision,
        )
        val stage = if (page.pageIndex == 0) {
            require(stages.isEmpty()) { "Renderer-style binding already has an active cut" }
            Stage.from(page, maximumRows, key, nowNanos).also { stages[key] = it }
        } else {
            require(page.pageIndex > 0) { "Renderer-style page index is invalid" }
            stages[key] ?: throw IllegalArgumentException("Renderer-style page has no active staging")
        }
        try {
            stage.accept(page)
            if (!page.isFinal) {
                return Result.Progress(key, stage.nextPageIndex, stage.stagedRows)
            }
            val cut = stage.finish()
            stages.remove(key)
            return Result.Complete(key, cut)
        } catch (error: Exception) {
            stages.remove(key)
            throw error
        }
    }

    fun clear() {
        stages.clear()
    }

    fun clear(groupGeneration: Long, styleRevision: Long) {
        stages.keys.removeIf { it.groupGeneration == groupGeneration && it.styleRevision == styleRevision }
    }

    fun stagedCutCount(): Int = stages.size

    /** Retained row capacity, including rows reserved for pages not received yet. */
    fun stagedRowCapacity(): Int = stages.values.sumOf(Stage::totalRows)

    /** Number of authenticated pages currently retained across incomplete cuts. */
    fun stagedPageCount(): Int = stages.values.sumOf(Stage::nextPageIndex)

    /** Admission check that does not mutate or retain the page. */
    fun canAccept(page: RendererStyleCommandV1.Page, committedStyleRevision: Long): Boolean {
        expire(clockNanos())
        val key = Key(
            bindingIdentity = bindingKey,
            captureGroupId = page.captureGroupId.asList(),
            bindingGeneration = page.bindingGeneration,
            groupGeneration = page.groupGeneration,
            styleRevision = page.styleRevision,
        )
        val nextRevision = committedStyleRevision.takeIf { it < Long.MAX_VALUE }?.plus(1L)
        return if (page.pageIndex == 0) {
            page.styleRevision == nextRevision && stages.isEmpty()
        } else {
            page.styleRevision == nextRevision &&
                stages.containsKey(key)
        }
    }

    private fun expire(nowNanos: Long) {
        stages.entries.removeIf { (_, stage) ->
            nowNanos - stage.startedAtNanos >= timeoutNanos
        }
    }

    private class Stage private constructor(
        private val key: Key,
        private val flags: Int,
        private val transactionId: Long,
        private val geometryRevision: Long,
        private val lineageRevision: Long,
        private val semanticRevision: Long,
        private val coverageRevision: Long,
        private val residencyRevision: Long,
        private val targetRevision: Long,
        private val pageCount: Int,
        val totalRows: Int,
        private val targetSurfaceId: Long?,
        private val targetDirectionIndex: Int?,
        private val completeDigest: ByteArray,
        private val ids: LongArray,
        private val styles: ByteArray,
        val startedAtNanos: Long,
    ) {
        var nextPageIndex: Int = 0
            private set
        var stagedRows: Int = 0
            private set

        fun accept(page: RendererStyleCommandV1.Page) {
            require(page.groupGeneration == key.groupGeneration && page.styleRevision == key.styleRevision)
            require(page.bindingGeneration == key.bindingGeneration)
            require(page.captureGroupId.asList() == key.captureGroupId)
            require(page.pageIndex == nextPageIndex) { "Renderer-style page ordinal is not adjacent" }
            require(page.pageCount == pageCount && page.totalRows == totalRows)
            require(page.flags and RendererStyleCommandV1.RESET_FLAG == flags)
            require(page.transactionId == transactionId)
            require(page.geometryRevision == geometryRevision)
            require(page.lineageRevision == lineageRevision)
            require(page.semanticRevision == semanticRevision)
            require(page.coverageRevision == coverageRevision)
            require(page.residencyRevision == residencyRevision)
            require(page.targetRevision == targetRevision)
            require(page.targetSurfaceId == targetSurfaceId)
            require(page.targetDirectionIndex == targetDirectionIndex)
            require(page.completeDigest.contentEquals(completeDigest))
            require(stagedRows + page.rowCount <= totalRows)
            if (page.rowCount > 0 && stagedRows > 0) {
                require(ids[stagedRows - 1] < page.surfaceIds.first()) {
                    "Renderer-style stable IDs must ascend across pages"
                }
            }
            page.surfaceIds.copyInto(ids, stagedRows)
            page.styleRows.copyInto(styles, stagedRows * RendererStyleCommandV1.STYLE_ROW_BYTES)
            stagedRows += page.rowCount
            nextPageIndex++
            require(nextPageIndex <= pageCount)
        }

        fun finish(): RendererStyleCutPayloadV1 {
            require(nextPageIndex == pageCount && stagedRows == totalRows) {
                "Renderer-style final page does not complete the cut"
            }
            val cut = RendererStyleCutPayloadV1(
                groupGeneration = key.groupGeneration,
                captureGroupId = key.captureGroupId.toByteArray(),
                bindingGeneration = key.bindingGeneration,
                transactionId = transactionId,
                geometryRevision = geometryRevision,
                lineageRevision = lineageRevision,
                semanticRevision = semanticRevision,
                coverageRevision = coverageRevision,
                styleRevision = key.styleRevision,
                residencyRevision = residencyRevision,
                targetRevision = targetRevision,
                reset = flags and RendererStyleCommandV1.RESET_FLAG != 0,
                surfaceIds = ids.copyOf(stagedRows),
                styleRows = styles.copyOf(stagedRows * RendererStyleCommandV1.STYLE_ROW_BYTES),
                targetSurfaceId = targetSurfaceId,
                targetDirectionIndex = targetDirectionIndex,
            )
            require(cut.completeDigest().contentEquals(completeDigest)) {
                "Renderer-style complete-cut digest is invalid"
            }
            return cut
        }

        companion object {
            fun from(
                page: RendererStyleCommandV1.Page,
                maximumRows: Int,
                key: Key,
                startedAtNanos: Long,
            ): Stage {
                require(page.pageIndex == 0)
                require(page.totalRows <= maximumRows)
                return Stage(
                    key = key,
                    flags = page.flags and RendererStyleCommandV1.RESET_FLAG,
                    transactionId = page.transactionId,
                    geometryRevision = page.geometryRevision,
                    lineageRevision = page.lineageRevision,
                    semanticRevision = page.semanticRevision,
                    coverageRevision = page.coverageRevision,
                    residencyRevision = page.residencyRevision,
                    targetRevision = page.targetRevision,
                    pageCount = page.pageCount,
                    totalRows = page.totalRows,
                    targetSurfaceId = page.targetSurfaceId,
                    targetDirectionIndex = page.targetDirectionIndex,
                    completeDigest = page.completeDigest.copyOf(),
                    ids = LongArray(page.totalRows),
                    styles = ByteArray(page.totalRows * RendererStyleCommandV1.STYLE_ROW_BYTES),
                    startedAtNanos = startedAtNanos,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_NANOS = 2_000_000_000L
    }
}
