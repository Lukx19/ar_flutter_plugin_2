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
    val enabled: Boolean,
)

/**
 * Shared descriptor-to-page state machine for the point and cube meshes.
 *
 * A replacement descriptor supersedes only queued work.  A page already
 * handed to a coordinator remains owned until its opaque ticket is released;
 * this is what keeps a late callback from clearing a newer descriptor's
 * in-flight state.  Full work is reserved for a baseline, reset, rehydration,
 * mode, or palette transition, and for every successor whose predecessor is
 * not fully complete.  Other updates become sorted, merged,
 * descriptor-count-clipped range-only pages.
 */
internal class CoverageDescriptorPageSequencer {
    private val rendererThread = Thread.currentThread()

    private fun checkRendererThread() {
        check(Thread.currentThread() === rendererThread) {
            "Coverage descriptor pages belong to their renderer thread"
        }
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

    private data class InFlightPage(
        val work: PageWork,
        val ticket: CoverageDescriptorPageTicket,
    )

    private var currentDescriptor: BoundedCoveragePresentation? = null
    private var completedDescriptor: BoundedCoveragePresentation? = null
    private var failedDescriptor: BoundedCoveragePresentation? = null
    private val pendingPages = ArrayDeque<PageWork>()
    private var inFlightPage: InFlightPage? = null
    // Legacy release permits cube delta sequencing, but supplies no GPU success proof.
    private var uploadProof = UPLOAD_UNPROVEN
    private var nextTicketSerial = 0L
    private val pageDestination = CoveragePresentationPage.allocate(MAX_ROWS_PER_PAGE)

    /** Replaces queued work while preserving any page already in flight. */
    fun replace(
        descriptor: BoundedCoveragePresentation?,
        rehydrate: Boolean = false,
    ) {
        checkRendererThread()
        val previous = currentDescriptor
        val predecessorComplete = previous != null &&
            previous === completedDescriptor &&
            previous !== failedDescriptor
        val previousUploadSucceeded = predecessorComplete && uploadProof == DESCRIPTOR_SUCCEEDED
        currentDescriptor = descriptor
        completedDescriptor = null
        uploadProof = PAGES_SUCCEEDED
        failedDescriptor = null
        pendingPages.clear()
        if (descriptor == null || !descriptor.enabled) { uploadProof = UPLOAD_UNPROVEN; return }

        val full = previous == null ||
            !predecessorComplete ||
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
                val end = minOf(start + MAX_ROWS_PER_PAGE, range.endSlotExclusive)
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
        if (ranges.isEmpty()) {
            completedDescriptor = descriptor
            uploadProof = if (descriptor.count == 0 || previousUploadSucceeded) DESCRIPTOR_SUCCEEDED else UPLOAD_UNPROVEN
        }
    }

    /** Clears queued work; an already submitted page still owns its ticket. */
    fun clear() {
        checkRendererThread()
        currentDescriptor = null
        completedDescriptor = null
        uploadProof = UPLOAD_UNPROVEN
        failedDescriptor = null
        pendingPages.clear()
    }

    /** Returns the next page only when no previous page is still owned. */
    fun nextPage(): CoverageDescriptorPageSubmission? {
        checkRendererThread()
        if (inFlightPage != null) return null
        while (pendingPages.isNotEmpty()) {
            val work = pendingPages.removeFirst()
            if (work.descriptor !== currentDescriptor) continue
            val accepted = work.descriptor.withPageInto(
                expected = work.descriptor.qualifier,
                startSlot = work.startSlot,
                maximumRows = minOf(MAX_ROWS_PER_PAGE, work.endSlotExclusive - work.startSlot),
                destination = pageDestination,
            )
            if (!accepted || pageDestination.startSlot + pageDestination.count != work.endSlotExclusive) {
                failedDescriptor = work.descriptor
                uploadProof = UPLOAD_UNPROVEN
                pendingPages.clear()
                completedDescriptor = null
                return null
            }
            val ticket = CoverageDescriptorPageTicket(++nextTicketSerial)
            inFlightPage = InFlightPage(work, ticket)
            return CoverageDescriptorPageSubmission(ticket, pageDestination, work.reset, work.descriptor.enabled)
        }
        return null
    }

    /** Legacy cube drain/delta sequencing; this is never a successful GPU receipt. */
    fun release(ticket: CoverageDescriptorPageTicket) = settle(ticket, successful = null)

    /** Settles the exact point page only after all its accepted GPU lanes consumed storage. */
    fun finishUpload(ticket: CoverageDescriptorPageTicket, successful: Boolean) = settle(ticket, successful)

    private fun settle(ticket: CoverageDescriptorPageTicket, successful: Boolean?) {
        checkRendererThread()
        val inFlight = inFlightPage ?: return
        if (ticket !== inFlight.ticket) return
        inFlightPage = null
        if (currentDescriptor !== inFlight.work.descriptor) return
        if (successful != true) uploadProof = UPLOAD_UNPROVEN
        if (successful == false) {
            failedDescriptor = inFlight.work.descriptor
            completedDescriptor = null
            pendingPages.clear()
            return
        }
        if (failedDescriptor !== inFlight.work.descriptor && pendingPages.isEmpty()) {
            completedDescriptor = inFlight.work.descriptor
            if (uploadProof == PAGES_SUCCEEDED) uploadProof = DESCRIPTOR_SUCCEEDED
        }
    }

    fun isCurrent(descriptor: BoundedCoveragePresentation): Boolean {
        checkRendererThread()
        return currentDescriptor === descriptor
    }

    fun owns(ticket: CoverageDescriptorPageTicket): Boolean {
        checkRendererThread()
        return inFlightPage?.ticket === ticket
    }

    /** Exact descriptor identity includes its immutable selection/control/attribute view. */
    fun isSuccessfullyComplete(descriptor: BoundedCoveragePresentation): Boolean =
        isComplete(descriptor) && uploadProof == DESCRIPTOR_SUCCEEDED

    val hasInFlightPage: Boolean
        get() {
            checkRendererThread()
            return inFlightPage != null
        }

    fun isComplete(descriptor: BoundedCoveragePresentation): Boolean {
        checkRendererThread()
        return currentDescriptor === descriptor && completedDescriptor === descriptor &&
            failedDescriptor !== descriptor && inFlightPage == null && pendingPages.isEmpty()
    }

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
        private const val UPLOAD_UNPROVEN = 0
        private const val PAGES_SUCCEEDED = 1
        private const val DESCRIPTOR_SUCCEEDED = 2
        const val MAX_ROWS_PER_PAGE = 512
    }
}
