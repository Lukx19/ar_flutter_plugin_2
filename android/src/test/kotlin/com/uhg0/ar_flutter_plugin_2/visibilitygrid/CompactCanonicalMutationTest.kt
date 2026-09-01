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

class CompactCanonicalMutationTest {
    private val plans = java.util.IdentityHashMap<PreparedIntent, PreparedCanonicalMutation>()
    @Test
    fun `all seven mutation kinds persist exact complete private semantics`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-seven-kinds").toFile()
        try {
            val activeRows = listOf(
                row(1, Voxel(0, 0, 0)), row(2, Voxel(1, 0, 0)),
                row(3, Voxel(2, 0, 0)), row(4, Voxel(3, 0, 0)),
            )
            data class Scenario(
                val name: String,
                val kind: PreparedMutationKind,
                val base: TestView,
                val command: Any,
                val expected: Map<Long, Voxel>,
                val removed: Set<Long>,
                val support: Map<Long, List<Long>>,
                val lineage: Map<Long, List<Long>>,
                val expectedKinds: Set<CowFragmentKind>,
            )
            val rowKinds = setOf(CowFragmentKind.ROW, CowFragmentKind.ID_INDEX, CowFragmentKind.VOXEL_INDEX, CowFragmentKind.PAGE_INDEX)
            val evidenceKinds = setOf(CowFragmentKind.SOURCE, CowFragmentKind.SUPPORT)
            val tombstoneKinds = setOf(CowFragmentKind.ID_TOMBSTONE, CowFragmentKind.VOXEL_TOMBSTONE, CowFragmentKind.PAGE_TOMBSTONE, CowFragmentKind.SUPPORT_TOMBSTONE, CowFragmentKind.LINEAGE_TOMBSTONE)
            val relocationKinds = rowKinds + setOf(CowFragmentKind.SUPPORT, CowFragmentKind.LINEAGE) + tombstoneKinds
            val replacementKinds = rowKinds + evidenceKinds + setOf(CowFragmentKind.LINEAGE) + tombstoneKinds
            fun active(name: String) = view(name, activeRows)
            val scenarios = listOf(
                Scenario("feature-add", PreparedMutationKind.FEATURE_ADD, view("feature-add", emptyList()),
                    FeatureMutationCommand("feature-add", 0, 0, target(null, Voxel(10, 0, 0))),
                    mapOf(1L to Voxel(10, 0, 0)), emptySet(), mapOf(1L to listOf(1L)), emptyMap(), rowKinds + evidenceKinds),
                Scenario("feature-refine", PreparedMutationKind.FEATURE_REFINE, view("feature-refine", listOf(row(1, Voxel(0, 0, 0)))),
                    FeatureMutationCommand("feature-refine", 0, 0, target(SurfaceId(1), Voxel(0, 0, 0), 191)),
                    mapOf(1L to Voxel(0, 0, 0)), emptySet(), mapOf(1L to listOf(1L)), emptyMap(), rowKinds),
                Scenario("create", PreparedMutationKind.CREATE, view("create", emptyList()),
                    CanonicalTransactionCommand("create", CanonicalOperation.CREATE, 0, 0, emptyList(), listOf(target(null, Voxel(10, 0, 0)), target(null, Voxel(11, 0, 0)))),
                    mapOf(1L to Voxel(10, 0, 0), 2L to Voxel(11, 0, 0)), emptySet(), mapOf(1L to listOf(1L), 2L to listOf(2L)), emptyMap(), rowKinds + evidenceKinds),
                Scenario("relocation", PreparedMutationKind.RELOCATION, active("relocation"),
                    CanonicalTransactionCommand("relocation", CanonicalOperation.RELOCATION, 0, 0, listOf(SurfaceId(1)), listOf(target(SurfaceId(1), Voxel(10, 0, 0)))),
                    mapOf(1L to Voxel(10, 0, 0), 2L to Voxel(1, 0, 0), 3L to Voxel(2, 0, 0), 4L to Voxel(3, 0, 0)), emptySet(), mapOf(1L to listOf(1L), 2L to listOf(2L), 3L to listOf(3L), 4L to listOf(4L)), mapOf(1L to listOf(1L)), relocationKinds),
                Scenario("merge", PreparedMutationKind.MERGE, active("merge"),
                    CanonicalTransactionCommand("merge", CanonicalOperation.MERGE, 0, 0, listOf(SurfaceId(2), SurfaceId(3)), listOf(target(null, Voxel(20, 0, 0)))),
                    mapOf(1L to Voxel(0, 0, 0), 4L to Voxel(3, 0, 0), 5L to Voxel(20, 0, 0)), setOf(2L, 3L), mapOf(1L to listOf(1L), 4L to listOf(4L), 5L to listOf(2L, 3L)), mapOf(2L to listOf(5L), 3L to listOf(5L)), replacementKinds),
                Scenario("split", PreparedMutationKind.SPLIT, active("split"),
                    CanonicalTransactionCommand("split", CanonicalOperation.SPLIT, 0, 0, listOf(SurfaceId(4)), listOf(target(null, Voxel(30, 0, 0)), target(null, Voxel(31, 0, 0)))),
                    mapOf(1L to Voxel(0, 0, 0), 2L to Voxel(1, 0, 0), 3L to Voxel(2, 0, 0), 5L to Voxel(30, 0, 0), 6L to Voxel(31, 0, 0)), setOf(4L), mapOf(1L to listOf(1L), 2L to listOf(2L), 3L to listOf(3L), 5L to listOf(4L), 6L to listOf(4L)), mapOf(4L to listOf(5L, 6L)), replacementKinds),
                Scenario("replacement", PreparedMutationKind.REPLACEMENT, active("replacement"),
                    CanonicalTransactionCommand("replacement", CanonicalOperation.REPLACEMENT, 0, 0, listOf(SurfaceId(1)), listOf(target(null, Voxel(40, 0, 0)))),
                    mapOf(2L to Voxel(1, 0, 0), 3L to Voxel(2, 0, 0), 4L to Voxel(3, 0, 0), 5L to Voxel(40, 0, 0)), setOf(1L), mapOf(2L to listOf(2L), 3L to listOf(3L), 4L to listOf(4L), 5L to listOf(1L)), mapOf(1L to listOf(5L)), replacementKinds),
            )
            scenarios.forEach { scenario ->
                val plan = when (val command = scenario.command) {
                    is FeatureMutationCommand -> SurfaceOwnership.prepareMutation(
                        scenario.base, SurfaceOwnershipConfiguration(), command,
                    )
                    is CanonicalTransactionCommand -> SurfaceOwnership.prepareMutation(
                        scenario.base, SurfaceOwnershipConfiguration(), command,
                    )
                    else -> error("unknown command")
                }.let { (it as CanonicalMutationPreparation.Prepared).mutation }
                val intent = when (val command = scenario.command) {
                    is FeatureMutationCommand -> intent(scenario.base, command, File(directory, "${scenario.name}-intent"))
                    is CanonicalTransactionCommand -> intent(scenario.base, command, File(directory, "${scenario.name}-intent"))
                    else -> error("unknown command")
                }
                val exactCurrent = ByteArrayOutputStream().also { output ->
                    assertTrue(intent.visitCurrent(PreparedIntentVisitor.NONE, output) is PreparedIntentVisitResult.Complete)
                }.toByteArray()
                val generation = stage(scenario.base, intent, File(directory, "${scenario.name}-generations"))
                assertEquals(scenario.kind, generation.root.commandKind)
                assertEquals(scenario.name, scenario.expectedKinds, generation.root.manifest.mapTo(mutableSetOf()) { it.kind })
                assertArrayEquals(exactCurrent, File(generation.directory, CanonicalCowGeneration.CURRENT_UNACKED_FILE).readBytes())
                val overlay = generation.overlay(scenario.base)
                val commitOwner = requireNotNull(CanonicalCommitStore.open(
                    File(directory, "${scenario.name}-commit"), RecordingBudget(),
                ))
                val committed = commitOwner.commit(plan, scenario.base) as CanonicalCommitResult.Committed
                assertEquals(scenario.kind, committed.commit.roots.last().commandKind)
                assertEquals(scenario.expected.size, committed.commit.view.cut.liveSurfaceCount)
                committed.commit.close(); commitOwner.close()
                scenario.expected.forEach { (id, voxel) ->
                    assertEquals("${scenario.name} id=$id", voxel, overlay.findById(SurfaceId(id))?.voxel)
                    assertEquals("${scenario.name} voxel=$voxel", id, overlay.findByVoxel(voxel)?.id?.value)
                    assertNotNull("${scenario.name} source=$id", (overlay.readSourceById(SurfaceId(id)) as CanonicalPageRead.Complete).value)
                    val location = requireNotNull(CompactLocation(SurfaceOwnershipConfiguration(), voxel))
                    assertTrue("${scenario.name} page=$id", overlay.readPage(location.region, location.page, 0, 512).rows.any { it.id.value == id && it.voxel == voxel })
                }
                scenario.removed.forEach { id ->
                    assertNull("${scenario.name} removed=$id", overlay.findById(SurfaceId(id)))
                    assertNotNull("${scenario.name} historical source=$id", (overlay.readSourceById(SurfaceId(id)) as CanonicalPageRead.Complete).value)
                    val removedSupport = mutableListOf<Long>()
                    assertTrue(overlay.visitSourceSupport(SurfaceId(id), null) { removedSupport += it.source.id.value; true } is SourceSupportRead.Complete)
                    assertTrue("${scenario.name} removed support=$id", removedSupport.isEmpty())
                }
                scenario.base.rowsSnapshot().filter { old -> scenario.expected[old.id.value] != old.voxel }.forEach { old ->
                    assertNull("${scenario.name} old voxel=${old.voxel}", overlay.findByVoxel(old.voxel))
                    val location = requireNotNull(CompactLocation(SurfaceOwnershipConfiguration(), old.voxel))
                    assertTrue("${scenario.name} old page=${old.id.value}", overlay.readPage(location.region, location.page, 0, 512).rows.none { it.id == old.id && it.voxel == old.voxel })
                }
                scenario.support.forEach { (targetId, expectedSources) ->
                    val actual = mutableListOf<Long>()
                    assertTrue(overlay.visitSourceSupport(SurfaceId(targetId), null) { actual += it.source.id.value; true } is SourceSupportRead.Complete)
                    assertEquals("${scenario.name} support=$targetId", expectedSources, actual)
                }
                (scenario.base.rowsSnapshot().map { it.id.value } + scenario.expected.keys + scenario.lineage.keys).distinct().forEach { sourceId ->
                    val actual = mutableListOf<Long>()
                    assertTrue(overlay.visitLineage(SurfaceId(sourceId), null) { actual += it.target.value; true } is LineageRead.Complete)
                    assertEquals("${scenario.name} lineage=$sourceId", scenario.lineage[sourceId].orEmpty(), actual)
                }
                assertEquals(scenario.expected.size, overlay.cut.liveSurfaceCount)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `existing overlay fails closed after generation close without base fallback`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-close").toFile()
        try {
            val base = view("close", listOf(row(1, Voxel(0, 0, 0)), row(2, Voxel(1, 0, 0))))
            val preparedIntent = intent(
                base,
                FeatureMutationCommand("close", 0, 0, target(SurfaceId(1), Voxel(0, 0, 0), 191)),
                File(directory, "intent"),
            )
            val generation = stage(base, preparedIntent, File(directory, "generations"))
            val overlay = generation.overlay(base)
            assertNotNull(overlay.findById(SurfaceId(2)))
            val location = requireNotNull(CompactLocation(SurfaceOwnershipConfiguration(), Voxel(1, 0, 0)))
            assertTrue(overlay.readPage(location.region, location.page, 0, 10).rows.isNotEmpty())

            val enteredBaseFallback = CountDownLatch(1)
            val releaseBaseFallback = CountDownLatch(1)
            val closeReturned = CountDownLatch(1)
            val concurrentRead = AtomicReference<CompactSurface?>()
            base.beforeFindById = { id ->
                if (id == SurfaceId(2)) {
                    enteredBaseFallback.countDown()
                    assertTrue(releaseBaseFallback.await(5, TimeUnit.SECONDS))
                }
            }
            val reader = Thread { concurrentRead.set(overlay.findById(SurfaceId(2))) }.also { it.start() }
            assertTrue(enteredBaseFallback.await(5, TimeUnit.SECONDS))
            val closer = Thread { generation.close(); closeReturned.countDown() }.also { it.start() }
            assertFalse("close crossed an in-flight generation read", closeReturned.await(100, TimeUnit.MILLISECONDS))
            releaseBaseFallback.countDown()
            reader.join(5_000); closer.join(5_000)
            assertFalse(reader.isAlive); assertFalse(closer.isAlive)
            assertNotNull(concurrentRead.get())
            assertEquals(0L, closeReturned.count)

            assertNull(overlay.findById(SurfaceId(2)))
            assertNull(overlay.findByVoxel(Voxel(1, 0, 0)))
            assertTrue(overlay.readPage(location.region, location.page, 0, 10).rows.isEmpty())
            assertEquals(CompactCanonicalRefusal.CLOSED, (overlay.readSourceById(SurfaceId(2)) as CanonicalPageRead.Refused).reason)
            assertEquals(CompactCanonicalRefusal.CLOSED, (overlay.visitSourceSupport(SurfaceId(2), null) { true } as SourceSupportRead.Refused).reason)
            assertEquals(CompactCanonicalRefusal.CLOSED, (overlay.visitLineage(SurfaceId(2), null) { true } as LineageRead.Refused).reason)
            assertEquals(CompactCanonicalRefusal.CLOSED, (generation.readPage(base, location.region, location.page, null, 10) as CowPageRead.Refused).reason)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `physical reservation reconciles every candidate file and changed command bytes conflict`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-physical").toFile()
        try {
            val base = view("physical", emptyList())
            val firstIntent = intent(base, FeatureMutationCommand("same-command", 0, 0, target(null, Voxel(0, 0, 0))), File(directory, "intent-a"))
            val generationParent = File(directory, "generations").also { assertTrue(it.mkdirs()) }
            StorageBudgetCoordinatorV2(
                generationParent, StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
                JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                freeBytes = { 128L * 1024 * 1024 },
            ).use { coordinator ->
                val budget = CoordinatorStorageBudget(coordinator)
                val store = requireNotNull(CanonicalCommitStore.open(generationParent, budget))
                val prepared = stage(store, firstIntent, base, generationParent)
                val physical = coordinator.physicallyAllocatedTreeBytes(prepared.generation.directory)
                assertTrue(physical >= prepared.generation.storageReceipt().allocatedBytes)
                assertTrue(coordinator.committedBytes() >= physical)
                assertEquals(0L, coordinator.reservedBytes())
                prepared.generation.root.manifest.groupBy { it.file }.forEach { (name, entries) ->
                    assertEquals(entries.size.toLong() * CanonicalCowGeneration.PAGE_BYTES, File(prepared.generation.directory, name).length())
                }

                val changedIntent = intent(base, FeatureMutationCommand("same-command", 0, 0, target(null, Voxel(1, 0, 0))), File(directory, "intent-b"))
                val conflict = store.commit(requireNotNull(plans[changedIntent]), base) as CanonicalCommitResult.Refused
                assertEquals(CanonicalCommitRefusal.CURRENT_PENDING, conflict.reason)
                assertTrue(coordinator.committedBytes() >= physical)

                val page = File(prepared.generation.directory, prepared.generation.root.manifest.first().file)
                java.io.RandomAccessFile(page, "rw").use { file ->
                    file.seek(20)
                    val original = file.read()
                    file.seek(20)
                    file.write(original xor 0x55)
                    file.fd.sync()
                }
                val corrupt = store.reopen(base) as CanonicalReopenResult.Refused
                assertEquals(CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT, corrupt.reason)
                assertEquals(0L, coordinator.reservedBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `complete private generation contains all semantic kinds exact current and tombstones`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-complete").toFile()
        try {
            val empty = view("cow-add", emptyList())
            val add = FeatureMutationCommand("add", 0, 0, target(null, Voxel(0, 0, 0)))
            val addIntent = intent(empty, add, File(directory, "add-intent"))
            val budget = RecordingBudget()
            val store = requireNotNull(CanonicalCommitStore.open(File(directory, "generations"), budget))
            val added = stage(store, addIntent, empty, File(directory, "generations"))
            val addKinds = added.generation.root.manifest.mapTo(mutableSetOf()) { it.kind }
            assertTrue(addKinds.containsAll(setOf(
                CowFragmentKind.ROW, CowFragmentKind.ID_INDEX, CowFragmentKind.VOXEL_INDEX,
                CowFragmentKind.PAGE_INDEX, CowFragmentKind.SOURCE, CowFragmentKind.SUPPORT,
            )))
            val addedView = added.generation.overlay(empty)
            val row = addedView.findById(SurfaceId(1)); assertNotNull(row); row!!
            assertEquals(Voxel(0, 0, 0), row.voxel)
            assertNotNull((addedView.readSourceById(SurfaceId(1)) as CanonicalPageRead.Complete).value)
            val support = mutableListOf<PagedSupport>()
            assertTrue(addedView.visitSourceSupport(SurfaceId(1), null) { support += it; true } is SourceSupportRead.Complete)
            assertEquals(listOf(1L), support.map { it.source.id.value })
            val location = requireNotNull(CompactLocation(SurfaceOwnershipConfiguration(), Voxel(0, 0, 0)))
            val cowPage = added.generation.readPage(empty, location.region, location.page, null, 1) as CowPageRead.Complete
            assertEquals(listOf(1L), cowPage.rows.map { it.id.value })
            val stalePage = added.generation.readPage(empty, location.region, location.page,
                CowPageCursor(CanonicalReceiptBytes(ByteArray(32)), location.region, location.page, 0, 0, 0), 1)
            assertEquals(CompactCanonicalRefusal.STALE_CURSOR, (stalePage as CowPageRead.Refused).reason)
            assertEquals(added.generation.root.current.length, File(added.generation.directory, CanonicalCowGeneration.CURRENT_UNACKED_FILE).length())
            assertTrue(added.generation.storageReceipt().phasePeakBytes <= 1_048_576L)
            assertTrue(requireNotNull(budget.requests.maxOrNull()) >= requireNotNull(budget.actuals.maxOrNull()))

            val replay = store.commit(requireNotNull(plans[addIntent]), empty) as CanonicalCommitResult.Committed
            assertTrue(replay.replayed)
            replay.commit.close()

            val baseRow = row(1, Voxel(0, 0, 0))
            val base = view("cow-relocate", listOf(baseRow))
            val baseRootBefore = base.cut.rootHash
            val relocation = CanonicalTransactionCommand(
                "relocate", CanonicalOperation.RELOCATION, 0, 0, listOf(SurfaceId(1)),
                listOf(target(SurfaceId(1), Voxel(2, 0, 0))),
            )
            val relocationIntent = intent(base, relocation, File(directory, "relocate-intent"))
            val relocatedParent = File(directory, "relocated-generations")
            val relocated = stage(requireNotNull(CanonicalCommitStore.open(relocatedParent, budget)), relocationIntent, base, relocatedParent)
            val kinds = relocated.generation.root.manifest.mapTo(mutableSetOf()) { it.kind }
            assertTrue(kinds.contains(CowFragmentKind.LINEAGE))
            assertTrue(kinds.containsAll(setOf(
                CowFragmentKind.ID_TOMBSTONE, CowFragmentKind.VOXEL_TOMBSTONE,
                CowFragmentKind.PAGE_TOMBSTONE, CowFragmentKind.SUPPORT_TOMBSTONE,
                CowFragmentKind.LINEAGE_TOMBSTONE,
            )))
            val relocatedView = relocated.generation.overlay(base)
            assertEquals(Voxel(2, 0, 0), relocatedView.findById(SurfaceId(1))?.voxel)
            assertNull(relocatedView.findByVoxel(Voxel(0, 0, 0)))
            assertEquals(SurfaceId(1), relocatedView.findByVoxel(Voxel(2, 0, 0))?.id)
            assertNotNull((relocatedView.readSourceById(SurfaceId(1)) as CanonicalPageRead.Complete).value)
            val lineage = mutableListOf<LineageEdge>()
            val lineageRead = relocatedView.visitLineage(SurfaceId(1), null) { lineage += it; true }
            assertEquals(1, (lineageRead as LineageRead.Complete).delivered)
            assertEquals(listOf(LineageEdge(SurfaceId(1), SurfaceId(1))), lineage)
            assertEquals(baseRootBefore, base.cut.rootHash)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `small and 100k real v6 cuts stage identical one-row dirty work without dense reads`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-scale").toFile()
        try {
            val small = realV6("scale-small", 1, File(directory, "small-v6"))
            val large = realV6("scale-large", 100_000, File(directory, "large-v6"))
            val command = { name: String -> FeatureMutationCommand(name, 0, 0, target(SurfaceId(1), Voxel(0, 0, 0), 191)) }
            val smallIntent = intent(small, command("same"), File(directory, "small-intent"))
            val largeIntent = intent(large, command("same"), File(directory, "large-intent"))
            val smallBefore = small.readWorkReceipt(); val largeBefore = large.readWorkReceipt()
            val smallGeneration = stage(small, smallIntent, File(directory, "small"))
            val largeGeneration = stage(large, largeIntent, File(directory, "large"))
            fun signature(generation: CanonicalCowGeneration) = generation.root.manifest.map { Triple(it.kind, it.count, it.hash.toList()) }
            assertEquals(signature(smallGeneration), signature(largeGeneration))
            val smallWork = small.readWorkReceipt() - smallBefore; val largeWork = large.readWorkReceipt() - largeBefore
            assertEquals(smallWork.directLookups, largeWork.directLookups)
            assertEquals(smallWork.pageReads, largeWork.pageReads)
            assertTrue("large inspected=${largeWork.inspectedRows}", largeWork.inspectedRows < 64)
            assertEquals(0, smallWork.pageReads)
            assertEquals(0, largeWork.pageReads)

            val voxel = Voxel(0, 0, 0)
            val location = requireNotNull(CompactLocation(SurfaceOwnershipConfiguration(), voxel))
            fun queryReceipt(generation: CanonicalCowGeneration, base: CanonicalStateView): CowReadWork {
                val before = generation.readWorkReceipt()
                assertEquals(SurfaceId(1), generation.overlay(base).findByVoxel(voxel)?.id)
                val page = generation.readPage(base, location.region, location.page, null, 1) as CowPageRead.Complete
                assertEquals(listOf(1L), page.rows.map { it.id.value })
                return generation.readWorkReceipt() - before
            }
            assertEquals(queryReceipt(smallGeneration, small), queryReceipt(largeGeneration, large))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `same-page dirty and tombstone indexes stream across radix windows in canonical order`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-dense-page").toFile()
        try {
            val count = 700
            fun voxel(index: Int) = Voxel(index / 100, (index / 10) % 10, index % 10)
            val location = requireNotNull(CompactLocation(SurfaceOwnershipConfiguration(), voxel(0)))

            val empty = view("dense-create", emptyList())
            val createTargets = (0 until count).reversed().map { target(null, voxel(it)) }
            val create = CanonicalTransactionCommand(
                "dense-create", CanonicalOperation.CREATE, 0, 0, emptyList(), createTargets,
            )
            val created = stage(empty, intent(empty, create, File(directory, "create-intent")), File(directory, "create-generation"))
            val actual = mutableListOf<CompactSurface>()
            var cursor: CowPageCursor? = null
            do {
                val before = created.readWorkReceipt()
                val read = created.readPage(empty, location.region, location.page, cursor, 73) as CowPageRead.Complete
                actual += read.rows
                cursor = read.nextCursor
                val work = created.readWorkReceipt() - before
                assertTrue("unbounded dense dirty pages=$work", work.pages <= 75)
                assertTrue("unbounded dense dirty records=$work", work.records <= 21_000)
            } while (cursor != null)
            assertEquals(count, actual.size)
            assertEquals(count, actual.map { it.id }.toSet().size)
            assertEquals((0 until count).map(::voxel), actual.map { it.voxel })

            val indexEntry = created.root.manifest.first { it.kind == CowFragmentKind.PAGE_INDEX }
            java.io.RandomAccessFile(File(created.directory, indexEntry.file), "rw").use { file ->
                file.seek(indexEntry.page.toLong() * CanonicalCowGeneration.PAGE_BYTES + 100)
                val original = file.read()
                file.seek(indexEntry.page.toLong() * CanonicalCowGeneration.PAGE_BYTES + 100)
                file.write(original xor 0x5a)
                file.fd.sync()
            }
            assertEquals(
                CompactCanonicalRefusal.CORRUPT,
                (created.readPage(empty, location.region, location.page, null, 73) as CowPageRead.Refused).reason,
            )

            val baseRows = (0 until count).map { row(it + 1L, voxel(it)) }
            val base = view("dense-remove", baseRows)
            val replacement = CanonicalTransactionCommand(
                "dense-remove", CanonicalOperation.REPLACEMENT, 0, 0,
                baseRows.map { it.id }, listOf(target(null, Voxel(20, 0, 0))),
            )
            val removed = stage(base, intent(base, replacement, File(directory, "remove-intent")), File(directory, "remove-generation"))
            cursor = null
            var windows = 0
            do {
                val before = removed.readWorkReceipt()
                val read = removed.readPage(base, location.region, location.page, cursor, 73) as CowPageRead.Complete
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
        val directory = Files.createTempDirectory("canonical-surface-cow-dirty-scale").toFile()
        try {
            val configuration = SurfaceOwnershipConfiguration()
            val requested = requireNotNull(CompactLocation(configuration, Voxel(0, 0, 0)))
            val requestedPageKey = CanonicalCowGeneration.pageKey(requested.region, requested.page)
            val common = (0 until 1_000).map { Voxel(it / 100, (it / 10) % 10, it % 10) }
                .sortedWith { a, b -> java.lang.Long.compareUnsigned(
                    CanonicalCowGeneration.voxelKey(a.x, a.y, a.z),
                    CanonicalCowGeneration.voxelKey(b.x, b.y, b.z),
                ) }
                .take(CowFragmentKind.VOXEL_INDEX.recordsPerPage)
            val lookupVoxel = common.minWith { a, b -> compareVoxel(a.x, a.y, a.z, b.x, b.y, b.z) }
            val commonTargets = (listOf(lookupVoxel) + common.filter { it != lookupVoxel }).map { target(null, it) }
            val voxelThreshold = common.maxOfWith(
                Comparator { a, b -> java.lang.Long.compareUnsigned(a, b) },
            ) { CanonicalCowGeneration.voxelKey(it.x, it.y, it.z) }
            val fillers = ArrayList<CanonicalTarget>()
            var candidate = 0
            while (fillers.size < 5_182) {
                val value = Voxel(10 + candidate / 100, (candidate / 10) % 10, candidate % 10)
                candidate++
                val location = requireNotNull(CompactLocation(configuration, value))
                if (java.lang.Long.compareUnsigned(CanonicalCowGeneration.pageKey(location.region, location.page), requestedPageKey) <= 0) continue
                if (java.lang.Long.compareUnsigned(CanonicalCowGeneration.voxelKey(value.x, value.y, value.z), voxelThreshold) <= 0) continue
                fillers += target(null, value)
            }

            fun generation(name: String, targets: List<CanonicalTarget>): Pair<TestView, CanonicalCowGeneration> {
                val base = view(name, emptyList())
                val command = CanonicalTransactionCommand(name, CanonicalOperation.CREATE, 0, 0, emptyList(), targets)
                return base to stage(base, intent(base, command, File(directory, "$name-intent")), File(directory, "$name-generation"))
            }
            val (smallBase, small) = generation("dirty-small", commonTargets)
            val largeBase = view("dirty-large", emptyList())
            val candidates = commonTargets + fillers
            fun preparation(count: Int) = SurfaceOwnership.prepareMutation(
                largeBase,
                configuration,
                CanonicalTransactionCommand("dirty-large", CanonicalOperation.CREATE, 0, 0, emptyList(), candidates.take(count)),
            )
            assertTrue(preparation(candidates.size) is CanonicalMutationPreparation.Refused)
            var admitted = commonTargets.size
            var refused = candidates.size
            while (refused - admitted > 1) {
                val middle = admitted + (refused - admitted) / 2
                if (preparation(middle) is CanonicalMutationPreparation.Prepared) admitted = middle else refused = middle
            }
            assertTrue(admitted > commonTargets.size)
            val largePlan = (preparation(admitted) as CanonicalMutationPreparation.Prepared).mutation
            val largeIntent = flush(largeBase, largePlan, File(directory, "dirty-large-intent"))
            val large = stage(largeBase, largeIntent, File(directory, "dirty-large-generation"))
            val phase = large.storageReceipt()
            assertEquals(CanonicalCowGeneration.phasePeakBytes(large.root.manifest.size), phase.phasePeakBytes)
            assertTrue("maximum admitted dirty phase=$phase", phase.phasePeakBytes <= 1_048_576L)
            fun receipt(base: TestView, generation: CanonicalCowGeneration): CowReadWork {
                val before = generation.readWorkReceipt()
                assertEquals(SurfaceId(1), generation.overlay(base).findByVoxel(lookupVoxel)?.id)
                val page = generation.readPage(base, requested.region, requested.page, null, 1) as CowPageRead.Complete
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
        val directory = Files.createTempDirectory("canonical-surface-cow-lineage-membership").toFile()
        try {
            val base = view(
                "lineage-membership",
                (1L..4L).map { row(it, Voxel(it.toInt(), 0, 0)) },
                lineage = listOf(LineageEdge(SurfaceId(3), SurfaceId(99))),
            )
            val command = CanonicalTransactionCommand(
                "merge-non-adjacent", CanonicalOperation.MERGE, 0, 0,
                listOf(SurfaceId(2), SurfaceId(4)), listOf(target(null, Voxel(20, 0, 0))),
            )
            val generation = stage(base, intent(base, command, File(directory, "intent")), File(directory, "generation"))
            val actual = mutableListOf<LineageEdge>()
            val read = generation.overlay(base).visitLineage(SurfaceId(3), null) { actual += it; true }
            assertTrue(read is LineageRead.Complete)
            assertEquals(listOf(LineageEdge(SurfaceId(3), SurfaceId(99))), actual)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `same-root support cursor rejects malformed candidate ordinal and matching offset`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-support-cursor").toFile()
        try {
            val base = view("support-cursor", emptyList())
            val generation = stage(
                base,
                intent(base, FeatureMutationCommand("support-cursor", 0, 0, target(null, Voxel(0, 0, 0))), File(directory, "intent")),
                File(directory, "generation"),
            )
            val overlay = generation.overlay(base)
            val target = SurfaceId(1)
            val stopped = overlay.visitSourceSupport(target, null) { false } as SourceSupportRead.Complete
            val valid = requireNotNull(stopped.nextCursor)
            fun refusal(cursor: SourceSupportCursor) = overlay.visitSourceSupport(target, cursor) { true } as SourceSupportRead.Refused
            assertEquals(CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(ordinal = Int.MAX_VALUE)).reason)
            assertEquals(CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(ordinal = -1)).reason)
            assertEquals(CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(offset = -1)).reason)
            assertEquals(CompactCanonicalRefusal.STALE_CURSOR, refusal(valid.copy(offset = 2)).reason)
            val delivered = mutableListOf<Long>()
            val resumed = overlay.visitSourceSupport(target, valid) { delivered += it.source.id.value; true }
            assertTrue(resumed is SourceSupportRead.Complete)
            assertEquals(listOf(1L), delivered)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `every stage cut leaves old authority and later yields complete or reusable generation`() {
        CanonicalCowFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("canonical-surface-cow-fault-${fault.name}").toFile()
            try {
                val base = view("fault-${fault.name}", emptyList())
                val intent = intent(base, FeatureMutationCommand("fault", 0, 0, target(null, Voxel(0, 0, 0))), File(directory, "intent"))
                val parent = File(directory, "generations")
                val first = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget())).commit(
                    requireNotNull(plans[intent]), base, CanonicalCommitFaults(cow = fault),
                )
                assertTrue("$fault returned $first", first is CanonicalCommitResult.Refused)
                assertEquals(0, base.cut.liveSurfaceCount)
                val recovered = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget())).commit(requireNotNull(plans[intent]), base)
                assertTrue("$fault recovery returned $recovered", recovered is CanonicalCommitResult.Committed)
                (recovered as CanonicalCommitResult.Committed).commit.close()
                val prepared = CanonicalCowStageResult.Prepared(
                    requireNotNull(parent.listFiles().orEmpty().singleOrNull { it.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) }?.let(CanonicalCowGeneration::open)), true,
                )
                assertEquals(1, prepared.generation.overlay(base).cut.liveSurfaceCount)
                assertTrue(parent.listFiles().orEmpty().none { it.name.endsWith(".staging") })
            } finally { directory.deleteRecursively() }
        }
    }

    private fun stage(base: CanonicalStateView, intent: PreparedIntent, parent: File) =
        stage(requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget())), intent, base, parent).generation

    private fun stage(store: CanonicalCommitStore, intent: PreparedIntent, base: CanonicalStateView, parent: File): CanonicalCowStageResult.Prepared {
        val committed = store.commit(requireNotNull(plans[intent]), base) as CanonicalCommitResult.Committed
        committed.commit.close()
        val generation = requireNotNull(parent.listFiles().orEmpty().singleOrNull { it.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) }?.let(CanonicalCowGeneration::open))
        return CanonicalCowStageResult.Prepared(generation, committed.replayed)
    }

    private fun prepared(result: CanonicalCowStageResult): CanonicalCowStageResult.Prepared =
        result as? CanonicalCowStageResult.Prepared ?: error("stage refused: $result")

    private fun intent(view: CanonicalStateView, command: FeatureMutationCommand, directory: File): PreparedIntent {
        val plan = (SurfaceOwnership.prepareMutation(view, SurfaceOwnershipConfiguration(), command) as CanonicalMutationPreparation.Prepared).mutation
        return flush(view, plan, directory)
    }

    private fun intent(view: TestView, command: CanonicalTransactionCommand, directory: File): PreparedIntent {
        val plan = (SurfaceOwnership.prepareMutation(view, SurfaceOwnershipConfiguration(), command) as CanonicalMutationPreparation.Prepared).mutation
        return flush(view, plan, directory)
    }

    private fun flush(view: CanonicalStateView, plan: PreparedCanonicalMutation, directory: File): PreparedIntent {
        val journal = (CanonicalDirtyJournal.open(view, directory, RecordingBudget()) as CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan) as CanonicalDirtyJournalFlushResult.Prepared).intent.also { plans[it] = plan }
    }

    private fun target(id: SurfaceId?, voxel: Voxel, confidence: Int = 192) = CanonicalTarget(id, voxel, 0, 0, confidence)
    private fun row(id: Long, voxel: Voxel) = CompactSurface(SurfaceId(id), voxel, 0, 192)
    private fun hash(text: String) = CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))

    private fun realV6(name: String, count: Int, directory: File): CompactCanonicalStore {
        assertTrue(directory.mkdirs())
        val group = SurfaceGroup(name)
        val prefix = MessageDigest.getInstance("SHA-256").digest(group.value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val ledgerBody = java.io.ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d33524c); out.writeInt(1); out.writeLong(1); out.writeLong(1); out.writeLong(count + 1L)
                out.write(group.hash); out.write(ByteArray(32) { 1 }); out.write(ByteArray(32) { 2 }); out.write(ByteArray(32))
            }
            raw.toByteArray()
        }
        File(directory, "canonical-surface-surface-$prefix.ledger").writeBytes(ledgerBody + MessageDigest.getInstance("SHA-256").digest(ledgerBody))
        val snapshot = File(directory, "canonical-surface-surface-$prefix.snapshot")
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
        val prepared = CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        assertTrue(prepared.toString(), prepared is CompactCanonicalMigrationResult.Prepared)
        return (CompactCanonicalStore.openV6(group, directory, budget) as CompactCanonicalOpenResult.Opened).store
    }

    private fun view(
        name: String,
        rows: List<CompactSurface>,
        declaredRows: Int = rows.size,
        lineage: List<LineageEdge> = emptyList(),
    ): TestView {
        val cut = CompactCanonicalCut(SurfaceGroup(name), CompactCanonicalStore.PROFILE, 0, 0, declaredRows + 1L,
            declaredRows, declaredRows, declaredRows, lineage.size, null, hash("root-$name"), hash("source-$name"))
        return TestView(cut, rows, lineage)
    }

    private class TestView(
        override val cut: CompactCanonicalCut,
        private val rows: List<CompactSurface>,
        private val lineage: List<LineageEdge>,
    ) : CanonicalStateView {
        var lookups = 0
        var pageScans = 0
        var beforeFindById: ((SurfaceId) -> Unit)? = null
        fun rowsSnapshot() = rows.toList()
        override fun findById(id: SurfaceId): CompactSurface? { beforeFindById?.invoke(id); lookups++; return rows.firstOrNull { it.id == id } }
        override fun findByVoxel(voxel: Voxel): CompactSurface? { lookups++; return rows.firstOrNull { it.voxel == voxel } }
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int): CompactPage {
            pageScans++
            val matching = rows.filter { row -> CompactLocation(SurfaceOwnershipConfiguration(), row.voxel)?.let { it.region == region && it.page == page } == true }
            val end = minOf(matching.size, cursor + limit)
            return CompactPage(if (cursor <= matching.size) matching.subList(cursor, end) else emptyList(), if (end < matching.size) end else null, matching.size)
        }
        override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> = CanonicalPageRead.Complete(rows.firstOrNull { it.id == id }?.let {
            PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, CanonicalReceiptBytes(ByteArray(32)))
        }, 0, 0)
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead {
            val source = rows.firstOrNull { it.id == target }
            val delivered = if (source != null && sink(PagedSupport(target, PagedSource(source.id, source.voxel, source.packedNormal, source.normalConfidence, CanonicalReceiptBytes(ByteArray(32)))))) 1 else 0
            return SourceSupportRead.Complete(delivered, null, 0, 0)
        }
        override fun visitLineage(source: SurfaceId, cursor: LineageCursor?, sink: (LineageEdge) -> Boolean): LineageRead {
            val matches = lineage.filter { it.source == source }
            var delivered = 0
            matches.drop(cursor?.offset ?: 0).forEach { if (!sink(it)) return LineageRead.Complete(delivered, LineageCursor(cut.rootHash, source, delivered)); delivered++ }
            return LineageRead.Complete(delivered, null)
        }
        override fun retainedMemoryReceipt() = error("unused")
        override fun allocatedStorageReceipt() = error("unused")
        override fun close() = Unit
    }

    private class RecordingBudget : ExclusiveFakeStorageBudget() {
        val requests = mutableListOf<Long>()
        val actuals = mutableListOf<Long>()
        override fun reserveBytes(bytes: Long): Any = bytes
        override fun reserveCandidate(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long): Any? {
            requests += maximumPhysicalBytes
            return super.reserveCandidate(staging, target, fileBytes, maximumPhysicalBytes)
        }
        override fun verifyCandidate(token: Any, candidate: File): Long = allocatedBytes(candidate).also { actuals += it }
        override fun commitBytes(token: Any, actualBytes: Long) { actuals += actualBytes }
        override fun releaseBytes(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
}
