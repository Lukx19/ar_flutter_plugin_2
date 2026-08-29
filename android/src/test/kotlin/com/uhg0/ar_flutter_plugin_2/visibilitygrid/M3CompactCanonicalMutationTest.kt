package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CompactCanonicalMutationTest {
    @Test
    fun `all seven mutation kinds persist exact complete private semantics`() {
        val directory = Files.createTempDirectory("m3-cow-seven-kinds").toFile()
        try {
            val activeRows = listOf(
                row(1, M3Voxel(0, 0, 0)), row(2, M3Voxel(1, 0, 0)),
                row(3, M3Voxel(2, 0, 0)), row(4, M3Voxel(3, 0, 0)),
            )
            data class Scenario(
                val name: String,
                val kind: M3PreparedMutationKind,
                val base: TestView,
                val command: Any,
                val expected: Map<Long, M3Voxel>,
                val removed: Set<Long>,
                val support: Map<Long, List<Long>>,
                val lineage: Map<Long, List<Long>>,
                val expectedKinds: Set<M3CowFragmentKind>,
            )
            val rowKinds = setOf(M3CowFragmentKind.ROW, M3CowFragmentKind.ID_INDEX, M3CowFragmentKind.VOXEL_INDEX, M3CowFragmentKind.PAGE_INDEX)
            val evidenceKinds = setOf(M3CowFragmentKind.SOURCE, M3CowFragmentKind.SUPPORT)
            val tombstoneKinds = setOf(M3CowFragmentKind.ID_TOMBSTONE, M3CowFragmentKind.VOXEL_TOMBSTONE, M3CowFragmentKind.PAGE_TOMBSTONE, M3CowFragmentKind.SUPPORT_TOMBSTONE, M3CowFragmentKind.LINEAGE_TOMBSTONE)
            val relocationKinds = rowKinds + setOf(M3CowFragmentKind.SUPPORT, M3CowFragmentKind.LINEAGE) + tombstoneKinds
            val replacementKinds = rowKinds + evidenceKinds + setOf(M3CowFragmentKind.LINEAGE) + tombstoneKinds
            fun active(name: String) = view(name, activeRows)
            val scenarios = listOf(
                Scenario("feature-add", M3PreparedMutationKind.FEATURE_ADD, view("feature-add", emptyList()),
                    M3FeatureMutationCommand("feature-add", 0, 0, target(null, M3Voxel(10, 0, 0))),
                    mapOf(1L to M3Voxel(10, 0, 0)), emptySet(), mapOf(1L to listOf(1L)), emptyMap(), rowKinds + evidenceKinds),
                Scenario("feature-refine", M3PreparedMutationKind.FEATURE_REFINE, view("feature-refine", listOf(row(1, M3Voxel(0, 0, 0)))),
                    M3FeatureMutationCommand("feature-refine", 0, 0, target(M3SurfaceId(1), M3Voxel(0, 0, 0), 191)),
                    mapOf(1L to M3Voxel(0, 0, 0)), emptySet(), mapOf(1L to listOf(1L)), emptyMap(), rowKinds),
                Scenario("create", M3PreparedMutationKind.CREATE, view("create", emptyList()),
                    M3CanonicalTransactionCommand("create", M3CanonicalOperation.CREATE, 0, 0, emptyList(), listOf(target(null, M3Voxel(10, 0, 0)), target(null, M3Voxel(11, 0, 0)))),
                    mapOf(1L to M3Voxel(10, 0, 0), 2L to M3Voxel(11, 0, 0)), emptySet(), mapOf(1L to listOf(1L), 2L to listOf(2L)), emptyMap(), rowKinds + evidenceKinds),
                Scenario("relocation", M3PreparedMutationKind.RELOCATION, active("relocation"),
                    M3CanonicalTransactionCommand("relocation", M3CanonicalOperation.RELOCATION, 0, 0, listOf(M3SurfaceId(1)), listOf(target(M3SurfaceId(1), M3Voxel(10, 0, 0)))),
                    mapOf(1L to M3Voxel(10, 0, 0), 2L to M3Voxel(1, 0, 0), 3L to M3Voxel(2, 0, 0), 4L to M3Voxel(3, 0, 0)), emptySet(), mapOf(1L to listOf(1L), 2L to listOf(2L), 3L to listOf(3L), 4L to listOf(4L)), mapOf(1L to listOf(1L)), relocationKinds),
                Scenario("merge", M3PreparedMutationKind.MERGE, active("merge"),
                    M3CanonicalTransactionCommand("merge", M3CanonicalOperation.MERGE, 0, 0, listOf(M3SurfaceId(2), M3SurfaceId(3)), listOf(target(null, M3Voxel(20, 0, 0)))),
                    mapOf(1L to M3Voxel(0, 0, 0), 4L to M3Voxel(3, 0, 0), 5L to M3Voxel(20, 0, 0)), setOf(2L, 3L), mapOf(1L to listOf(1L), 4L to listOf(4L), 5L to listOf(2L, 3L)), mapOf(2L to listOf(5L), 3L to listOf(5L)), replacementKinds),
                Scenario("split", M3PreparedMutationKind.SPLIT, active("split"),
                    M3CanonicalTransactionCommand("split", M3CanonicalOperation.SPLIT, 0, 0, listOf(M3SurfaceId(4)), listOf(target(null, M3Voxel(30, 0, 0)), target(null, M3Voxel(31, 0, 0)))),
                    mapOf(1L to M3Voxel(0, 0, 0), 2L to M3Voxel(1, 0, 0), 3L to M3Voxel(2, 0, 0), 5L to M3Voxel(30, 0, 0), 6L to M3Voxel(31, 0, 0)), setOf(4L), mapOf(1L to listOf(1L), 2L to listOf(2L), 3L to listOf(3L), 5L to listOf(4L), 6L to listOf(4L)), mapOf(4L to listOf(5L, 6L)), replacementKinds),
                Scenario("replacement", M3PreparedMutationKind.REPLACEMENT, active("replacement"),
                    M3CanonicalTransactionCommand("replacement", M3CanonicalOperation.REPLACEMENT, 0, 0, listOf(M3SurfaceId(1)), listOf(target(null, M3Voxel(40, 0, 0)))),
                    mapOf(2L to M3Voxel(1, 0, 0), 3L to M3Voxel(2, 0, 0), 4L to M3Voxel(3, 0, 0), 5L to M3Voxel(40, 0, 0)), setOf(1L), mapOf(2L to listOf(2L), 3L to listOf(3L), 4L to listOf(4L), 5L to listOf(1L)), mapOf(1L to listOf(5L)), replacementKinds),
            )
            scenarios.forEach { scenario ->
                val intent = when (val command = scenario.command) {
                    is M3FeatureMutationCommand -> intent(scenario.base, command, File(directory, "${scenario.name}-intent"))
                    is M3CanonicalTransactionCommand -> intent(scenario.base, command, File(directory, "${scenario.name}-intent"))
                    else -> error("unknown command")
                }
                val exactCurrent = ByteArrayOutputStream().also { output ->
                    assertTrue(intent.visitCurrent(M3PreparedIntentVisitor.NONE, output) is M3PreparedIntentVisitResult.Complete)
                }.toByteArray()
                val generation = stage(scenario.base, intent, File(directory, "${scenario.name}-generations"))
                assertEquals(scenario.kind, generation.root.commandKind)
                assertEquals(scenario.name, scenario.expectedKinds, generation.root.manifest.mapTo(mutableSetOf()) { it.kind })
                assertArrayEquals(exactCurrent, File(generation.directory, M3CanonicalCowGeneration.CURRENT_UNACKED_FILE).readBytes())
                val overlay = generation.overlay(scenario.base)
                scenario.expected.forEach { (id, voxel) ->
                    assertEquals("${scenario.name} id=$id", voxel, overlay.findById(M3SurfaceId(id))?.voxel)
                    assertEquals("${scenario.name} voxel=$voxel", id, overlay.findByVoxel(voxel)?.id?.value)
                    assertNotNull("${scenario.name} source=$id", (overlay.readSourceById(M3SurfaceId(id)) as M3CanonicalPageRead.Complete).value)
                    val location = requireNotNull(m3CompactLocation(M3SurfaceOwnershipConfiguration(), voxel))
                    assertTrue("${scenario.name} page=$id", overlay.readPage(location.region, location.page, 0, 512).rows.any { it.id.value == id && it.voxel == voxel })
                }
                scenario.removed.forEach { id ->
                    assertNull("${scenario.name} removed=$id", overlay.findById(M3SurfaceId(id)))
                    assertNotNull("${scenario.name} historical source=$id", (overlay.readSourceById(M3SurfaceId(id)) as M3CanonicalPageRead.Complete).value)
                    val removedSupport = mutableListOf<Long>()
                    assertTrue(overlay.visitSourceSupport(M3SurfaceId(id), null) { removedSupport += it.source.id.value; true } is M3SourceSupportRead.Complete)
                    assertTrue("${scenario.name} removed support=$id", removedSupport.isEmpty())
                }
                scenario.base.rowsSnapshot().filter { old -> scenario.expected[old.id.value] != old.voxel }.forEach { old ->
                    assertNull("${scenario.name} old voxel=${old.voxel}", overlay.findByVoxel(old.voxel))
                    val location = requireNotNull(m3CompactLocation(M3SurfaceOwnershipConfiguration(), old.voxel))
                    assertTrue("${scenario.name} old page=${old.id.value}", overlay.readPage(location.region, location.page, 0, 512).rows.none { it.id == old.id && it.voxel == old.voxel })
                }
                scenario.support.forEach { (targetId, expectedSources) ->
                    val actual = mutableListOf<Long>()
                    assertTrue(overlay.visitSourceSupport(M3SurfaceId(targetId), null) { actual += it.source.id.value; true } is M3SourceSupportRead.Complete)
                    assertEquals("${scenario.name} support=$targetId", expectedSources, actual)
                }
                (scenario.base.rowsSnapshot().map { it.id.value } + scenario.expected.keys + scenario.lineage.keys).distinct().forEach { sourceId ->
                    val actual = mutableListOf<Long>()
                    assertTrue(overlay.visitLineage(M3SurfaceId(sourceId), null) { actual += it.target.value; true } is M3LineageRead.Complete)
                    assertEquals("${scenario.name} lineage=$sourceId", scenario.lineage[sourceId].orEmpty(), actual)
                }
                assertEquals(scenario.expected.size, overlay.cut.liveSurfaceCount)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `existing overlay fails closed after generation close without base fallback`() {
        val directory = Files.createTempDirectory("m3-cow-close").toFile()
        try {
            val base = view("close", listOf(row(1, M3Voxel(0, 0, 0)), row(2, M3Voxel(1, 0, 0))))
            val preparedIntent = intent(
                base,
                M3FeatureMutationCommand("close", 0, 0, target(M3SurfaceId(1), M3Voxel(0, 0, 0), 191)),
                File(directory, "intent"),
            )
            val generation = stage(base, preparedIntent, File(directory, "generations"))
            val overlay = generation.overlay(base)
            assertNotNull(overlay.findById(M3SurfaceId(2)))
            val location = requireNotNull(m3CompactLocation(M3SurfaceOwnershipConfiguration(), M3Voxel(1, 0, 0)))
            assertTrue(overlay.readPage(location.region, location.page, 0, 10).rows.isNotEmpty())

            val enteredBaseFallback = CountDownLatch(1)
            val releaseBaseFallback = CountDownLatch(1)
            val closeReturned = CountDownLatch(1)
            val concurrentRead = AtomicReference<M3CompactSurface?>()
            base.beforeFindById = { id ->
                if (id == M3SurfaceId(2)) {
                    enteredBaseFallback.countDown()
                    assertTrue(releaseBaseFallback.await(5, TimeUnit.SECONDS))
                }
            }
            val reader = Thread { concurrentRead.set(overlay.findById(M3SurfaceId(2))) }.also { it.start() }
            assertTrue(enteredBaseFallback.await(5, TimeUnit.SECONDS))
            val closer = Thread { generation.close(); closeReturned.countDown() }.also { it.start() }
            assertFalse("close crossed an in-flight generation read", closeReturned.await(100, TimeUnit.MILLISECONDS))
            releaseBaseFallback.countDown()
            reader.join(5_000); closer.join(5_000)
            assertFalse(reader.isAlive); assertFalse(closer.isAlive)
            assertNotNull(concurrentRead.get())
            assertEquals(0L, closeReturned.count)

            assertNull(overlay.findById(M3SurfaceId(2)))
            assertNull(overlay.findByVoxel(M3Voxel(1, 0, 0)))
            assertTrue(overlay.readPage(location.region, location.page, 0, 10).rows.isEmpty())
            assertEquals(M3CompactCanonicalRefusal.CLOSED, (overlay.readSourceById(M3SurfaceId(2)) as M3CanonicalPageRead.Refused).reason)
            assertEquals(M3CompactCanonicalRefusal.CLOSED, (overlay.visitSourceSupport(M3SurfaceId(2), null) { true } as M3SourceSupportRead.Refused).reason)
            assertEquals(M3CompactCanonicalRefusal.CLOSED, (overlay.visitLineage(M3SurfaceId(2), null) { true } as M3LineageRead.Refused).reason)
            assertEquals(M3CompactCanonicalRefusal.CLOSED, (generation.readPage(base, location.region, location.page, null, 10) as M3CowPageRead.Refused).reason)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `physical reservation reconciles every candidate file and changed command bytes conflict`() {
        val directory = Files.createTempDirectory("m3-cow-physical").toFile()
        try {
            val base = view("physical", emptyList())
            val firstIntent = intent(base, M3FeatureMutationCommand("same-command", 0, 0, target(null, M3Voxel(0, 0, 0))), File(directory, "intent-a"))
            val generationParent = File(directory, "generations").also { assertTrue(it.mkdirs()) }
            StorageBudgetCoordinatorV2(
                generationParent, StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
                JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                freeBytes = { 128L * 1024 * 1024 },
            ).use { coordinator ->
                val budget = M3CoordinatorStorageBudget(coordinator)
                val store = requireNotNull(M3CanonicalMutableStore.open(generationParent, budget))
                val prepared = prepared(store.stage(firstIntent, base))
                val physical = coordinator.physicallyAllocatedTreeBytes(prepared.generation.directory)
                assertEquals(physical, prepared.generation.storageReceipt().allocatedBytes)
                assertEquals(physical, coordinator.committedBytes())
                assertEquals(0L, coordinator.reservedBytes())
                prepared.generation.root.manifest.groupBy { it.file }.forEach { (name, entries) ->
                    assertEquals(entries.size.toLong() * M3CanonicalCowGeneration.PAGE_BYTES, File(prepared.generation.directory, name).length())
                }

                val changedIntent = intent(base, M3FeatureMutationCommand("same-command", 0, 0, target(null, M3Voxel(1, 0, 0))), File(directory, "intent-b"))
                val conflict = store.stage(changedIntent, base) as M3CanonicalCowStageResult.Refused
                assertEquals(M3CanonicalCowRefusal.IDENTITY_CONFLICT, conflict.reason)
                assertEquals(physical, coordinator.committedBytes())

                val page = File(prepared.generation.directory, prepared.generation.root.manifest.first().file)
                java.io.RandomAccessFile(page, "rw").use { file ->
                    file.seek(20)
                    val original = file.read()
                    file.seek(20)
                    file.write(original xor 0x55)
                    file.fd.sync()
                }
                val corrupt = store.stage(firstIntent, base) as M3CanonicalCowStageResult.Refused
                assertEquals(M3CanonicalCowRefusal.CORRUPT_GENERATION, corrupt.reason)
                assertEquals(0L, coordinator.reservedBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `complete private generation contains all semantic kinds exact current and tombstones`() {
        val directory = Files.createTempDirectory("m3-cow-complete").toFile()
        try {
            val empty = view("cow-add", emptyList())
            val add = M3FeatureMutationCommand("add", 0, 0, target(null, M3Voxel(0, 0, 0)))
            val addIntent = intent(empty, add, File(directory, "add-intent"))
            val budget = RecordingBudget()
            val store = requireNotNull(M3CanonicalMutableStore.open(File(directory, "generations"), budget))
            val added = prepared(store.stage(addIntent, empty))
            val addKinds = added.generation.root.manifest.mapTo(mutableSetOf()) { it.kind }
            assertTrue(addKinds.containsAll(setOf(
                M3CowFragmentKind.ROW, M3CowFragmentKind.ID_INDEX, M3CowFragmentKind.VOXEL_INDEX,
                M3CowFragmentKind.PAGE_INDEX, M3CowFragmentKind.SOURCE, M3CowFragmentKind.SUPPORT,
            )))
            val addedView = added.generation.overlay(empty)
            val row = addedView.findById(M3SurfaceId(1)); assertNotNull(row); row!!
            assertEquals(M3Voxel(0, 0, 0), row.voxel)
            assertNotNull((addedView.readSourceById(M3SurfaceId(1)) as M3CanonicalPageRead.Complete).value)
            val support = mutableListOf<M3PagedSupport>()
            assertTrue(addedView.visitSourceSupport(M3SurfaceId(1), null) { support += it; true } is M3SourceSupportRead.Complete)
            assertEquals(listOf(1L), support.map { it.source.id.value })
            val location = requireNotNull(m3CompactLocation(M3SurfaceOwnershipConfiguration(), M3Voxel(0, 0, 0)))
            val cowPage = added.generation.readPage(empty, location.region, location.page, null, 1) as M3CowPageRead.Complete
            assertEquals(listOf(1L), cowPage.rows.map { it.id.value })
            val stalePage = added.generation.readPage(empty, location.region, location.page,
                M3CowPageCursor(M3CanonicalReceiptBytes(ByteArray(32)), location.region, location.page, 0, 0, 0), 1)
            assertEquals(M3CompactCanonicalRefusal.STALE_CURSOR, (stalePage as M3CowPageRead.Refused).reason)
            assertEquals(added.generation.root.current.length, File(added.generation.directory, M3CanonicalCowGeneration.CURRENT_UNACKED_FILE).length())
            assertTrue(added.generation.storageReceipt().phasePeakBytes <= 1_048_576L)
            assertTrue(budget.requests.single() >= budget.actuals.maxOrNull()!!)

            val replay = store.stage(addIntent, empty) as M3CanonicalCowStageResult.Prepared
            assertTrue(replay.reused)
            assertEquals(added.generation.directory, replay.generation.directory)

            val baseRow = row(1, M3Voxel(0, 0, 0))
            val base = view("cow-relocate", listOf(baseRow))
            val baseRootBefore = base.cut.rootHash
            val relocation = M3CanonicalTransactionCommand(
                "relocate", M3CanonicalOperation.RELOCATION, 0, 0, listOf(M3SurfaceId(1)),
                listOf(target(M3SurfaceId(1), M3Voxel(2, 0, 0))),
            )
            val relocationIntent = intent(base, relocation, File(directory, "relocate-intent"))
            val relocated = prepared(store.stage(relocationIntent, base))
            val kinds = relocated.generation.root.manifest.mapTo(mutableSetOf()) { it.kind }
            assertTrue(kinds.contains(M3CowFragmentKind.LINEAGE))
            assertTrue(kinds.containsAll(setOf(
                M3CowFragmentKind.ID_TOMBSTONE, M3CowFragmentKind.VOXEL_TOMBSTONE,
                M3CowFragmentKind.PAGE_TOMBSTONE, M3CowFragmentKind.SUPPORT_TOMBSTONE,
                M3CowFragmentKind.LINEAGE_TOMBSTONE,
            )))
            val relocatedView = relocated.generation.overlay(base)
            assertEquals(M3Voxel(2, 0, 0), relocatedView.findById(M3SurfaceId(1))?.voxel)
            assertNull(relocatedView.findByVoxel(M3Voxel(0, 0, 0)))
            assertEquals(M3SurfaceId(1), relocatedView.findByVoxel(M3Voxel(2, 0, 0))?.id)
            assertNotNull((relocatedView.readSourceById(M3SurfaceId(1)) as M3CanonicalPageRead.Complete).value)
            val lineage = mutableListOf<M3LineageEdge>()
            val lineageRead = relocatedView.visitLineage(M3SurfaceId(1), null) { lineage += it; true }
            assertEquals(1, (lineageRead as M3LineageRead.Complete).delivered)
            assertEquals(listOf(M3LineageEdge(M3SurfaceId(1), M3SurfaceId(1))), lineage)
            assertEquals(baseRootBefore, base.cut.rootHash)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `small and 100k real v6 cuts stage identical one-row dirty work without dense reads`() {
        val directory = Files.createTempDirectory("m3-cow-scale").toFile()
        try {
            val small = realV6("scale-small", 1, File(directory, "small-v6"))
            val large = realV6("scale-large", 100_000, File(directory, "large-v6"))
            val command = { name: String -> M3FeatureMutationCommand(name, 0, 0, target(M3SurfaceId(1), M3Voxel(0, 0, 0), 191)) }
            val smallIntent = intent(small, command("same"), File(directory, "small-intent"))
            val largeIntent = intent(large, command("same"), File(directory, "large-intent"))
            val smallBefore = small.readWorkReceipt(); val largeBefore = large.readWorkReceipt()
            val smallGeneration = stage(small, smallIntent, File(directory, "small"))
            val largeGeneration = stage(large, largeIntent, File(directory, "large"))
            fun signature(generation: M3CanonicalCowGeneration) = generation.root.manifest.map { Triple(it.kind, it.count, it.hash.toList()) }
            assertEquals(signature(smallGeneration), signature(largeGeneration))
            val smallWork = small.readWorkReceipt() - smallBefore; val largeWork = large.readWorkReceipt() - largeBefore
            assertEquals(smallWork.directLookups, largeWork.directLookups)
            assertEquals(smallWork.pageReads, largeWork.pageReads)
            assertTrue("large inspected=${largeWork.inspectedRows}", largeWork.inspectedRows < 64)
            assertEquals(0, smallWork.pageReads)
            assertEquals(0, largeWork.pageReads)

            val voxel = M3Voxel(0, 0, 0)
            val location = requireNotNull(m3CompactLocation(M3SurfaceOwnershipConfiguration(), voxel))
            fun queryReceipt(generation: M3CanonicalCowGeneration, base: M3CanonicalStateView): M3CowReadWork {
                val before = generation.readWorkReceipt()
                assertEquals(M3SurfaceId(1), generation.overlay(base).findByVoxel(voxel)?.id)
                val page = generation.readPage(base, location.region, location.page, null, 1) as M3CowPageRead.Complete
                assertEquals(listOf(1L), page.rows.map { it.id.value })
                return generation.readWorkReceipt() - before
            }
            assertEquals(queryReceipt(smallGeneration, small), queryReceipt(largeGeneration, large))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `same-page dirty and tombstone indexes stream across radix windows in canonical order`() {
        val directory = Files.createTempDirectory("m3-cow-dense-page").toFile()
        try {
            val count = 700
            fun voxel(index: Int) = M3Voxel(index / 100, (index / 10) % 10, index % 10)
            val location = requireNotNull(m3CompactLocation(M3SurfaceOwnershipConfiguration(), voxel(0)))

            val empty = view("dense-create", emptyList())
            val createTargets = (0 until count).reversed().map { target(null, voxel(it)) }
            val create = M3CanonicalTransactionCommand(
                "dense-create", M3CanonicalOperation.CREATE, 0, 0, emptyList(), createTargets,
            )
            val created = stage(empty, intent(empty, create, File(directory, "create-intent")), File(directory, "create-generation"))
            val actual = mutableListOf<M3CompactSurface>()
            var cursor: M3CowPageCursor? = null
            do {
                val before = created.readWorkReceipt()
                val read = created.readPage(empty, location.region, location.page, cursor, 73) as M3CowPageRead.Complete
                actual += read.rows
                cursor = read.nextCursor
                val work = created.readWorkReceipt() - before
                assertTrue("unbounded dense dirty pages=$work", work.pages <= 75)
                assertTrue("unbounded dense dirty records=$work", work.records <= 21_000)
            } while (cursor != null)
            assertEquals(count, actual.size)
            assertEquals(count, actual.map { it.id }.toSet().size)
            assertEquals((0 until count).map(::voxel), actual.map { it.voxel })

            val indexEntry = created.root.manifest.first { it.kind == M3CowFragmentKind.PAGE_INDEX }
            java.io.RandomAccessFile(File(created.directory, indexEntry.file), "rw").use { file ->
                file.seek(indexEntry.page.toLong() * M3CanonicalCowGeneration.PAGE_BYTES + 100)
                val original = file.read()
                file.seek(indexEntry.page.toLong() * M3CanonicalCowGeneration.PAGE_BYTES + 100)
                file.write(original xor 0x5a)
                file.fd.sync()
            }
            assertEquals(
                M3CompactCanonicalRefusal.CORRUPT,
                (created.readPage(empty, location.region, location.page, null, 73) as M3CowPageRead.Refused).reason,
            )

            val baseRows = (0 until count).map { row(it + 1L, voxel(it)) }
            val base = view("dense-remove", baseRows)
            val replacement = M3CanonicalTransactionCommand(
                "dense-remove", M3CanonicalOperation.REPLACEMENT, 0, 0,
                baseRows.map { it.id }, listOf(target(null, M3Voxel(20, 0, 0))),
            )
            val removed = stage(base, intent(base, replacement, File(directory, "remove-intent")), File(directory, "remove-generation"))
            cursor = null
            var windows = 0
            do {
                val before = removed.readWorkReceipt()
                val read = removed.readPage(base, location.region, location.page, cursor, 73) as M3CowPageRead.Complete
                assertTrue(read.rows.isEmpty())
                cursor = read.nextCursor
                windows++
                val work = removed.readWorkReceipt() - before
                assertTrue("unbounded dense tombstone pages=$work", work.pages <= 1)
                assertTrue("unbounded dense tombstone records=$work", work.records <= 818)
            } while (cursor != null)
            assertEquals(2, windows)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `lookup and page receipts stay equal from one full index page to large dirty generation`() {
        val directory = Files.createTempDirectory("m3-cow-dirty-scale").toFile()
        try {
            val configuration = M3SurfaceOwnershipConfiguration()
            val requested = requireNotNull(m3CompactLocation(configuration, M3Voxel(0, 0, 0)))
            val requestedPageKey = M3CanonicalCowGeneration.pageKey(requested.region, requested.page)
            val common = (0 until 1_000).map { M3Voxel(it / 100, (it / 10) % 10, it % 10) }
                .sortedWith { a, b -> java.lang.Long.compareUnsigned(
                    M3CanonicalCowGeneration.voxelKey(a.x, a.y, a.z),
                    M3CanonicalCowGeneration.voxelKey(b.x, b.y, b.z),
                ) }
                .take(M3CowFragmentKind.VOXEL_INDEX.recordsPerPage)
            val lookupVoxel = common.minWith { a, b -> compareVoxel(a.x, a.y, a.z, b.x, b.y, b.z) }
            val commonTargets = (listOf(lookupVoxel) + common.filter { it != lookupVoxel }).map { target(null, it) }
            val voxelThreshold = common.maxOfWith(
                Comparator { a, b -> java.lang.Long.compareUnsigned(a, b) },
            ) { M3CanonicalCowGeneration.voxelKey(it.x, it.y, it.z) }
            val fillers = ArrayList<M3CanonicalTarget>()
            var candidate = 0
            while (fillers.size < 5_182) {
                val value = M3Voxel(10 + candidate / 100, (candidate / 10) % 10, candidate % 10)
                candidate++
                val location = requireNotNull(m3CompactLocation(configuration, value))
                if (java.lang.Long.compareUnsigned(M3CanonicalCowGeneration.pageKey(location.region, location.page), requestedPageKey) <= 0) continue
                if (java.lang.Long.compareUnsigned(M3CanonicalCowGeneration.voxelKey(value.x, value.y, value.z), voxelThreshold) <= 0) continue
                fillers += target(null, value)
            }

            fun generation(name: String, targets: List<M3CanonicalTarget>): Pair<TestView, M3CanonicalCowGeneration> {
                val base = view(name, emptyList())
                val command = M3CanonicalTransactionCommand(name, M3CanonicalOperation.CREATE, 0, 0, emptyList(), targets)
                return base to stage(base, intent(base, command, File(directory, "$name-intent")), File(directory, "$name-generation"))
            }
            val (smallBase, small) = generation("dirty-small", commonTargets)
            val largeBase = view("dirty-large", emptyList())
            val candidates = commonTargets + fillers
            fun preparation(count: Int) = M3SurfaceOwnership.prepareMutation(
                largeBase,
                configuration,
                M3CanonicalTransactionCommand("dirty-large", M3CanonicalOperation.CREATE, 0, 0, emptyList(), candidates.take(count)),
            )
            assertTrue(preparation(candidates.size) is M3CanonicalMutationPreparation.Refused)
            var admitted = commonTargets.size
            var refused = candidates.size
            while (refused - admitted > 1) {
                val middle = admitted + (refused - admitted) / 2
                if (preparation(middle) is M3CanonicalMutationPreparation.Prepared) admitted = middle else refused = middle
            }
            assertTrue(admitted > commonTargets.size)
            val largePlan = (preparation(admitted) as M3CanonicalMutationPreparation.Prepared).mutation
            val largeIntent = flush(largeBase, largePlan, File(directory, "dirty-large-intent"))
            val large = stage(largeBase, largeIntent, File(directory, "dirty-large-generation"))
            val phase = large.storageReceipt()
            assertEquals(M3CanonicalCowGeneration.phasePeakBytes(large.root.manifest.size), phase.phasePeakBytes)
            assertTrue("maximum admitted dirty phase=$phase", phase.phasePeakBytes <= 1_048_576L)
            fun receipt(base: TestView, generation: M3CanonicalCowGeneration): M3CowReadWork {
                val before = generation.readWorkReceipt()
                assertEquals(M3SurfaceId(1), generation.overlay(base).findByVoxel(lookupVoxel)?.id)
                val page = generation.readPage(base, requested.region, requested.page, null, 1) as M3CowPageRead.Complete
                assertEquals(listOf(lookupVoxel), page.rows.map { it.voxel })
                return generation.readWorkReceipt() - before
            }
            val smallWork = receipt(smallBase, small)
            val largeWork = receipt(largeBase, large)
            assertEquals(smallWork, largeWork)
            assertEquals(4, largeWork.pages)
            assertTrue(largeWork.records <= 2_200)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `non-adjacent lineage records do not shadow untouched base identity`() {
        val directory = Files.createTempDirectory("m3-cow-lineage-membership").toFile()
        try {
            val base = view(
                "lineage-membership",
                (1L..4L).map { row(it, M3Voxel(it.toInt(), 0, 0)) },
                lineage = listOf(M3LineageEdge(M3SurfaceId(3), M3SurfaceId(99))),
            )
            val command = M3CanonicalTransactionCommand(
                "merge-non-adjacent", M3CanonicalOperation.MERGE, 0, 0,
                listOf(M3SurfaceId(2), M3SurfaceId(4)), listOf(target(null, M3Voxel(20, 0, 0))),
            )
            val generation = stage(base, intent(base, command, File(directory, "intent")), File(directory, "generation"))
            val actual = mutableListOf<M3LineageEdge>()
            val read = generation.overlay(base).visitLineage(M3SurfaceId(3), null) { actual += it; true }
            assertTrue(read is M3LineageRead.Complete)
            assertEquals(listOf(M3LineageEdge(M3SurfaceId(3), M3SurfaceId(99))), actual)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `same-root support cursor rejects malformed candidate ordinal and matching offset`() {
        val directory = Files.createTempDirectory("m3-cow-support-cursor").toFile()
        try {
            val base = view("support-cursor", emptyList())
            val generation = stage(
                base,
                intent(base, M3FeatureMutationCommand("support-cursor", 0, 0, target(null, M3Voxel(0, 0, 0))), File(directory, "intent")),
                File(directory, "generation"),
            )
            val overlay = generation.overlay(base)
            val target = M3SurfaceId(1)
            val stopped = overlay.visitSourceSupport(target, null) { false } as M3SourceSupportRead.Complete
            val valid = requireNotNull(stopped.nextCursor)
            fun refusal(cursor: M3SourceSupportCursor) = overlay.visitSourceSupport(target, cursor) { true } as M3SourceSupportRead.Refused
            assertEquals(M3CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(ordinal = Int.MAX_VALUE)).reason)
            assertEquals(M3CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(ordinal = -1)).reason)
            assertEquals(M3CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(offset = -1)).reason)
            assertEquals(M3CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(offset = 2)).reason)
            val delivered = mutableListOf<Long>()
            val resumed = overlay.visitSourceSupport(target, valid) { delivered += it.source.id.value; true }
            assertTrue(resumed is M3SourceSupportRead.Complete)
            assertEquals(listOf(1L), delivered)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `every stage cut leaves old authority and later yields complete or reusable generation`() {
        M3CanonicalCowFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("m3-cow-fault-${fault.name}").toFile()
            try {
                val base = view("fault-${fault.name}", emptyList())
                val intent = intent(base, M3FeatureMutationCommand("fault", 0, 0, target(null, M3Voxel(0, 0, 0))), File(directory, "intent"))
                val parent = File(directory, "generations")
                val first = requireNotNull(M3CanonicalMutableStore.open(parent, RecordingBudget())).stage(intent, base, fault)
                assertTrue("$fault returned $first", first is M3CanonicalCowStageResult.Refused)
                assertEquals(0, base.cut.liveSurfaceCount)
                val recovered = requireNotNull(M3CanonicalMutableStore.open(parent, RecordingBudget())).stage(intent, base)
                assertTrue("$fault recovery returned $recovered", recovered is M3CanonicalCowStageResult.Prepared)
                val prepared = recovered as M3CanonicalCowStageResult.Prepared
                assertEquals(1, prepared.generation.overlay(base).cut.liveSurfaceCount)
                assertTrue(parent.listFiles().orEmpty().none { it.name.endsWith(".staging") })
            } finally { directory.deleteRecursively() }
        }
    }

    private fun stage(base: M3CanonicalStateView, intent: M3PreparedIntent, parent: File) =
        prepared(requireNotNull(M3CanonicalMutableStore.open(parent, RecordingBudget())).stage(intent, base)).generation

    private fun prepared(result: M3CanonicalCowStageResult): M3CanonicalCowStageResult.Prepared =
        result as? M3CanonicalCowStageResult.Prepared ?: error("stage refused: $result")

    private fun intent(view: M3CanonicalStateView, command: M3FeatureMutationCommand, directory: File): M3PreparedIntent {
        val plan = (M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(), command) as M3CanonicalMutationPreparation.Prepared).mutation
        return flush(view, plan, directory)
    }

    private fun intent(view: TestView, command: M3CanonicalTransactionCommand, directory: File): M3PreparedIntent {
        val plan = (M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(), command) as M3CanonicalMutationPreparation.Prepared).mutation
        return flush(view, plan, directory)
    }

    private fun flush(view: M3CanonicalStateView, plan: M3PreparedCanonicalMutation, directory: File): M3PreparedIntent {
        val journal = (M3CanonicalDirtyJournal.open(view, directory, RecordingBudget()) as M3CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
    }

    private fun target(id: M3SurfaceId?, voxel: M3Voxel, confidence: Int = 192) = M3CanonicalTarget(id, voxel, 0, 0, confidence)
    private fun row(id: Long, voxel: M3Voxel) = M3CompactSurface(M3SurfaceId(id), voxel, 0, 192)
    private fun hash(text: String) = M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))

    private fun realV6(name: String, count: Int, directory: File): M3CompactCanonicalStore {
        assertTrue(directory.mkdirs())
        val group = M3SurfaceGroup(name)
        val prefix = MessageDigest.getInstance("SHA-256").digest(group.value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val ledgerBody = java.io.ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d33524c); out.writeInt(1); out.writeLong(1); out.writeLong(1); out.writeLong(count + 1L)
                out.write(group.hash); out.write(ByteArray(32) { 1 }); out.write(ByteArray(32) { 2 }); out.write(ByteArray(32))
            }
            raw.toByteArray()
        }
        File(directory, "m3-surface-$prefix.ledger").writeBytes(ledgerBody + MessageDigest.getInstance("SHA-256").digest(ledgerBody))
        val snapshot = File(directory, "m3-surface-$prefix.snapshot")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(snapshot).use { raw ->
            val hashed = DigestOutputStream(raw, digest); val out = DataOutputStream(hashed)
            out.writeInt(0x4d33534f); out.writeInt(5); out.writeLong(count + 1L); out.writeInt(count)
            repeat(count) { index ->
                val id = index + 1L; val fingerprint = ByteArray(32) { byte -> (id + byte).toByte() }
                out.writeLong(id); out.writeUTF(group.value); out.writeInt(index); out.writeInt(0); out.writeInt(0)
                out.writeInt(Math.floorDiv(index, 30)); out.writeInt(0); out.writeInt(0); out.writeInt(Math.floorMod(index, 30) / 10)
                out.writeInt(0); out.writeInt(192); out.write(fingerprint)
            }
            out.writeInt(0); out.writeLong(0); out.writeLong(0); out.writeInt(count)
            repeat(count) { index -> out.writeLong(index + 1L); out.writeInt(1); out.writeLong(index + 1L) }
            out.writeInt(count)
            repeat(count) { index ->
                val id = index + 1L; out.writeLong(id); out.writeInt(index); out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(192)
                out.write(ByteArray(32) { byte -> (id + byte).toByte() })
            }
            out.writeInt(0); out.writeInt(0); out.writeBoolean(false); out.flush(); hashed.on(false); raw.write(digest.digest())
        }
        val budget = RecordingBudget()
        val prepared = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        assertTrue(prepared.toString(), prepared is M3CompactCanonicalMigrationResult.Prepared)
        return (M3CompactCanonicalStore.openV6(group, directory, budget) as M3CompactCanonicalOpenResult.Opened).store
    }

    private fun view(
        name: String,
        rows: List<M3CompactSurface>,
        declaredRows: Int = rows.size,
        lineage: List<M3LineageEdge> = emptyList(),
    ): TestView {
        val cut = M3CompactCanonicalCut(M3SurfaceGroup(name), M3CompactCanonicalStore.PROFILE, 0, 0, declaredRows + 1L,
            declaredRows, declaredRows, declaredRows, lineage.size, null, hash("root-$name"), hash("source-$name"))
        return TestView(cut, rows, lineage)
    }

    private class TestView(
        override val cut: M3CompactCanonicalCut,
        private val rows: List<M3CompactSurface>,
        private val lineage: List<M3LineageEdge>,
    ) : M3CanonicalStateView {
        var lookups = 0
        var pageScans = 0
        var beforeFindById: ((M3SurfaceId) -> Unit)? = null
        fun rowsSnapshot() = rows.toList()
        override fun findById(id: M3SurfaceId): M3CompactSurface? { beforeFindById?.invoke(id); lookups++; return rows.firstOrNull { it.id == id } }
        override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? { lookups++; return rows.firstOrNull { it.voxel == voxel } }
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int): M3CompactPage {
            pageScans++
            val matching = rows.filter { row -> m3CompactLocation(M3SurfaceOwnershipConfiguration(), row.voxel)?.let { it.region == region && it.page == page } == true }
            val end = minOf(matching.size, cursor + limit)
            return M3CompactPage(if (cursor <= matching.size) matching.subList(cursor, end) else emptyList(), if (end < matching.size) end else null, matching.size)
        }
        override fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> = M3CanonicalPageRead.Complete(rows.firstOrNull { it.id == id }?.let {
            M3PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, M3CanonicalReceiptBytes(ByteArray(32)))
        }, 0, 0)
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead {
            val source = rows.firstOrNull { it.id == target }
            val delivered = if (source != null && sink(M3PagedSupport(target, M3PagedSource(source.id, source.voxel, source.packedNormal, source.normalConfidence, M3CanonicalReceiptBytes(ByteArray(32)))))) 1 else 0
            return M3SourceSupportRead.Complete(delivered, null, 0, 0)
        }
        override fun visitLineage(source: M3SurfaceId, cursor: M3LineageCursor?, sink: (M3LineageEdge) -> Boolean): M3LineageRead {
            val matches = lineage.filter { it.source == source }
            var delivered = 0
            matches.drop(cursor?.offset ?: 0).forEach { if (!sink(it)) return M3LineageRead.Complete(delivered, M3LineageCursor(cut.rootHash, source, delivered)); delivered++ }
            return M3LineageRead.Complete(delivered, null)
        }
        override fun retainedMemoryReceipt() = error("unused")
        override fun allocatedStorageReceipt() = error("unused")
        override fun close() = Unit
    }

    private class RecordingBudget : M3CanonicalStorageBudget {
        val requests = mutableListOf<Long>()
        val actuals = mutableListOf<Long>()
        override fun reserve(bytes: Long): Any = bytes
        override fun reserveCandidate(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long): Any? {
            requests += maximumPhysicalBytes
            return super.reserveCandidate(staging, target, fileBytes, maximumPhysicalBytes)
        }
        override fun verifyCandidate(token: Any, candidate: File): Long = allocatedBytes(candidate).also { actuals += it }
        override fun commit(token: Any, actualBytes: Long) { actuals += actualBytes }
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
}
