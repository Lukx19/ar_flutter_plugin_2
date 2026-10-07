package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Borrowed only during one serial invocation. No descriptor is retained by selection. */
internal class RendererAttributeBasis(
    val style: ByteArray, val rendererGeneration: Long, val shape: Int, val color: Int,
    val count: Int, val paletteRevision: Long = 0, val surfaceIdAt: (Int) -> Long,
)

/** Immutable complete attributes. Upload leases keep retired tables charged. */
internal class RendererAttributeTable(
    val header: RendererAttributeCodec.Header,
    val rows: ByteArray,
) {
    private val readers = AtomicInteger()
    val held: Boolean get() = readers.get() != 0
    fun hold() { readers.incrementAndGet() }
    fun release() { check(readers.decrementAndGet() >= 0) }
    fun acquire(): AutoCloseable {
        hold()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) release() }
    }
    fun rowOffset(id: Long): Int {
        val data = ByteBuffer.wrap(rows).order(ByteOrder.LITTLE_ENDIAN)
        var low = 0; var high = rows.size / 24 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val found = data.getLong(middle * 24)
            if (found == id) return middle * 24
            if (found < id) low = middle + 1 else high = middle - 1
        }
        return -1
    }
    val portableBytes: Long get() = rows.size.toLong() + 256L + 64L
}

/**
 * One selection and at most two active/staging/retired tables. Pages are copied
 * straight into the single candidate; no page list or canonical source cache.
 * The fixed 561152-byte modeled allowance includes peer bytes and transport,
 * not measured heap. Admission additionally reserves the concrete shape model.
 */
internal class RendererAttributeOwner(
    private val basis: () -> RendererAttributeBasis?,
    private val admit: (Long) -> Boolean,
    private val attach: (RendererAttributeTable) -> Boolean,
) {
    private var generation = 0L
    private var selection: RendererAttributeCodec.Header? = null
    private var selectedIds: LongArray? = null
    private var selectionPaletteRevision = 0L
    private var candidate: ByteArray? = null
    private var candidateHeader: RendererAttributeCodec.Header? = null
    private var received = 0
    private var nextPage = 0
    private var active: RendererAttributeTable? = null
    private var activeValid = false
    private var retired: RendererAttributeTable? = null
    @Volatile private var lastOutcome = "unavailable"
    @Volatile var requestedRows = 0
        private set
    @Volatile var fundedRows = 0
        private set
    @Volatile var fundedModeledBytes = 0L
        private set
    fun currentOutcome(): String = lastOutcome
    private var maxTables = 0
    private var accepted = 0L
    private var refused = 0L
    private var invalidations = 0L

    @Synchronized fun exchange(bytes: ByteArray): Any = try {
        val (header, raw) = RendererAttributeCodec.decode(bytes)
        require(header.operation != RendererAttributeCodec.SELECT || raw.size == RendererAttributeCodec.HEADER_BYTES)
        when (header.operation) {
            RendererAttributeCodec.SELECT -> select(header)
            RendererAttributeCodec.PAGE -> page(header, raw)
            RendererAttributeCodec.COMMIT -> commit(header)
            RendererAttributeCodec.STATUS -> status(header)
            RendererAttributeCodec.CANCEL -> {
                if (selection?.sameSelection(header) != true) outcome("selectionChanged")
                else { cancel(); outcome("cancelled") }
            }
            else -> outcome("unsupported")
        }
    } catch (_: IllegalArgumentException) { cancel(); outcome("malformed") }

    private fun outcome(name: String): Map<String, Any> {
        lastOutcome = name
        if (name != "accepted") refused++
        return mapOf("outcome" to name)
    }

    private fun select(request: RendererAttributeCodec.Header): Any {
        val current = basis() ?: return outcome("staleCut")
        if (!request.styleMatches(current.style) || request.shape != current.shape ||
            request.color != current.color || request.rendererGeneration != current.rendererGeneration) return outcome("staleCut")
        val old = selection
        if (old == null || request.selectionGeneration == 0L) {
            if (retired?.held == false) retired = null
            if (candidate != null || retired != null) return outcome("busy")
            var count = minOf(current.count, if (current.shape == RendererAttributeCodec.BALLS) 256 else 4096)
            requestedRows = count
            // Caps are maxima. Refuse only display density when the complete
            // owner model does not fit; canonical/photo/depth quality is fixed.
            while (!admit(modeledAllowance(current.shape, count))) {
                if (count <= 1) return outcome("busy")
                count /= 2
            }
            fundedRows = count; fundedModeledBytes = modeledAllowance(current.shape, count)
            val ids = LongArray(count) { current.surfaceIdAt(it) }
            java.util.Arrays.sort(ids)
            require(ids.all { it > 0 } && (1 until ids.size).all { ids[it - 1] < ids[it] })
            generation = Math.addExact(generation, 1)
            val next = RendererAttributeCodec.header(current.style, current.rendererGeneration,
                generation, current.shape, current.color, count)
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(next.bytes, 8, 144); hash.update(next.bytes, 240, 8)
            val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            for (id in ids) { scratch.putLong(0, id); hash.update(scratch.array()) }
            hash.digest().copyInto(next.bytes, 168)
            selection = next; selectedIds = ids; candidate = null
            selectionPaletteRevision = current.paletteRevision
            received = 0; nextPage = 0; candidateHeader = null
        } else if (!old.sameSelection(request)) return outcome("selectionChanged")
        val selected = checkNotNull(selection)
        if (!currentMatches(selected)) { cancel(); return outcome("selectionChanged") }
        val ids = checkNotNull(selectedIds)
        val pageIndex = request.pageIndex
        val pages = maxOf(1, (ids.size + RendererAttributeCodec.IDS_PER_PAGE - 1) / RendererAttributeCodec.IDS_PER_PAGE)
        if (pageIndex >= pages) return outcome("malformed")
        val first = pageIndex * RendererAttributeCodec.IDS_PER_PAGE
        val count = minOf(RendererAttributeCodec.IDS_PER_PAGE, ids.size - first)
        val response = selected.page(RendererAttributeCodec.SELECT, pageIndex, pages, first, count).encode(
            ByteArray(count * 8).also { body -> val data = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { data.putLong(ids[first + it]) } },
        )
        lastOutcome = "selected"
        return response
    }

    private fun currentMatches(header: RendererAttributeCodec.Header): Boolean {
        val current = basis() ?: return false
        val ids = selectedIds ?: return false
        val count = ids.size
        return header.styleMatches(current.style) && current.rendererGeneration == header.rendererGeneration &&
            current.shape == header.shape && current.color == header.color && current.count >= count &&
            current.paletteRevision == selectionPaletteRevision &&
            (0 until count).all { java.util.Arrays.binarySearch(ids, current.surfaceIdAt(it)) >= 0 }
    }

    private fun page(header: RendererAttributeCodec.Header, raw: ByteArray): Any {
        val selected = selection ?: return outcome("selectionChanged")
        if (!selected.sameSelection(header) || !currentMatches(selected)) {
            cancel(); return outcome("selectionChanged")
        }
        val ids = checkNotNull(selectedIds)
        val pages = maxOf(1, (ids.size + RendererAttributeCodec.ROWS_PER_PAGE - 1) / RendererAttributeCodec.ROWS_PER_PAGE)
        val expectedCount = minOf(RendererAttributeCodec.ROWS_PER_PAGE, ids.size - received)
        if (header.pageIndex != nextPage || header.pageCount != pages || header.first != received ||
            header.count != expectedCount || raw.size != 256 + expectedCount * 24) return outcome("malformed")
        if (candidate == null) {
            if (retired?.held == false) retired = null
            if (retired != null || !admit(modeledAllowance(header.shape, ids.size))) return outcome("busy")
            candidate = ByteArray(ids.size * 24); candidateHeader = header
            chargePeak()
        }
        if (!candidateHeader!!.sameDigest(header)) { cancel(); return outcome("digestMismatch") }
        repeat(expectedCount) { RendererAttributeCodec.validateRow(raw, 256 + it * 24, ids[received + it]) }
        raw.copyInto(candidate!!, received * 24, 256)
        received += expectedCount; nextPage++
        return outcome("accepted")
    }

    private fun commit(header: RendererAttributeCodec.Header): Any {
        val committed = active
        if (activeValid && committed != null && committed.header.sameSelection(header) && committed.header.sameDigest(header)) {
            return if (currentMatches(header)) outcome("accepted") else outcome("staleCut")
        }
        val selected = selection ?: return outcome("selectionChanged")
        if (!selected.sameSelection(header) || !currentMatches(selected)) { cancel(); return outcome("staleCut") }
        val rows = candidate ?: return outcome("incomplete")
        if (received != selected.total || !candidateHeader!!.sameDigest(header) || header.count != 0) return outcome("incomplete")
        val digest = RendererAttributeCodec.digest(header, rows)
        if (!(0 until 32).all { digest[it] == header.bytes[200 + it] }) { cancel(); return outcome("digestMismatch") }
        val table = RendererAttributeTable(header.page(RendererAttributeCodec.COMMIT, 0, 0, 0, 0),
            rows)
        if (!attach(table)) { cancel(); return outcome("staleCut") }
        retired = active?.takeIf { it.held }
        active = table; activeValid = true; candidate = null; candidateHeader = null; accepted++
        chargePeak()
        return outcome("accepted")
    }

    private fun status(header: RendererAttributeCodec.Header): Any =
        if (activeValid && active?.let { it.header.sameSelection(header) && it.header.sameDigest(header) } == true && currentMatches(header))
            outcome("accepted") else outcome("cancelled")

    @Synchronized fun invalidate() {
        cancel(); activeValid = false
        if (active?.held == false) active = null
        if (retired?.held == false) retired = null
        invalidations++
    }
    private fun cancel() { selection = null; selectedIds = null; candidate = null; candidateHeader = null; received = 0; nextPage = 0 }
    private fun chargePeak() { maxTables = maxOf(maxTables, (if (active != null) 1 else 0) +
        (if (retired != null) 1 else 0) + (if (candidate != null) 1 else 0)); check(maxTables <= 2) }
    @Synchronized fun portableBytes(): Long = 512L + (selectedIds?.size ?: 0) * 8L +
        (candidate?.size ?: 0) + (active?.portableBytes ?: 0) + (retired?.portableBytes ?: 0)
    @Synchronized fun snapshot(): Map<String, Any> = mapOf("supported" to true, "basisAvailable" to (activeValid && active != null),
        "outcome" to lastOutcome, "accepted" to accepted, "refused" to refused, "invalidations" to invalidations,
        "maxTables" to maxTables, "heldTables" to listOfNotNull(active, retired).count { it.held },
        "portableOwnerBytes" to portableBytes(), "modeledImplementationAllowanceBytes" to 65536,
        "requestedAttributeRows" to requestedRows, "fundedAttributeRows" to fundedRows,
        "rendererAttributeModeledCpuBytes" to fundedModeledBytes,
        "actualHeapMeasured" to false)

    companion object {
        /** Both peer sidecar/codec allowance + bounded geometry model, never a raised cap. */
        fun modeledAllowance(shape: Int, rows: Int): Long {
            require(rows in 0..if (shape == RendererAttributeCodec.BALLS) 256 else 4096)
            // Sidecar/peer/codec envelope + current and constructing CPU mesh,
            // borrowed source plus private geometry copies, transform/inverse,
            // bounded descriptor page and writer workspace. GPU/page owners
            // are charged independently per resource token before construction.
            val body = rows * if (shape == RendererAttributeCodec.BALLS) 3856L else CoveragePlanarPatchGeometry.GPU_BYTES_PER_PATCH + 16L
            val directionOverlap = if (shape == RendererAttributeCodec.BALLS) rows * 224L + 6648L else 280L
            return 561152L + 2 * (body + 64L) + rows * 72L + 256L + 20480L + directionOverlap
        }
    }
}
