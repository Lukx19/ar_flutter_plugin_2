package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalAcknowledgementTest {
    @Test
    fun `feature batch becomes one exact adjacent current and survives replay before ACK`() {
        val fixture = activated("feature-batch-current")
        val boundaryCommandId = "\u0000".repeat(128)
        try {
            val before = requireNotNull(fixture.owner.activationState())
            val current = before.current as CanonicalActivationCurrent.Receipt
            assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, before.cut.geometryRevision, before.cut.lineageRevision),
            ) is CanonicalAcknowledgementResult.Acknowledged)
            val plan = withAdjacentView(fixture, before) { view ->
                (fixture.owner.prepareAdjacentMutation(view, CanonicalFeatureBatchCommand(
                    boundaryCommandId, before.cut.geometryRevision, before.cut.lineageRevision,
                    listOf(
                        FeatureFusionChange.Upsert(FeatureFusionCandidate(1, 0, 0, 2, 1,
                            listOf(FeatureNormalCandidate(1, 0, 0, FeatureNormalFace.PRIMARY, 0, 0, 191)))),
                        FeatureFusionChange.Upsert(FeatureFusionCandidate(2, 0, 0, 2, 1,
                            listOf(FeatureNormalCandidate(2, 0, 0, FeatureNormalFace.PRIMARY, 0, 0, 191)))),
                        FeatureFusionChange.Removal(3, 0, 0),
                    ),
                )) as CanonicalMutationPreparation.Prepared).mutation
            }
            val committed = fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Committed
            val published = committed.state.current as CanonicalActivationCurrent.Receipt
            assertEquals(before.cut.geometryRevision + 1, committed.state.cut.geometryRevision)
            assertEquals(before.cut.lineageRevision, committed.state.cut.lineageRevision)
            assertEquals(2, committed.state.cut.liveSurfaceCount)
            fixture.owner.close()
            val replayedOwner = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            val replayed = requireNotNull(replayedOwner.activationState())
            assertEquals(committed.state.cut, replayed.cut)
            assertEquals(published.identity, (replayed.current as CanonicalActivationCurrent.Receipt).identity)
            assertTrue(replayedOwner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(published.identity.commandHash, replayed.cut.geometryRevision, replayed.cut.lineageRevision),
            ) is CanonicalAcknowledgementResult.Acknowledged)
            replayedOwner.close()
        } finally {
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `ACK publication linearizes query and adjacent prepare with its durable selector`() {
        val fixture = activated("ack-linearized-readers")
        try {
            val before = requireNotNull(fixture.owner.activationState())
            val current = before.current as CanonicalActivationCurrent.Receipt
            withAdjacentView(fixture, before) { view ->
                val durable = java.util.concurrent.CountDownLatch(1)
                val publish = java.util.concurrent.CountDownLatch(1)
                val ackResult = java.util.concurrent.atomic.AtomicReference<CanonicalAcknowledgementResult>()
                val queryResult = java.util.concurrent.atomic.AtomicReference<CanonicalActivationState?>()
                val prepareResult = java.util.concurrent.atomic.AtomicReference<CanonicalMutationPreparation>()
                CanonicalActivationTestHooks.onDirectorySync = { stage, _ ->
                    if (stage == CanonicalActivationSyncStage.ACK_SELECTOR) {
                        durable.countDown()
                        assertTrue(publish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
                val ack = Thread {
                    ackResult.set(fixture.owner.acknowledgeCanonicalCurrent(
                        CanonicalAcknowledgement(
                            current.identity.commandHash,
                            before.cut.geometryRevision,
                            before.cut.lineageRevision,
                        ),
                    ))
                }.also(Thread::start)
                assertTrue(durable.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val query = Thread { queryResult.set(fixture.owner.activationState()) }.also(Thread::start)
                val prepare = Thread {
                    prepareResult.set(fixture.owner.prepareAdjacentMutation(
                        view, adjacentCommand(fixture, before, 2),
                    ))
                }.also(Thread::start)
                Thread.sleep(200)
                assertTrue("query crossed ACK publication", query.isAlive)
                assertTrue("prepare crossed ACK publication", prepare.isAlive)
                publish.countDown()
                listOf(ack, query, prepare).forEach { it.join(10_000) }
                assertTrue(listOf(ack, query, prepare).none(Thread::isAlive))
                assertTrue(ackResult.get() is CanonicalAcknowledgementResult.Acknowledged)
                assertTrue(requireNotNull(queryResult.get()).currentState is CanonicalCurrentState.Acknowledged)
                val plan = (prepareResult.get() as CanonicalMutationPreparation.Prepared).mutation
                assertTrue(fixture.owner.commitAdjacentCanonicalMutation(plan) is CanonicalAdjacentCommitResult.Committed)
                assertEquals(PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
                val prefix = "canonical-surface-activation-${fixture.group.hash.joinToString("") { "%02x".format(it) }}-current-"
                assertEquals(1, fixture.directory.listFiles().orEmpty().count {
                    it.name.startsWith(prefix) && it.name.endsWith(".receipt")
                })
            }
        } finally {
            CanonicalActivationTestHooks.onDirectorySync = null
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `close linearizes after durable ACK publication and hides the final snapshot`() {
        val fixture = activated("ack-linearized-close")
        try {
            val before = requireNotNull(fixture.owner.activationState())
            val current = before.current as CanonicalActivationCurrent.Receipt
            val durable = java.util.concurrent.CountDownLatch(1)
            val publish = java.util.concurrent.CountDownLatch(1)
            val ackResult = java.util.concurrent.atomic.AtomicReference<CanonicalAcknowledgementResult>()
            CanonicalActivationTestHooks.onDirectorySync = { stage, _ ->
                if (stage == CanonicalActivationSyncStage.ACK_SELECTOR) {
                    durable.countDown()
                    assertTrue(publish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val ack = Thread {
                ackResult.set(fixture.owner.acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(
                        current.identity.commandHash,
                        before.cut.geometryRevision,
                        before.cut.lineageRevision,
                    ),
                ))
            }.also(Thread::start)
            assertTrue(durable.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val close = Thread { fixture.owner.close() }.also(Thread::start)
            Thread.sleep(200)
            assertTrue("close crossed ACK publication", close.isAlive)
            publish.countDown()
            ack.join(10_000); close.join(10_000)
            assertFalse("ACK deadlocked", ack.isAlive)
            assertFalse("close deadlocked", close.isAlive)
            assertTrue(ackResult.get() is CanonicalAcknowledgementResult.Acknowledged)
            assertEquals(null, fixture.owner.activationState())
            assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(
                    current.identity.commandHash,
                    before.cut.geometryRevision,
                    before.cut.lineageRevision,
                ),
            ) is CanonicalAcknowledgementResult.NoOp)
        } finally {
            CanonicalActivationTestHooks.onDirectorySync = null
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }
    @Test
    fun `discard and owner close defer across every durable adjacent stage`() {
        listOf("discard", "owner-close").forEach { action ->
            listOf(
                CanonicalAdjacentOwnershipStage.COMMIT,
                CanonicalAdjacentOwnershipStage.RECONCILE,
                CanonicalAdjacentOwnershipStage.REOPEN,
            ).forEach { stage ->
                val fixture = activated("in-flight-$action-${stage.name.lowercase()}")
                try {
                    val state = requireNotNull(fixture.owner.activationState())
                    val current = state.current as CanonicalActivationCurrent.Receipt
                    assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                        CanonicalAcknowledgement(
                            current.identity.commandHash,
                            state.cut.geometryRevision,
                            state.cut.lineageRevision,
                        ),
                    ) is CanonicalAcknowledgementResult.Acknowledged)
                    val plan = adjacentPlan(fixture, state)
                    val entered = java.util.concurrent.CountDownLatch(1)
                    val proceed = java.util.concurrent.CountDownLatch(1)
                    val result = java.util.concurrent.atomic.AtomicReference<CanonicalAdjacentCommitResult>()
                    CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                        if (observation.stage == stage) {
                            entered.countDown()
                            assertTrue(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    }
                    val commit = Thread {
                        result.set(fixture.owner.commitAdjacentCanonicalMutation(plan))
                    }.also(Thread::start)
                    assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    if (action == "discard") {
                        assertEquals(PreparedMutationDiscardResult.Deferred, plan.discard())
                        assertEquals(PreparedMutationDiscardResult.AlreadyPending, plan.discard())
                    } else {
                        val close = Thread { fixture.owner.close() }.also(Thread::start)
                        close.join(2_000)
                        assertFalse("owner close deadlocked at $stage", close.isAlive)
                    }
                    assertEquals(PreparedMutationLifecycle.IN_FLIGHT, plan.lifecycle())
                    assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                    assertEquals(1, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
                    proceed.countDown()
                    commit.join(10_000)
                    assertFalse("commit deadlocked at $stage", commit.isAlive)
                    assertTrue("$action at $stage => ${result.get()}",
                        result.get() is CanonicalAdjacentCommitResult.Committed)
                    assertEquals(PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
                    assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                    assertEquals(0, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
                } finally {
                    CanonicalActivationTestHooks.onAdjacentOwnership = null
                    fixture.owner.close()
                    fixture.directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `second concurrent commit is refused without disturbing the claimed operation`() {
        val fixture = activated("double-commit")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(
                    current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision,
                ),
            )
            val plan = adjacentPlan(fixture, state)
            val entered = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val first = java.util.concurrent.atomic.AtomicReference<CanonicalAdjacentCommitResult>()
            CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                if (observation.stage == CanonicalAdjacentOwnershipStage.COMMIT) {
                    entered.countDown()
                    assertTrue(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val worker = Thread {
                first.set(fixture.owner.commitAdjacentCanonicalMutation(plan))
            }.also(Thread::start)
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))

            val duplicate = fixture.owner.commitAdjacentCanonicalMutation(plan)
                as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.PLAN_IN_FLIGHT, duplicate.reason)
            assertEquals(PreparedMutationDisposition.RETRYABLE, duplicate.disposition)
            assertEquals(PreparedMutationLifecycle.IN_FLIGHT, plan.lifecycle())
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())

            proceed.countDown(); worker.join(10_000)
            assertFalse("first commit deadlocked", worker.isAlive)
            assertTrue(first.get() is CanonicalAdjacentCommitResult.Committed)
            assertEquals(PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally {
            CanonicalActivationTestHooks.onAdjacentOwnership = null
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `retryable admission with pending owner close becomes terminal and future owner can bind`() {
        val fixture = activated("retry-pending-close")
        var duplicateStore: CompactCanonicalStore? = null
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(
                    current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision,
                ),
            )
            val plan = adjacentPlan(fixture, state)
            duplicateStore = (CompactCanonicalStore.openV6(
                fixture.group, fixture.directory, fixture.budget,
            ) as CompactCanonicalOpenResult.Opened).store
            val entered = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference<CanonicalAdjacentCommitResult>()
            CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                if (observation.stage == CanonicalAdjacentOwnershipStage.ADMISSION) {
                    entered.countDown()
                    assertTrue(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val commit = Thread {
                result.set(fixture.owner.commitAdjacentCanonicalMutation(plan))
            }.also(Thread::start)
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val close = Thread { fixture.owner.close() }.also(Thread::start)
            close.join(2_000)
            assertFalse("owner close deadlocked", close.isAlive)
            assertEquals(PreparedMutationLifecycle.IN_FLIGHT, plan.lifecycle())
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            proceed.countDown(); commit.join(10_000)
            assertFalse("retryable commit deadlocked", commit.isAlive)

            val refused = result.get() as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.DUPLICATE_RESIDENT_AUTHORITY, refused.reason)
            assertEquals(PreparedMutationDisposition.TERMINAL, refused.disposition)
            assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            duplicateStore.close(); duplicateStore = null

            val reopened = opened(SurfaceOwnership.open(
                fixture.group, fixture.directory, fixture.budget,
            ))
            val future = adjacentPreparation(fixture, state, 3, reopened)
                as CanonicalMutationPreparation.Prepared
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            future.mutation.discard()
            reopened.close()
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally {
            CanonicalActivationTestHooks.onAdjacentOwnership = null
            duplicateStore?.close()
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }
    @Test
    fun `opaque authority lease rejects forgery staleness cross scope and concurrent cross owner use`() {
        val fixture = activated("lease-scope")
        try {
            val base = (CompactCanonicalStore.openV6(fixture.group, fixture.directory, fixture.budget)
                as CompactCanonicalOpenResult.Opened).store
            val owner = Any()
            val otherOwner = Any()
            val lease = CanonicalAuthorityLeaseRegistry.acquire(base, owner)
            base.close()
            val forged = CanonicalAuthorityLease()
            val forgedClone = CanonicalAuthorityLease()
            assertTrue(forged !== forgedClone)
            assertTrue(CanonicalAuthorityLease::class.java.declaredFields.none { it.name == "token" })
            listOf(forged, forgedClone).forEach { fake ->
                assertEquals(
                    CanonicalAuthorityLeaseRefusal.STALE,
                    (CanonicalAuthorityLeaseRegistry.resolve(
                        fake, fixture.group, fixture.directory, owner,
                    ) as CanonicalAuthorityLeaseResolution.Refused).reason,
                )
            }
            assertEquals(
                CanonicalAuthorityLeaseRefusal.GROUP_MISMATCH,
                (CanonicalAuthorityLeaseRegistry.resolve(
                    lease, SurfaceGroup("wrong-group"), fixture.directory, owner,
                ) as CanonicalAuthorityLeaseResolution.Refused).reason,
            )
            assertEquals(
                CanonicalAuthorityLeaseRefusal.DIRECTORY_MISMATCH,
                (CanonicalAuthorityLeaseRegistry.resolve(
                    lease, fixture.group, File(fixture.directory, "wrong-directory"), owner,
                ) as CanonicalAuthorityLeaseResolution.Refused).reason,
            )

            val results = java.util.Collections.synchronizedList(mutableListOf<CanonicalAuthorityLeaseResolution>())
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = List(16) { index -> Thread {
                start.await()
                results += CanonicalAuthorityLeaseRegistry.resolve(
                    lease, fixture.group, fixture.directory, if (index % 2 == 0) owner else otherOwner,
                )
            }.also(Thread::start) }
            start.countDown(); threads.forEach(Thread::join)
            assertEquals(8, results.count { it is CanonicalAuthorityLeaseResolution.Resolved })
            assertEquals(8, results.count {
                it is CanonicalAuthorityLeaseResolution.Refused &&
                    it.reason == CanonicalAuthorityLeaseRefusal.OWNER_MISMATCH
            })
            assertTrue(CanonicalAuthorityLeaseRegistry.release(lease))
            assertTrue(!CanonicalAuthorityLeaseRegistry.release(lease))
            assertEquals(
                CanonicalAuthorityLeaseRefusal.STALE,
                (CanonicalAuthorityLeaseRegistry.resolve(
                    lease, fixture.group, fixture.directory, owner,
                ) as CanonicalAuthorityLeaseResolution.Refused).reason,
            )
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `owner close discards a fresh READY adjacent plan and releases its exact store`() {
        val fixture = activated("ready-close")
        try {
            val plan = adjacentPlan(fixture, requireNotNull(fixture.owner.activationState()))
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())

            assertEquals(SurfaceOwnershipCloseResult.Closed, fixture.owner.close())

            assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `one outstanding adjacent plan bounds repeated preparation and permits future admission`() {
        val fixture = activated("one-outstanding")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val first = adjacentPreparation(fixture, state, 2) as CanonicalMutationPreparation.Prepared
            val busy = adjacentPreparation(fixture, state, 3) as CanonicalMutationPreparation.Refused
            assertEquals(CanonicalMutationRefusal.ADJACENT_BUSY, busy.reason)
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())

            assertEquals(PreparedMutationDiscardResult.Discarded, first.mutation.discard())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val future = adjacentPreparation(fixture, state, 4) as CanonicalMutationPreparation.Prepared
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            future.mutation.discard()
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally {
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `concurrent adjacent preparation admits one bound plan and refuses the rest busy`() {
        val fixture = activated("concurrent-bind")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val results = java.util.Collections.synchronizedList(
                mutableListOf<CanonicalMutationPreparation>(),
            )
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = List(12) { index -> Thread {
                start.await()
                results += adjacentPreparation(fixture, state, index + 2)
            }.also(Thread::start) }
            start.countDown(); threads.forEach(Thread::join)

            assertEquals(1, results.count { it is CanonicalMutationPreparation.Prepared })
            assertEquals(11, results.count {
                it is CanonicalMutationPreparation.Refused &&
                    it.reason == CanonicalMutationRefusal.ADJACENT_BUSY
            })
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
            (results.single { it is CanonicalMutationPreparation.Prepared }
                as CanonicalMutationPreparation.Prepared).mutation.discard()
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
        } finally {
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `abandoned prepared authority discards exactly once and fails closed on reuse`() {
        val fixture = activated("discarded-plan")
        try {
            val plan = adjacentPlan(fixture, requireNotNull(fixture.owner.activationState()))
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(PreparedMutationDiscardResult.Discarded, plan.discard())
            assertEquals(PreparedMutationDiscardResult.AlreadyDiscarded, plan.discard())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val refused = fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.PLAN_DISCARDED, refused.reason)
            assertEquals(PreparedMutationDisposition.TERMINAL, refused.disposition)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `owner close releases an explicitly retryable pre ACK plan`() {
        val fixture = activated("retryable-close")
        try {
            val plan = adjacentPlan(fixture, requireNotNull(fixture.owner.activationState()))
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val refused = fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.CURRENT_UNACKNOWLEDGED, refused.reason)
            assertEquals(PreparedMutationDisposition.RETRYABLE, refused.disposition)
            assertEquals(PreparedMutationLifecycle.READY, plan.lifecycle())
            fixture.owner.close()
            assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `terminal commit refusal discards authority and a fresh plan can admit`() {
        val fixture = activated("terminal-commit")
        try {
            val initial = requireNotNull(fixture.owner.activationState())
            val current = initial.current as CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
            )
            val failedPlan = adjacentPlan(fixture, initial)
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val refused = fixture.owner.commitAdjacentCanonicalMutation(
                failedPlan, CanonicalCommitFaults(cow = CanonicalCowFault.BEFORE_RESERVATION),
            ) as CanonicalAdjacentCommitResult.Refused
            assertEquals(PreparedMutationDisposition.TERMINAL, refused.disposition)
            assertEquals(PreparedMutationLifecycle.DISCARDED, failedPlan.lifecycle())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())

            val fresh = adjacentPlan(fixture, initial)
            assertTrue(fixture.owner.commitAdjacentCanonicalMutation(fresh) is CanonicalAdjacentCommitResult.Committed)
            assertEquals(PreparedMutationLifecycle.CONSUMED, fresh.lifecycle())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `commit reconcile and reopen exceptions discard their exact authority`() {
        listOf(
            CanonicalAdjacentOwnershipStage.COMMIT,
            CanonicalAdjacentOwnershipStage.RECONCILE,
            CanonicalAdjacentOwnershipStage.REOPEN,
        ).forEach { failedStage ->
            val fixture = activated("terminal-stage-${failedStage.name.lowercase()}")
            try {
                val initial = requireNotNull(fixture.owner.activationState())
                val current = initial.current as CanonicalActivationCurrent.Receipt
                fixture.owner.acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
                )
                val plan = adjacentPlan(fixture, initial)
                assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                    if (observation.stage == failedStage) error("terminal-$failedStage")
                }
                val refused = try {
                    fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Refused
                } finally { CanonicalActivationTestHooks.onAdjacentOwnership = null }
                assertEquals(PreparedMutationDisposition.TERMINAL, refused.disposition)
                assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
                assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            } finally {
                CanonicalActivationTestHooks.onAdjacentOwnership = null
                fixture.directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `oversized truncated and checksum corrupt adjacent manifests fail closed`() {
        listOf("oversized", "truncated", "checksum").forEach { corruption ->
            val fixture = activated("adjacent-manifest-$corruption")
            try {
                val state = requireNotNull(fixture.owner.activationState())
                val plan = adjacentPlan(fixture, state)
                val current = state.current as CanonicalActivationCurrent.Receipt
                fixture.owner.acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision),
                )
                try {
                    fixture.owner.commitAdjacentCanonicalMutation(
                        plan, CanonicalCommitFaults(selector = CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH),
                    )
                    throw AssertionError("expected private selector process cut")
                } catch (_: CanonicalSimulatedProcessCrash) { }
                assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
                assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                fixture.owner.close()
                val transaction = File(fixture.directory, "canonical-surface-activation-${fixture.group.hash.joinToString("") { "%02x".format(it) }}-adjacent.transaction")
                val original = transaction.readBytes()
                when (corruption) {
                    "oversized" -> transaction.writeBytes(ByteArray(2_049))
                    "truncated" -> transaction.writeBytes(original.copyOf(10))
                    else -> transaction.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                }
                assertTrue(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget) is SurfaceOwnershipOpenResult.Refused)
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    @Test
    fun `oversized truncated and checksum corrupt ACK manifests fail closed`() {
        listOf("oversized", "truncated", "checksum").forEach { corruption ->
            val fixture = activated("manifest-$corruption")
            try {
                val state = requireNotNull(fixture.owner.activationState())
                val current = state.current as CanonicalActivationCurrent.Receipt
                try {
                    fixture.owner.acknowledgeCanonicalCurrent(
                        CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision),
                        CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_ROOT_SYNC,
                    )
                    throw AssertionError("expected process cut")
                } catch (_: CanonicalAcknowledgementProcessCrash) { }
                fixture.owner.close()
                val attempt = fixture.directory.listFiles().orEmpty().single { it.name.contains("-ack-") && it.name.endsWith(".attempt") }
                val original = attempt.readBytes()
                when (corruption) {
                    "oversized" -> attempt.writeBytes(ByteArray(2_049))
                    "truncated" -> attempt.writeBytes(original.copyOf(10))
                    else -> attempt.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                }
                assertTrue(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget) is SurfaceOwnershipOpenResult.Refused)
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    @Test
    fun `repeated ACK adjacent cycles retain bounded roots and exact physical ledger`() {
        val fixture = activated("bounded-cycles")
        try {
            val physicalBaseline = fixture.budget.allocatedBytes(fixture.directory)
            val ledgerBaseline = fixture.budget.committedBytes
            repeat(6) { cycle ->
                val state = requireNotNull(fixture.owner.activationState())
                val current = state.current as CanonicalActivationCurrent.Receipt
                assertTrue(current.identity.canonicalLength <= CanonicalActivationResources.MAX_CURRENT_BYTES)
                assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision),
                ) is CanonicalAcknowledgementResult.Acknowledged)
                val adjacent = fixture.owner.commitAdjacentCanonicalMutation(adjacentPlan(fixture, state, cycle + 2))
                assertTrue("cycle=$cycle adjacent=$adjacent", adjacent is CanonicalAdjacentCommitResult.Committed)
                val files = fixture.directory.walkTopDown().filter(File::isFile).toList()
                assertTrue(files.count { it.name.matches(Regex("canonical-surface-activation-.*-root-[0-9a-f]{64}\\.root")) } <= 2)
                assertTrue(files.count { it.name.matches(Regex("canonical-surface-activation-.*-current-[0-9a-f]{64}\\.receipt")) } <= 1)
                assertTrue(files.none { it.name.contains("-reclaim-") })
                assertEquals(0L, fixture.budget.reservedBytes)
                val physicalDelta = fixture.budget.allocatedBytes(fixture.directory) - physicalBaseline
                assertEquals(physicalDelta, fixture.budget.committedBytes - ledgerBaseline)
                assertTrue(fixture.budget.allocatedBytes(fixture.directory) <= CompactCanonicalStore.C17_TOTAL_BYTES)
            }
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `adjacent activation selector process cuts settle exact durable reservations`() {
        CanonicalAdjacentFault.entries.forEachIndexed { index, fault ->
            val fixture = activated("adjacent-pointer-$index")
            try {
                val initial = requireNotNull(fixture.owner.activationState())
                val current = initial.current as CanonicalActivationCurrent.Receipt
                fixture.owner.acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
                )
                val committedBefore = fixture.budget.committedBytes
                val physicalBefore = fixture.budget.allocatedBytes(fixture.directory)
                val plan = adjacentPlan(fixture, initial)
                assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                try {
                    fixture.owner.commitAdjacentCanonicalMutation(
                        plan, CanonicalCommitFaults(adjacent = fault),
                    )
                    throw AssertionError("expected adjacent process crash")
                } catch (_: CanonicalAdjacentProcessCrash) { }
                assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
                assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                fixture.owner.close()
                val reopened = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
                assertEquals(initial.cut.geometryRevision + 1, requireNotNull(reopened.activationState()).cut.geometryRevision)
                assertEquals(0L, fixture.budget.reservedBytes)
                val physical = fixture.budget.allocatedBytes(fixture.directory)
                assertEquals(physical - physicalBefore, fixture.budget.committedBytes - committedBefore)
                val again = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
                assertEquals(requireNotNull(reopened.activationState()).cut, requireNotNull(again.activationState()).cut)
                assertEquals(0L, fixture.budget.reservedBytes)
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    @Test
    fun `current none is already ready for one exact adjacent cut`() {
        val directory = Files.createTempDirectory("canonical-surface-ack-none").toFile()
        try {
            val group = SurfaceGroup("ack-none")
            val budget = CountingBudget()
            val legacy = opened(SurfaceOwnership.open(group, directory))
            val id = (legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0)))) as SurfaceOwnershipResult.Accepted).owners.single().id
            legacy.close()
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val activation = (CanonicalActivation.prepare(group, directory, budget) as CanonicalActivationPreparation.Prepared).plan
            val owner = opened(SurfaceOwnership.open(group, directory, budget, activation))
            val state = requireNotNull(owner.activationState())
            assertEquals(CanonicalCurrentState.None, state.currentState)
            val fixture = Fixture(directory, group, budget, id, owner)
            assertTrue(owner.commitAdjacentCanonicalMutation(adjacentPlan(fixture, state)) is CanonicalAdjacentCommitResult.Committed)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `crossed private selector is forward repaired before reopen exposure`() {
        val fixture = activated("forward-repair")
        try {
            val initial = requireNotNull(fixture.owner.activationState())
            val current = initial.current as CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
            )
            val plan = adjacentPlan(fixture, initial)
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            try {
                fixture.owner.commitAdjacentCanonicalMutation(
                    plan,
                    CanonicalCommitFaults(selector = CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH),
                )
                throw AssertionError("expected simulated process crash")
            } catch (_: CanonicalSimulatedProcessCrash) {
                // Reopen owns the durable forward repair.
            }
            assertEquals(PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            fixture.owner.close()
            val reopened = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            val repaired = requireNotNull(reopened.activationState())
            assertEquals(initial.cut.geometryRevision + 1, repaired.cut.geometryRevision)
            assertTrue(repaired.current is CanonicalActivationCurrent.Receipt)
            assertTrue(fixture.directory.listFiles().orEmpty().none { it.name.endsWith(".transaction") })
            reopened.close()
            val again = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            again.close()
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `owner blocks before ACK then admits one exact adjacent cut and reopens its current`() {
        val fixture = activated("adjacent")
        try {
            val initial = requireNotNull(fixture.owner.activationState())
            val plan = adjacentPlan(fixture, initial)

            val blocked = fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.CURRENT_UNACKNOWLEDGED, blocked.reason)
            assertEquals(PreparedMutationDisposition.RETRYABLE, blocked.disposition)
            val current = initial.current as CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
            )
            val committed = fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Committed
            assertEquals(PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
            assertEquals(initial.cut.geometryRevision + 1, committed.state.cut.geometryRevision)
            assertTrue(committed.state.current is CanonicalActivationCurrent.Receipt)
            assertEquals(CanonicalCurrentState.Unacknowledged((committed.state.current as CanonicalActivationCurrent.Receipt).identity), committed.state.currentState)
            val duplicate = fixture.owner.commitAdjacentCanonicalMutation(plan) as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.PLAN_DISCARDED, duplicate.reason)

            fixture.owner.close()
            val reopened = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            assertEquals(committed.state.cut, requireNotNull(reopened.activationState()).cut)
            assertTrue(requireNotNull(reopened.activationState()).current is CanonicalActivationCurrent.Receipt)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `exact acknowledgement is durable idempotent and revision neutral`() {
        val fixture = activated("exact")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as CanonicalActivationCurrent.Receipt
            val ack = CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision)

            val first = fixture.owner.acknowledgeCanonicalCurrent(ack)
            assertTrue("first=$first reserved=${fixture.budget.reservedBytes} committed=${fixture.budget.committedBytes} files=${fixture.directory.listFiles().orEmpty().map { it.name }}", first is CanonicalAcknowledgementResult.Acknowledged)
            assertEquals(CanonicalCurrentState.Acknowledged(current.identity), requireNotNull(fixture.owner.activationState()).currentState)
            assertEquals(CanonicalActivationCurrent.None, requireNotNull(fixture.owner.activationState()).current)

            val duplicate = fixture.owner.acknowledgeCanonicalCurrent(ack)
            assertTrue(duplicate is CanonicalAcknowledgementResult.Idempotent)
            fixture.owner.close()
            val reopened = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            assertEquals(CanonicalCurrentState.Acknowledged(current.identity), requireNotNull(reopened.activationState()).currentState)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `mismatched or stale acknowledgement is a typed no op and preserves current`() {
        val fixture = activated("no-op")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as CanonicalActivationCurrent.Receipt
            val stale = fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision + 1, state.cut.lineageRevision),
            ) as CanonicalAcknowledgementResult.NoOp
            assertEquals(CanonicalAcknowledgementNoOp.STALE_REVISION, stale.reason)
            val mismatch = fixture.owner.acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(CanonicalReceiptBytes(ByteArray(32) { 7 }), state.cut.geometryRevision, state.cut.lineageRevision),
            ) as CanonicalAcknowledgementResult.NoOp
            assertEquals(CanonicalAcknowledgementNoOp.COMMAND_MISMATCH, mismatch.reason)
            assertTrue(requireNotNull(fixture.owner.activationState()).current is CanonicalActivationCurrent.Receipt)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `acknowledgement recovery exposes old unacknowledged or complete acknowledged state`() {
        CanonicalAcknowledgementFault.entries.forEachIndexed { index, fault ->
            val fixture = activated("fault-$index")
            try {
                val state = requireNotNull(fixture.owner.activationState())
                val current = state.current as CanonicalActivationCurrent.Receipt
                val committedBefore = fixture.budget.committedBytes
                val physicalBefore = fixture.budget.allocatedBytes(fixture.directory)
                try {
                    fixture.owner.acknowledgeCanonicalCurrent(
                        CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision), fault,
                    )
                } catch (_: CanonicalAcknowledgementProcessCrash) {
                    // A process crash has no in-process cleanup; reopen owns the cut.
                }
                fixture.owner.close()
                val reopened = opened(SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
                val recovered = requireNotNull(reopened.activationState())
                assertEquals(state.cut, recovered.cut)
                assertTrue(recovered.currentState is CanonicalCurrentState.Unacknowledged || recovered.currentState is CanonicalCurrentState.Acknowledged)
                assertEquals(0L, fixture.budget.reservedBytes)
                assertEquals(
                    fixture.budget.allocatedBytes(fixture.directory) - physicalBefore,
                    fixture.budget.committedBytes - committedBefore,
                )
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    private fun activated(suffix: String): Fixture {
        val directory = Files.createTempDirectory("canonical-surface-ack-$suffix").toFile()
        val group = SurfaceGroup("ack-$suffix")
        val budget = CountingBudget()
        val legacy = opened(SurfaceOwnership.open(group, directory))
        val id = (legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0)))) as SurfaceOwnershipResult.Accepted).owners.single().id
        legacy.transact(CanonicalTransactionCommand("current", CanonicalOperation.RELOCATION, 0, 0, listOf(id), listOf(CanonicalTarget(id, Voxel(1, 0, 0), 0, 0, 192))))
        legacy.close()
        CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        val plan = (CanonicalActivation.prepare(group, directory, budget) as CanonicalActivationPreparation.Prepared).plan
        return Fixture(directory, group, budget, id, opened(SurfaceOwnership.open(group, directory, budget, plan)))
    }

    private fun candidate(x: Int) = SurfaceCandidate(null, Voxel(x, 0, 0), 0, 0, 192)
    private fun adjacentPlan(fixture: Fixture, state: CanonicalActivationState, targetX: Int = 2) =
        (adjacentPreparation(fixture, state, targetX) as CanonicalMutationPreparation.Prepared).mutation

    private fun adjacentPreparation(
        fixture: Fixture,
        state: CanonicalActivationState,
        targetX: Int,
        owner: SurfaceOwnership = fixture.owner,
    ) =
        (CompactCanonicalStore.openV6(fixture.group, fixture.directory, fixture.budget)
            as CompactCanonicalOpenResult.Opened).store.use { base ->
            val current = (state.current as? CanonicalActivationCurrent.Receipt)?.identity
            val store = requireNotNull(CanonicalCommitStore.open(fixture.directory, fixture.budget))
            store.use {
                val selected = store.reopen(base, current?.let { PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) })
                fun prepare(view: CanonicalStateView) = owner.prepareAdjacentMutation(
                    view, adjacentCommand(fixture, state, targetX),
                )
                when (selected) {
                    is CanonicalReopenResult.GenerationZero -> prepare(selected.view)
                    is CanonicalReopenResult.Selected -> selected.commit.use { prepare(it.view) }
                    is CanonicalReopenResult.Refused -> error("reopen refused: $selected")
                }
            }
        }

    private fun adjacentCommand(fixture: Fixture, state: CanonicalActivationState, targetX: Int) =
        CanonicalTransactionCommand(
            "adjacent-${state.cut.geometryRevision}", CanonicalOperation.RELOCATION,
            state.cut.geometryRevision, state.cut.lineageRevision, listOf(fixture.id),
            listOf(CanonicalTarget(fixture.id, Voxel(targetX, 0, 0), 0, 0, 192)),
        )

    private inline fun <T> withAdjacentView(
        fixture: Fixture,
        state: CanonicalActivationState,
        block: (CanonicalStateView) -> T,
    ): T = (CompactCanonicalStore.openV6(fixture.group, fixture.directory, fixture.budget)
        as CompactCanonicalOpenResult.Opened).store.use { base ->
        val current = (state.current as? CanonicalActivationCurrent.Receipt)?.identity
        requireNotNull(CanonicalCommitStore.open(fixture.directory, fixture.budget)).use { store ->
            when (val selected = store.reopen(
                base, current?.let { PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) },
            )) {
                is CanonicalReopenResult.GenerationZero -> block(selected.view)
                is CanonicalReopenResult.Selected -> selected.commit.use { block(it.view) }
                is CanonicalReopenResult.Refused -> error("reopen refused: $selected")
            }
        }
    }
    private fun opened(result: SurfaceOwnershipOpenResult) = (result as SurfaceOwnershipOpenResult.Opened).ownership
    private data class Fixture(val directory: File, val group: SurfaceGroup, val budget: CountingBudget, val id: SurfaceId, val owner: SurfaceOwnership)
    private class CountingBudget : ExclusiveFakeStorageBudget() {
        private data class Token(val id: Int, val bytes: Long)
        private var next = 0
        private val outstanding = linkedMapOf<Token, Long>()
        private val attempts = linkedMapOf<String, MutableList<CanonicalPointerReservation>>()
        private val completedReclaims = hashSetOf<String>()
        var committedBytes = 0L; private set
        val reservedBytes get() = outstanding.values.sum()
        override fun reserveBytes(bytes: Long): Any = Token(++next, bytes).also { outstanding[it] = bytes }
        override fun commitBytes(token: Any, actualBytes: Long) {
            forget(token); require(outstanding.remove(token as Token) != null); committedBytes += actualBytes
        }
        override fun releaseBytes(token: Any) { forget(token); require(outstanding.remove(token as Token) != null) }
        override fun reserveActivationAttempt(groupId: String, attemptId: String, rootBeforeBytes: Long, slotBeforeBytes: Long, selectorBeforeBytes: Long, commitBytes: Long, maximumPhysicalBytes: Long): Any? {
            val token = reserveBytes(maximumPhysicalBytes)
            attempts.getOrPut(groupId) { mutableListOf() } += CanonicalPointerReservation(token, attemptId, 0, rootBeforeBytes, slotBeforeBytes, selectorBeforeBytes, commitBytes)
            return token
        }
        override fun activationAttempts(groupId: String) = attempts[groupId].orEmpty().toList()
        override fun commitActivationAttempt(reservation: CanonicalPointerReservation) {
            commit(reservation.token, reservation.commitBytes)
        }
        override fun releaseActivationAttempt(reservation: CanonicalPointerReservation) {
            release(reservation.token)
        }
        override fun reclaimCommittedBytesOnce(reclaimId: String, bytes: Long) {
            if (completedReclaims.add(reclaimId)) { require(bytes in 1..committedBytes); committedBytes -= bytes }
        }
        override fun forgetCommittedReclaim(reclaimId: String) = Unit
        override fun reclaimCommittedCandidate(candidate: File): Long {
            val bytes = allocatedBytes(candidate)
            require(bytes in 1..committedBytes && candidate.deleteRecursively())
            committedBytes -= bytes
            return bytes
        }
        override fun allocationUnitBytes(path: File) = 4_096L
        private fun forget(token: Any) { attempts.values.forEach { it.removeIf { value -> value.token == token } } }
    }
}
