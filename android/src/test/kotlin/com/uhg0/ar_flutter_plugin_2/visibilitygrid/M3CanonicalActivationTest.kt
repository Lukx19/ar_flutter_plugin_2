package com.uhg0.ar_flutter_plugin_2.visibilitygrid

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

class M3CanonicalActivationTest {
    @Test
    fun `forged plan facts fail before files reservation burn or accounting`() {
        val forgeries: List<(M3CanonicalActivationPlan) -> M3CanonicalActivationPlan> = listOf(
            { plan -> M3CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut, M3CanonicalActivationCurrent.None, plan.receipt) },
            { plan ->
                val current = plan.current as M3CanonicalActivationCurrent.Receipt
                M3CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut,
                    current.copy(source = M3CanonicalCurrentSource { output -> current.source.writeTo(output) }), plan.receipt)
            },
            { plan -> M3CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut.copy(), plan.current, plan.receipt) },
        )
        forgeries.forEachIndexed { index, forge -> fixture { group, directory ->
            val legacy = opened(M3SurfaceOwnership.open(group, directory))
            val id = accepted(legacy.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
            accepted(legacy.transact(relocate("current", id, 1)))
            legacy.close()
            val budget = CountingBudget()
            M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget))
            val filesBefore = fileTree(directory)
            val accountingBefore = budget.snapshot()
            val forged = forge(plan)

            val result = M3SurfaceOwnership.activateV6(group, directory, budget, forged)

            assertEquals("forgery $index", M3CanonicalActivationSelectorRefusal.INVALID_PLAN,
                (result as M3CanonicalActivationResult.Refused).reason)
            assertEquals("forgery $index files", filesBefore, fileTree(directory))
            assertEquals("forgery $index accounting", accountingBefore, budget.snapshot())
            assertTrue("forgery $index remains legacy",
                M3CanonicalActivationSelector.reopen(group, directory, budget) is M3CanonicalActivationResult.Legacy)
        } }
    }

    @Test
    fun `legacy restoration and activation publication share one group lock`() = fixture { group, directory ->
        opened(M3SurfaceOwnership.open(group, directory)).close()
        val budget = CountingBudget()
        M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget))
        val selectedLegacy = CountDownLatch(1)
        val releaseLegacy = CountDownLatch(1)
        val legacyReturned = CountDownLatch(1)
        val activationReturned = CountDownLatch(1)
        val order = AtomicInteger()
        val legacyOrder = AtomicInteger()
        val activationOrder = AtomicInteger()
        val legacyResult = AtomicReference<M3SurfaceOwnershipOpenResult>()
        val activationResult = AtomicReference<M3CanonicalActivationResult>()
        M3CanonicalActivationTestHooks.afterLegacySelection = {
            selectedLegacy.countDown()
            check(releaseLegacy.await(5, TimeUnit.SECONDS))
        }
        try {
            val legacyThread = Thread {
                legacyResult.set(M3SurfaceOwnership.open(group, directory))
                legacyOrder.set(order.incrementAndGet())
                legacyReturned.countDown()
            }
            legacyThread.start()
            assertTrue(selectedLegacy.await(5, TimeUnit.SECONDS))
            val activationThread = Thread {
                activationResult.set(M3SurfaceOwnership.activateV6(group, directory, budget, plan))
                activationOrder.set(order.incrementAndGet())
                activationReturned.countDown()
            }
            activationThread.start()
            assertTrue("activation must wait behind legacy restore", !activationReturned.await(200, TimeUnit.MILLISECONDS))
            releaseLegacy.countDown()
            assertTrue(legacyReturned.await(5, TimeUnit.SECONDS))
            assertTrue(activationReturned.await(5, TimeUnit.SECONDS))
            legacyThread.join(); activationThread.join()
            assertTrue(legacyResult.get() is M3SurfaceOwnershipOpenResult.Opened)
            assertEquals(M3CanonicalActivationSelectorRefusal.LEGACY_OWNER_ACTIVE,
                (activationResult.get() as M3CanonicalActivationResult.Refused).reason)
            assertTrue(legacyOrder.get() < activationOrder.get())
            (legacyResult.get() as M3SurfaceOwnershipOpenResult.Opened).ownership.close()
            assertTrue(M3SurfaceOwnership.activateV6(group, directory, budget, plan) is M3CanonicalActivationResult.Active)
            assertTrue(M3SurfaceOwnership.open(group, directory) is M3SurfaceOwnershipOpenResult.Refused)
        } finally {
            M3CanonicalActivationTestHooks.afterLegacySelection = null
            releaseLegacy.countDown()
        }
    }

    @Test
    fun `durability receipt orders prerequisite parent sync before selector publication sync`() = fixture { group, directory ->
        opened(M3SurfaceOwnership.open(group, directory)).close()
        val budget = CountingBudget()
        M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget))
        val receipt = mutableListOf<Pair<M3CanonicalActivationSyncStage, Boolean>>()
        M3CanonicalActivationTestHooks.onDirectorySync = { stage, physical ->
            val files = directory.listFiles().orEmpty()
            when (stage) {
                M3CanonicalActivationSyncStage.PREREQUISITES -> {
                    assertTrue(files.none { it.name.endsWith(".selector") })
                    assertTrue(files.any { it.name.endsWith(".root") })
                    assertTrue(files.any { it.name.endsWith(".slot") })
                }
                M3CanonicalActivationSyncStage.SELECTOR ->
                    assertTrue(files.any { it.name.endsWith(".selector") })
            }
            receipt += stage to physical
        }
        try {
            assertTrue(M3SurfaceOwnership.activateV6(group, directory, budget, plan) is M3CanonicalActivationResult.Active)
        } finally {
            M3CanonicalActivationTestHooks.onDirectorySync = null
        }
        val physical = !System.getProperty("os.name").orEmpty().startsWith("Windows", true)
        assertEquals(listOf(
            M3CanonicalActivationSyncStage.PREREQUISITES to physical,
            M3CanonicalActivationSyncStage.SELECTOR to physical,
        ), receipt)
        assertTrue(M3CanonicalActivationSelector.reopen(group, directory, budget) is M3CanonicalActivationResult.Active)
    }

    @Test
    fun `activation publishes one v6 cut and exact current receipt without rewriting legacy`() = fixture { group, directory ->
        val legacy = opened(M3SurfaceOwnership.open(group, directory))
        val id = accepted(legacy.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
        val receipt = accepted(legacy.transact(relocate("current", id, 1))).receipt.canonicalBytes.toByteArray()
        legacy.close()
        M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
        val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
        val legacyBefore = directory.listFiles().orEmpty().filter { it.name.startsWith("m3-surface-") }
            .associate { it.name to it.readBytes() }

        val activatedOwner = opened(M3SurfaceOwnership.open(group, directory, budget(), plan))
        val active = requireNotNull(activatedOwner.activationState())
        assertEquals(plan.siblingCut, active.cut)
        val current = active.current as M3CanonicalActivationCurrent.Receipt
        assertArrayEquals(receipt, ByteArrayOutputStream().also(current.source::writeTo).toByteArray())
        assertEquals(legacyBefore.mapValues { it.value.toList() }, directory.listFiles().orEmpty()
            .filter { it.name.startsWith("m3-surface-") }.associate { it.name to it.readBytes().toList() })

        val reopenedOwner = opened(M3SurfaceOwnership.open(group, directory, budget()))
        assertEquals(active.cut, requireNotNull(reopenedOwner.activationState()).cut)
        reopenedOwner.close()
        val replay = opened(M3SurfaceOwnership.open(group, directory, budget(), plan))
        assertEquals(active.identity, requireNotNull(replay.activationState()).identity)
        assertTrue(M3SurfaceOwnership.open(group, directory) is M3SurfaceOwnershipOpenResult.Refused)
    }

    @Test
    fun `selector faults reopen old legacy or complete active v6`() {
        M3CanonicalActivationFault.entries.forEach { fault -> fixture { group, directory ->
            opened(M3SurfaceOwnership.open(group, directory)).close()
            M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
            val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            M3CanonicalActivationSelector.activate(group, directory, budget(), plan, fault)
            when (val reopened = M3CanonicalActivationSelector.reopen(group, directory, budget())) {
                M3CanonicalActivationResult.Legacy -> assertTrue(
                    M3SurfaceOwnershipLegacyCodec.readValidated(group, directory, M3SurfaceOwnershipConfiguration()).resident.rows == 0,
                )
                is M3CanonicalActivationResult.Active -> assertEquals(plan.siblingCut, reopened.state.cut)
                M3CanonicalActivationResult.UnknownAfterSwitch -> throw AssertionError("fault $fault left unknown reopen")
                is M3CanonicalActivationResult.Refused -> throw AssertionError("fault $fault refused ${reopened.reason}")
            }
        } }
    }

    @Test
    fun `corrupt active selector fails closed and never falls back to legacy`() = fixture { group, directory ->
        opened(M3SurfaceOwnership.open(group, directory)).close()
        M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
        val plan = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
        assertTrue(M3SurfaceOwnership.activateV6(group, directory, budget(), plan) is M3CanonicalActivationResult.Active)
        val selector = directory.listFiles().orEmpty().single { it.name.endsWith(".selector") }
        selector.writeBytes(selector.readBytes().also { it[0] = (it[0].toInt() xor 0x40).toByte() })

        assertEquals(M3CanonicalActivationSelectorRefusal.CORRUPT_SELECTOR,
            (M3CanonicalActivationSelector.reopen(group, directory, budget()) as M3CanonicalActivationResult.Refused).reason)
        assertTrue(M3SurfaceOwnership.open(group, directory, budget()) is M3SurfaceOwnershipOpenResult.Refused)
        assertEquals(0, M3SurfaceOwnershipLegacyCodec.readValidated(group, directory, M3SurfaceOwnershipConfiguration()).resident.rows)
    }

    private fun budget() = object : M3CanonicalStorageBudget {
        override fun reserve(bytes: Long): Any = bytes
        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
            M3CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
    private class CountingBudget : M3CanonicalStorageBudget {
        var reserves = 0; var commits = 0; var releases = 0
        override fun reserve(bytes: Long): Any = bytes.also { reserves++ }
        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
            M3CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) { commits++ }
        override fun release(token: Any) { releases++ }
        override fun allocationUnitBytes(path: File) = 4_096L
        fun snapshot() = Triple(reserves, commits, releases)
    }
    private fun fileTree(directory: File) = directory.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(directory).path to it.readBytes().toList() }
    private fun candidate(x: Int) = M3SurfaceCandidate(null, M3Voxel(x, 0, 0), 0, 0, 192)
    private fun relocate(command: String, id: M3SurfaceId, x: Int) = M3CanonicalTransactionCommand(
        command, M3CanonicalOperation.RELOCATION, 0, 0, listOf(id),
        listOf(M3CanonicalTarget(id, M3Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
    private fun accepted(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Accepted
    private fun prepared(result: M3CanonicalActivationPreparation) = (result as M3CanonicalActivationPreparation.Prepared).plan
    private fun fixture(block: (M3SurfaceGroup, File) -> Unit) {
        val directory = Files.createTempDirectory("m3-activation").toFile()
        try { block(M3SurfaceGroup("activation"), directory) } finally { directory.deleteRecursively() }
    }
}
