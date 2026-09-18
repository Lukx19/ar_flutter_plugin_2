package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan

/**
 * Opaque identity for one page that has been handed to a mesh upload
 * coordinator.  A ticket is intentionally not a revision number: only the
 * sequencer which issued it can decide whether a release belongs to its
 * current in-flight page.
 */
internal class CoverageDescriptorPageTicket internal constructor(
    private val serial: Long,
)

/** One bounded page admission for a point or cube upload coordinator. */
internal data class CoverageDescriptorPageSubmission(
    val ticket: CoverageDescriptorPageTicket,
    val page: CoveragePresentationPage,
    val reset: Boolean,
)

/**
 * Shared descriptor-to-page state machine for the point and cube meshes.
 *
 * A replacement descriptor supersedes only queued work.  A page already
 * handed to a coordinator remains owned until its opaque ticket is released;
 * this is what keeps a late callback from clearing a newer descriptor's
 * in-flight state.  Full work is reserved for a baseline, reset, rehydration,
 * mode, or palette transition.  Other updates become sorted, merged,
 * descriptor-count-clipped range-only pages.
 */
internal class CoverageDescriptorPageSequencer(
    private val maximumRowsPerPage: Int = MAX_ROWS_PER_PAGE,
) {
    init {
        require(maximumRowsPerPage in 1..MAX_ROWS_PER_PAGE)
    }

    private data class PageWork(
        val descriptor: BoundedCoveragePresentation,
        val startSlot: Int,
        val endSlotExclusive: Int,
        val reset: Boolean,
    )

    private data class Range(
        val startSlot: Int,
        val endSlotExclusive: Int,
    )

    private var currentDescriptor: BoundedCoveragePresentation? = null
    private val pendingPages = ArrayDeque<PageWork>()
    private var inFlightTicket: CoverageDescriptorPageTicket? = null
    private var nextTicketSerial = 0L

    /** Replaces queued work while preserving any page already in flight. */
    fun replace(
        descriptor: BoundedCoveragePresentation?,
        rehydrate: Boolean = false,
    ) {
        val previous = currentDescriptor
        currentDescriptor = descriptor
        pendingPages.clear()
        if (descriptor == null) return

        val full = previous == null ||
            rehydrate ||
            descriptor.mode != previous.mode ||
            descriptor.palette != previous.palette ||
            descriptor.paletteEpoch != previous.paletteEpoch ||
            descriptor.update?.reset == true
        val ranges = if (full) {
            if (descriptor.count == 0) emptyList() else listOf(Range(0, descriptor.count))
        } else {
            mergeClippedRanges(descriptor.update?.spans.orEmpty(), descriptor.count)
        }
        ranges.forEachIndexed { index, range ->
            var start = range.startSlot
            while (start < range.endSlotExclusive) {
                val end = minOf(start + maximumRowsPerPage, range.endSlotExclusive)
                pendingPages.addLast(
                    PageWork(
                        descriptor = descriptor,
                        startSlot = start,
                        endSlotExclusive = end,
                        reset = full && index == 0 && start == range.startSlot,
                    ),
                )
                start = end
            }
        }
    }

    /** Clears queued work; an already submitted page still owns its ticket. */
    fun clear() {
        currentDescriptor = null
        pendingPages.clear()
    }

    /** Returns the next page only when no previous page is still owned. */
    fun nextPage(): CoverageDescriptorPageSubmission? {
        if (inFlightTicket != null) return null
        while (pendingPages.isNotEmpty()) {
            val work = pendingPages.removeFirst()
            if (work.descriptor !== currentDescriptor) continue
            var page: CoveragePresentationPage? = null
            val accepted = work.descriptor.withPage(
                expected = work.descriptor.qualifier,
                startSlot = work.startSlot,
                maximumRows = minOf(maximumRowsPerPage, work.endSlotExclusive - work.startSlot),
            ) { borrowed ->
                val end = minOf(work.endSlotExclusive, borrowed.startSlot + borrowed.count)
                page = if (end == work.endSlotExclusive) borrowed else null
            }
            if (!accepted || page == null) continue
            val ticket = CoverageDescriptorPageTicket(++nextTicketSerial)
            inFlightTicket = ticket
            return CoverageDescriptorPageSubmission(ticket, page!!, work.reset)
        }
        return null
    }

    /** Releases only the currently owned page represented by [ticket]. */
    fun release(ticket: CoverageDescriptorPageTicket) {
        if (ticket === inFlightTicket) inFlightTicket = null
    }

    val hasInFlightPage: Boolean
        get() = inFlightTicket != null

    val pendingPageCount: Int
        get() = pendingPages.size

    private fun mergeClippedRanges(
        spans: List<CoveragePointSpan>,
        count: Int,
    ): List<Range> {
        if (count == 0) return emptyList()
        val sorted = spans.mapNotNull { span ->
            val start = span.startSlot.coerceAtLeast(0)
            val end = span.endSlotExclusive.coerceAtMost(count)
            if (start < end) Range(start, end) else null
        }.sortedWith(compareBy<Range> { it.startSlot }.thenBy { it.endSlotExclusive })
        if (sorted.isEmpty()) return emptyList()
        val merged = ArrayList<Range>(sorted.size)
        var current = sorted.first()
        sorted.drop(1).forEach { next ->
            if (next.startSlot <= current.endSlotExclusive) {
                current = current.copy(
                    endSlotExclusive = maxOf(current.endSlotExclusive, next.endSlotExclusive),
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    internal companion object {
        const val MAX_ROWS_PER_PAGE = 512
    }
}
