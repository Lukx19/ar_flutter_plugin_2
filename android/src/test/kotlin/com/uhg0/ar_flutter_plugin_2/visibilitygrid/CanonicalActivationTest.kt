package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalActivationTest {
    @Test
    fun `forged plan facts fail before files reservation burn or accounting`() {
        val forgeries: List<(CanonicalActivationPlan) -> CanonicalActivationPlan> = listOf(
            { plan -> CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut, CanonicalActivationCurrent.None, plan.receipt) },
            { plan ->
                val current = plan.current as CanonicalActivationCurrent.Receipt
                CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut,
                    current.copy(source = CanonicalCurrentSource { output -> current.source.writeTo(output) }), plan.receipt)
            },
            { plan -> CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut.copy(), plan.current, plan.receipt) },
        )
        forgeries.forEachIndexed { index, forge -> fixture { group, directory ->
            val legacy = opened(SurfaceOwnership.open(group, directory))
            val id = accepted(legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
            accepted(legacy.transact(relocate("current", id, 1)))
            legacy.close()
            val budget = CountingBudget()
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val plan = prepared(CanonicalActivation.prepare(group, directory, budget))
            val filesBefore = fileTree(directory)
            val accountingBefore = budget.snapshot()
            val forged = forge(plan)

            val result = SurfaceOwnership.activateV6(group, directory, budget, forged)

            assertEquals("forgery $index", CanonicalActivationSelectorRefusal.INVALID_PLAN,
                (result as CanonicalActivationResult.Refused).reason)
            assertEquals("forgery $index files", filesBefore, fileTree(directory))
            assertEquals("forgery $index accounting", accountingBefore, budget.snapshot())
            assertTrue("forgery $index remains legacy",
                CanonicalActivationSelector.reopen(group, directory, budget) is CanonicalActivationResult.Legacy)
        } }
    }

    @Test
    fun `legacy restoration and activation publication share one group lock`() = fixture { group, directory ->
        opened(SurfaceOwnership.open(group, directory)).close()
        val budget = CountingBudget()
        CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        val plan = prepared(CanonicalActivation.prepare(group, directory, budget))
        val selectedLegacy = CountDownLatch(1)
        val releaseLegacy = CountDownLatch(1)
        val legacyReturned = CountDownLatch(1)
        val activationReturned = CountDownLatch(1)
        val order = AtomicInteger()
        val legacyOrder = AtomicInteger()
        val activationOrder = AtomicInteger()
        val legacyResult = AtomicReference<SurfaceOwnershipOpenResult>()
        val activationResult = AtomicReference<CanonicalActivationResult>()
        CanonicalActivationTestHooks.afterLegacySelection = {
            selectedLegacy.countDown()
            check(releaseLegacy.await(5, TimeUnit.SECONDS))
        }
        try {
            val legacyThread = Thread {
                legacyResult.set(SurfaceOwnership.open(group, directory))
                legacyOrder.set(order.incrementAndGet())
                legacyReturned.countDown()
            }
            legacyThread.start()
            assertTrue(selectedLegacy.await(5, TimeUnit.SECONDS))
            val activationThread = Thread {
                activationResult.set(SurfaceOwnership.activateV6(group, directory, budget, plan))
                activationOrder.set(order.incrementAndGet())
                activationReturned.countDown()
            }
            activationThread.start()
            assertTrue("activation must wait behind legacy restore", !activationReturned.await(200, TimeUnit.MILLISECONDS))
            releaseLegacy.countDown()
            assertTrue(legacyReturned.await(5, TimeUnit.SECONDS))
            assertTrue(activationReturned.await(5, TimeUnit.SECONDS))
            legacyThread.join(); activationThread.join()
            assertTrue(legacyResult.get() is SurfaceOwnershipOpenResult.Opened)
            assertEquals(CanonicalActivationSelectorRefusal.LEGACY_OWNER_ACTIVE,
                (activationResult.get() as CanonicalActivationResult.Refused).reason)
            assertTrue(legacyOrder.get() < activationOrder.get())
            (legacyResult.get() as SurfaceOwnershipOpenResult.Opened).ownership.close()
            assertTrue(SurfaceOwnership.activateV6(group, directory, budget, plan) is CanonicalActivationResult.Active)
            assertTrue(SurfaceOwnership.open(group, directory) is SurfaceOwnershipOpenResult.Refused)
        } finally {
            CanonicalActivationTestHooks.afterLegacySelection = null
            releaseLegacy.countDown()
        }
    }

    @Test
    fun `durability receipt orders prerequisite parent sync before selector publication sync`() = fixture { group, directory ->
        opened(SurfaceOwnership.open(group, directory)).close()
        val budget = CountingBudget()
        CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        val plan = prepared(CanonicalActivation.prepare(group, directory, budget))
        val receipt = mutableListOf<Pair<CanonicalActivationSyncStage, Boolean>>()
        CanonicalActivationTestHooks.onDirectorySync = { stage, physical ->
            val files = directory.listFiles().orEmpty()
            when (stage) {
                CanonicalActivationSyncStage.ATTEMPT -> {
                    assertTrue(files.any { it.name.endsWith(".attempt") })
                    assertTrue(files.none { it.name.endsWith(".selector") })
                }
                CanonicalActivationSyncStage.PREREQUISITES -> {
                    assertTrue(files.none { it.name.endsWith(".selector") })
                    assertTrue(files.any { it.name.endsWith(".root") })
                    assertTrue(files.any { it.name.endsWith(".slot") })
                }
                CanonicalActivationSyncStage.SELECTOR ->
                    assertTrue(files.any { it.name.endsWith(".selector") })
                CanonicalActivationSyncStage.RECOVERY -> {
                    assertTrue(files.none { it.name.endsWith(".attempt") })
                    assertTrue(files.any { it.name.endsWith(".selector") })
                }
                else -> Unit
            }
            receipt += stage to physical
        }
        try {
            assertTrue(SurfaceOwnership.activateV6(group, directory, budget, plan) is CanonicalActivationResult.Active)
        } finally {
            CanonicalActivationTestHooks.onDirectorySync = null
        }
        val physical = !System.getProperty("os.name").orEmpty().startsWith("Windows", true)
        assertEquals(listOf(
            CanonicalActivationSyncStage.ATTEMPT to physical,
            CanonicalActivationSyncStage.PREREQUISITES to physical,
            CanonicalActivationSyncStage.SELECTOR to physical,
            CanonicalActivationSyncStage.RECOVERY to physical,
        ), receipt)
        assertTrue(CanonicalActivationSelector.reopen(group, directory, budget) is CanonicalActivationResult.Active)
    }

    @Test
    fun `activation publishes one v6 cut and exact current receipt without rewriting legacy`() = fixture { group, directory ->
        val legacy = opened(SurfaceOwnership.open(group, directory))
        val id = accepted(legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
        val receipt = accepted(legacy.transact(relocate("current", id, 1))).receipt.canonicalBytes.toByteArray()
        legacy.close()
        CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
        val plan = prepared(CanonicalActivation.prepare(group, directory, budget()))
        val legacyBefore = directory.listFiles().orEmpty().filter { it.name.startsWith("canonical-surface-surface-") }
            .associate { it.name to it.readBytes() }

        val activatedOwner = opened(SurfaceOwnership.open(group, directory, budget(), plan))
        val active = requireNotNull(activatedOwner.activationState())
        assertEquals(plan.siblingCut, active.cut)
        val current = active.current as CanonicalActivationCurrent.Receipt
        assertArrayEquals(receipt, ByteArrayOutputStream().also(current.source::writeTo).toByteArray())
        assertEquals(legacyBefore.mapValues { it.value.toList() }, directory.listFiles().orEmpty()
            .filter { it.name.startsWith("canonical-surface-surface-") }.associate { it.name to it.readBytes().toList() })

        val reopenedOwner = opened(SurfaceOwnership.open(group, directory, budget()))
        assertEquals(active.cut, requireNotNull(reopenedOwner.activationState()).cut)
        reopenedOwner.close()
        val replay = opened(SurfaceOwnership.open(group, directory, budget(), plan))
        assertEquals(active.identity, requireNotNull(replay.activationState()).identity)
        assertTrue(SurfaceOwnership.open(group, directory) is SurfaceOwnershipOpenResult.Refused)
    }

    @Test
    fun `selector faults reopen old legacy or complete active v6`() {
        CanonicalActivationFault.entries.filterNot { it.isProcessCrashForTest() }.forEach { fault -> fixture { group, directory ->
            opened(SurfaceOwnership.open(group, directory)).close()
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
            val plan = prepared(CanonicalActivation.prepare(group, directory, budget()))
            CanonicalActivationSelector.activate(group, directory, budget(), plan, fault)
            when (val reopened = CanonicalActivationSelector.reopen(group, directory, budget())) {
                CanonicalActivationResult.Legacy -> assertTrue(
                    SurfaceOwnershipLegacyCodec.readValidated(group, directory, SurfaceOwnershipConfiguration()).resident.rows == 0,
                )
                is CanonicalActivationResult.Active -> assertEquals(plan.siblingCut, reopened.state.cut)
                CanonicalActivationResult.UnknownAfterSwitch -> throw AssertionError("fault $fault left unknown reopen")
                is CanonicalActivationResult.Refused -> throw AssertionError("fault $fault refused ${reopened.reason}")
            }
        } }
    }

    @Test
    fun `every caught pre-selector fault reclaims named attempt and liability before legacy reopen`() {
        val faults = listOf(
            CanonicalActivationFault.BEFORE_RESERVATION,
            CanonicalActivationFault.AFTER_RESERVATION,
            CanonicalActivationFault.AFTER_ATTEMPT_SYNC,
            CanonicalActivationFault.BEFORE_CURRENT_WRITE,
            CanonicalActivationFault.DURING_CURRENT_WRITE,
            CanonicalActivationFault.AFTER_CURRENT_SYNC,
            CanonicalActivationFault.BEFORE_ROOT_WRITE,
            CanonicalActivationFault.DURING_ROOT_WRITE,
            CanonicalActivationFault.AFTER_ROOT_SYNC,
            CanonicalActivationFault.BEFORE_SLOT_WRITE,
            CanonicalActivationFault.DURING_SLOT_WRITE,
            CanonicalActivationFault.AFTER_SLOT_SYNC,
            CanonicalActivationFault.BEFORE_PREREQUISITE_PARENT_SYNC,
            CanonicalActivationFault.AFTER_PREREQUISITE_PARENT_SYNC,
            CanonicalActivationFault.BEFORE_SELECTOR_SWITCH,
            CanonicalActivationFault.DURING_SELECTOR_WRITE,
        )
        faults.forEach { fault -> fixture { group, directory ->
            val legacy = opened(SurfaceOwnership.open(group, directory))
            val id = accepted(legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
            accepted(legacy.transact(relocate("current", id, 1))); legacy.close()
            val budget = CountingBudget()
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val plan = prepared(CanonicalActivation.prepare(group, directory, budget))
            val accountingBefore = budget.liabilitySnapshot()

            SurfaceOwnership.activateV6(group, directory, budget, plan, fault)

            assertTrue("$fault legacy", CanonicalActivationSelector.reopen(group, directory, budget) is CanonicalActivationResult.Legacy)
            assertTrue("$fault no named activation artifacts", activationArtifacts(directory).isEmpty())
            assertEquals("$fault no liability", accountingBefore, budget.liabilitySnapshot())
            assertTrue("$fault idempotent reopen", CanonicalActivationSelector.reopen(group, directory, budget) is CanonicalActivationResult.Legacy)
            assertTrue("$fault later activates", SurfaceOwnership.activateV6(group, directory, budget, plan) is CanonicalActivationResult.Active)
        } }
    }

    @Test
    fun `hard crash before selector is reclaimed by durable coordinator reopen`() = fixture { group, directory ->
        val legacy = opened(SurfaceOwnership.open(group, directory))
        val id = accepted(legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
        accepted(legacy.transact(relocate("current", id, 1))); legacy.close()
        var committedBefore = 0L
        StorageBudgetCoordinatorV2(
            directory, StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
            JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
        ) { 128L * 1024 * 1024 }.use { coordinator ->
            val budget = CoordinatorStorageBudget(coordinator)
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val plan = prepared(CanonicalActivation.prepare(group, directory, budget))
            committedBefore = coordinator.committedBytes()
            try {
                SurfaceOwnership.activateV6(
                    group, directory, budget, plan,
                    CanonicalActivationFault.PROCESS_CRASH_AFTER_PREREQUISITE_SYNC,
                )
                throw AssertionError("hard crash was not injected")
            } catch (crash: CanonicalActivationProcessCrash) {
                assertEquals(CanonicalActivationFault.PROCESS_CRASH_AFTER_PREREQUISITE_SYNC, crash.point)
            }
            assertTrue(coordinator.reservedBytes() > 0L)
            assertTrue(activationArtifacts(directory).isNotEmpty())
        }
        StorageBudgetCoordinatorV2(
            directory, StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
            JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
        ) { 128L * 1024 * 1024 }.use { coordinator ->
            val budget = CoordinatorStorageBudget(coordinator)
            assertTrue(CanonicalActivationSelector.reopen(group, directory, budget) is CanonicalActivationResult.Legacy)
            assertEquals(0L, coordinator.reservedBytes())
            assertEquals(committedBefore, coordinator.committedBytes())
            assertTrue(activationArtifacts(directory).isEmpty())
            assertTrue(File(directory, "reservations-v2").listFiles().orEmpty().none {
                it.name.endsWith(".reservation") || it.name.endsWith(".allocation")
            })
            assertTrue(CanonicalActivationSelector.reopen(group, directory, budget) is CanonicalActivationResult.Legacy)
            val plan = prepared(CanonicalActivation.prepare(group, directory, budget))
            assertTrue(SurfaceOwnership.activateV6(group, directory, budget, plan) is CanonicalActivationResult.Active)
        }
    }

    @Test
    fun `corrupt active selector fails closed and never falls back to legacy`() = fixture { group, directory ->
        opened(SurfaceOwnership.open(group, directory)).close()
        CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
        val plan = prepared(CanonicalActivation.prepare(group, directory, budget()))
        assertTrue(SurfaceOwnership.activateV6(group, directory, budget(), plan) is CanonicalActivationResult.Active)
        val selector = directory.listFiles().orEmpty().single { it.name.endsWith(".selector") }
        selector.writeBytes(selector.readBytes().also { it[0] = (it[0].toInt() xor 0x40).toByte() })

        assertEquals(CanonicalActivationSelectorRefusal.CORRUPT_SELECTOR,
            (CanonicalActivationSelector.reopen(group, directory, budget()) as CanonicalActivationResult.Refused).reason)
        assertTrue(SurfaceOwnership.open(group, directory, budget()) is SurfaceOwnershipOpenResult.Refused)
        assertEquals(0, SurfaceOwnershipLegacyCodec.readValidated(group, directory, SurfaceOwnershipConfiguration()).resident.rows)
    }

    private fun budget() = object : CanonicalStorageBudget {
        override fun reserve(bytes: Long): Any = bytes
        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
            CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
    private class CountingBudget : CanonicalStorageBudget {
        private data class Token(val bytes: Long)
        var reserves = 0; var commits = 0; var releases = 0
        var reservedLiability = 0L; var committedLiability = 0L
        override fun reserve(bytes: Long): Any = Token(bytes).also { reserves++; reservedLiability += bytes }
        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
            CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) {
            commits++; reservedLiability -= (token as Token).bytes; committedLiability += actualBytes
        }
        override fun release(token: Any) { releases++; reservedLiability -= (token as Token).bytes }
        override fun allocationUnitBytes(path: File) = 4_096L
        fun snapshot() = listOf(reserves.toLong(), commits.toLong(), releases.toLong(), reservedLiability, committedLiability)
        fun liabilitySnapshot() = reservedLiability to committedLiability
    }
    private fun activationArtifacts(directory: File) = directory.listFiles().orEmpty()
        .filter { it.name.startsWith("canonical-surface-activation-") }
    private fun CanonicalActivationFault?.isProcessCrashForTest() =
        this == CanonicalActivationFault.PROCESS_CRASH_AFTER_PREREQUISITE_SYNC
    private fun fileTree(directory: File) = directory.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(directory).path to it.readBytes().toList() }
    private fun candidate(x: Int) = SurfaceCandidate(null, Voxel(x, 0, 0), 0, 0, 192)
    private fun relocate(command: String, id: SurfaceId, x: Int) = CanonicalTransactionCommand(
        command, CanonicalOperation.RELOCATION, 0, 0, listOf(id),
        listOf(CanonicalTarget(id, Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun opened(result: SurfaceOwnershipOpenResult) = (result as SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: SurfaceOwnershipResult) = result as SurfaceOwnershipResult.Accepted
    private fun accepted(result: CanonicalTransactionResult) = result as CanonicalTransactionResult.Accepted
    private fun prepared(result: CanonicalActivationPreparation) = (result as CanonicalActivationPreparation.Prepared).plan
    private fun fixture(block: (SurfaceGroup, File) -> Unit) {
        val directory = Files.createTempDirectory("canonical-surface-activation").toFile()
        try { block(SurfaceGroup("activation"), directory) } finally { directory.deleteRecursively() }
    }
}
