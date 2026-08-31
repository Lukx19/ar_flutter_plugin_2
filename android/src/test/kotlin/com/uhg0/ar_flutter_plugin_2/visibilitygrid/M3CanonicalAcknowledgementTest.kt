package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalAcknowledgementTest {
    @Test
    fun `feature batch becomes one exact adjacent current and survives replay before ACK`() {
        val fixture = activated("feature-batch-current")
        try {
            val before = requireNotNull(fixture.owner.activationState())
            val current = before.current as M3CanonicalActivationCurrent.Receipt
            assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(current.identity.commandHash, before.cut.geometryRevision, before.cut.lineageRevision),
            ) is M3CanonicalAcknowledgementResult.Acknowledged)
            val plan = withAdjacentView(fixture, before) { view ->
                (fixture.owner.prepareAdjacentMutation(view, M3CanonicalFeatureBatchCommand(
                    "feature-batch-${before.cut.geometryRevision}", before.cut.geometryRevision, before.cut.lineageRevision,
                    listOf(
                        M3FeatureFusionChange.Upsert(M3FeatureFusionCandidate(1, 0, 0, 2, 1,
                            listOf(M3FeatureNormalCandidate(1, 0, 0, M3FeatureNormalFace.PRIMARY, 0, 0, 191)))),
                        M3FeatureFusionChange.Upsert(M3FeatureFusionCandidate(2, 0, 0, 2, 1,
                            listOf(M3FeatureNormalCandidate(2, 0, 0, M3FeatureNormalFace.PRIMARY, 0, 0, 191)))),
                        M3FeatureFusionChange.Removal(3, 0, 0),
                    ),
                )) as M3CanonicalMutationPreparation.Prepared).mutation
            }
            val committed = fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Committed
            val published = committed.state.current as M3CanonicalActivationCurrent.Receipt
            assertEquals(before.cut.geometryRevision + 1, committed.state.cut.geometryRevision)
            assertEquals(before.cut.lineageRevision, committed.state.cut.lineageRevision)
            assertEquals(2, committed.state.cut.liveSurfaceCount)
            fixture.owner.close()
            val replayedOwner = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            val replayed = requireNotNull(replayedOwner.activationState())
            assertEquals(committed.state.cut, replayed.cut)
            assertEquals(published.identity, (replayed.current as M3CanonicalActivationCurrent.Receipt).identity)
            assertTrue(replayedOwner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(published.identity.commandHash, replayed.cut.geometryRevision, replayed.cut.lineageRevision),
            ) is M3CanonicalAcknowledgementResult.Acknowledged)
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
            val current = before.current as M3CanonicalActivationCurrent.Receipt
            withAdjacentView(fixture, before) { view ->
                val durable = java.util.concurrent.CountDownLatch(1)
                val publish = java.util.concurrent.CountDownLatch(1)
                val ackResult = java.util.concurrent.atomic.AtomicReference<M3CanonicalAcknowledgementResult>()
                val queryResult = java.util.concurrent.atomic.AtomicReference<M3CanonicalActivationState?>()
                val prepareResult = java.util.concurrent.atomic.AtomicReference<M3CanonicalMutationPreparation>()
                M3CanonicalActivationTestHooks.onDirectorySync = { stage, _ ->
                    if (stage == M3CanonicalActivationSyncStage.ACK_SELECTOR) {
                        durable.countDown()
                        assertTrue(publish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
                val ack = Thread {
                    ackResult.set(fixture.owner.acknowledgeCanonicalCurrent(
                        M3CanonicalAcknowledgement(
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
                assertTrue(ackResult.get() is M3CanonicalAcknowledgementResult.Acknowledged)
                assertTrue(requireNotNull(queryResult.get()).currentState is M3CanonicalCurrentState.Acknowledged)
                val plan = (prepareResult.get() as M3CanonicalMutationPreparation.Prepared).mutation
                assertTrue(fixture.owner.commitAdjacentCanonicalMutation(plan) is M3CanonicalAdjacentCommitResult.Committed)
                assertEquals(M3PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
                val prefix = "m3-activation-${fixture.group.hash.joinToString("") { "%02x".format(it) }}-current-"
                assertEquals(1, fixture.directory.listFiles().orEmpty().count {
                    it.name.startsWith(prefix) && it.name.endsWith(".receipt")
                })
            }
        } finally {
            M3CanonicalActivationTestHooks.onDirectorySync = null
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `close linearizes after durable ACK publication and hides the final snapshot`() {
        val fixture = activated("ack-linearized-close")
        try {
            val before = requireNotNull(fixture.owner.activationState())
            val current = before.current as M3CanonicalActivationCurrent.Receipt
            val durable = java.util.concurrent.CountDownLatch(1)
            val publish = java.util.concurrent.CountDownLatch(1)
            val ackResult = java.util.concurrent.atomic.AtomicReference<M3CanonicalAcknowledgementResult>()
            M3CanonicalActivationTestHooks.onDirectorySync = { stage, _ ->
                if (stage == M3CanonicalActivationSyncStage.ACK_SELECTOR) {
                    durable.countDown()
                    assertTrue(publish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val ack = Thread {
                ackResult.set(fixture.owner.acknowledgeCanonicalCurrent(
                    M3CanonicalAcknowledgement(
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
            assertTrue(ackResult.get() is M3CanonicalAcknowledgementResult.Acknowledged)
            assertEquals(null, fixture.owner.activationState())
            assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(
                    current.identity.commandHash,
                    before.cut.geometryRevision,
                    before.cut.lineageRevision,
                ),
            ) is M3CanonicalAcknowledgementResult.NoOp)
        } finally {
            M3CanonicalActivationTestHooks.onDirectorySync = null
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }
    @Test
    fun `discard and owner close defer across every durable adjacent stage`() {
        listOf("discard", "owner-close").forEach { action ->
            listOf(
                M3CanonicalAdjacentOwnershipStage.COMMIT,
                M3CanonicalAdjacentOwnershipStage.RECONCILE,
                M3CanonicalAdjacentOwnershipStage.REOPEN,
            ).forEach { stage ->
                val fixture = activated("in-flight-$action-${stage.name.lowercase()}")
                try {
                    val state = requireNotNull(fixture.owner.activationState())
                    val current = state.current as M3CanonicalActivationCurrent.Receipt
                    assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                        M3CanonicalAcknowledgement(
                            current.identity.commandHash,
                            state.cut.geometryRevision,
                            state.cut.lineageRevision,
                        ),
                    ) is M3CanonicalAcknowledgementResult.Acknowledged)
                    val plan = adjacentPlan(fixture, state)
                    val entered = java.util.concurrent.CountDownLatch(1)
                    val proceed = java.util.concurrent.CountDownLatch(1)
                    val result = java.util.concurrent.atomic.AtomicReference<M3CanonicalAdjacentCommitResult>()
                    M3CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
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
                        assertEquals(M3PreparedMutationDiscardResult.Deferred, plan.discard())
                        assertEquals(M3PreparedMutationDiscardResult.AlreadyPending, plan.discard())
                    } else {
                        val close = Thread { fixture.owner.close() }.also(Thread::start)
                        close.join(2_000)
                        assertFalse("owner close deadlocked at $stage", close.isAlive)
                    }
                    assertEquals(M3PreparedMutationLifecycle.IN_FLIGHT, plan.lifecycle())
                    assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                    assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
                    proceed.countDown()
                    commit.join(10_000)
                    assertFalse("commit deadlocked at $stage", commit.isAlive)
                    assertTrue("$action at $stage => ${result.get()}",
                        result.get() is M3CanonicalAdjacentCommitResult.Committed)
                    assertEquals(M3PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
                    assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                    assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
                } finally {
                    M3CanonicalActivationTestHooks.onAdjacentOwnership = null
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
            val current = state.current as M3CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(
                    current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision,
                ),
            )
            val plan = adjacentPlan(fixture, state)
            val entered = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val first = java.util.concurrent.atomic.AtomicReference<M3CanonicalAdjacentCommitResult>()
            M3CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                if (observation.stage == M3CanonicalAdjacentOwnershipStage.COMMIT) {
                    entered.countDown()
                    assertTrue(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val worker = Thread {
                first.set(fixture.owner.commitAdjacentCanonicalMutation(plan))
            }.also(Thread::start)
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))

            val duplicate = fixture.owner.commitAdjacentCanonicalMutation(plan)
                as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3CanonicalAdjacentCommitRefusal.PLAN_IN_FLIGHT, duplicate.reason)
            assertEquals(M3PreparedMutationDisposition.RETRYABLE, duplicate.disposition)
            assertEquals(M3PreparedMutationLifecycle.IN_FLIGHT, plan.lifecycle())
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())

            proceed.countDown(); worker.join(10_000)
            assertFalse("first commit deadlocked", worker.isAlive)
            assertTrue(first.get() is M3CanonicalAdjacentCommitResult.Committed)
            assertEquals(M3PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally {
            M3CanonicalActivationTestHooks.onAdjacentOwnership = null
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun `retryable admission with pending owner close becomes terminal and future owner can bind`() {
        val fixture = activated("retry-pending-close")
        var duplicateStore: M3CompactCanonicalStore? = null
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as M3CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(
                    current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision,
                ),
            )
            val plan = adjacentPlan(fixture, state)
            duplicateStore = (M3CompactCanonicalStore.openV6(
                fixture.group, fixture.directory, fixture.budget,
            ) as M3CompactCanonicalOpenResult.Opened).store
            val entered = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference<M3CanonicalAdjacentCommitResult>()
            M3CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                if (observation.stage == M3CanonicalAdjacentOwnershipStage.ADMISSION) {
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
            assertEquals(M3PreparedMutationLifecycle.IN_FLIGHT, plan.lifecycle())
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            proceed.countDown(); commit.join(10_000)
            assertFalse("retryable commit deadlocked", commit.isAlive)

            val refused = result.get() as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3CanonicalAdjacentCommitRefusal.DUPLICATE_RESIDENT_AUTHORITY, refused.reason)
            assertEquals(M3PreparedMutationDisposition.TERMINAL, refused.disposition)
            assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            duplicateStore.close(); duplicateStore = null

            val reopened = opened(M3SurfaceOwnership.open(
                fixture.group, fixture.directory, fixture.budget,
            ))
            val future = adjacentPreparation(fixture, state, 3, reopened)
                as M3CanonicalMutationPreparation.Prepared
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            future.mutation.discard()
            reopened.close()
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally {
            M3CanonicalActivationTestHooks.onAdjacentOwnership = null
            duplicateStore?.close()
            fixture.owner.close()
            fixture.directory.deleteRecursively()
        }
    }
    @Test
    fun `opaque authority lease rejects forgery staleness cross scope and concurrent cross owner use`() {
        val fixture = activated("lease-scope")
        try {
            val base = (M3CompactCanonicalStore.openV6(fixture.group, fixture.directory, fixture.budget)
                as M3CompactCanonicalOpenResult.Opened).store
            val owner = Any()
            val otherOwner = Any()
            val lease = M3CanonicalAuthorityLeaseRegistry.acquire(base, owner)
            base.close()
            val forged = M3CanonicalAuthorityLease()
            val forgedClone = M3CanonicalAuthorityLease()
            assertTrue(forged !== forgedClone)
            assertTrue(M3CanonicalAuthorityLease::class.java.declaredFields.none { it.name == "token" })
            listOf(forged, forgedClone).forEach { fake ->
                assertEquals(
                    M3CanonicalAuthorityLeaseRefusal.STALE,
                    (M3CanonicalAuthorityLeaseRegistry.resolve(
                        fake, fixture.group, fixture.directory, owner,
                    ) as M3CanonicalAuthorityLeaseResolution.Refused).reason,
                )
            }
            assertEquals(
                M3CanonicalAuthorityLeaseRefusal.GROUP_MISMATCH,
                (M3CanonicalAuthorityLeaseRegistry.resolve(
                    lease, M3SurfaceGroup("wrong-group"), fixture.directory, owner,
                ) as M3CanonicalAuthorityLeaseResolution.Refused).reason,
            )
            assertEquals(
                M3CanonicalAuthorityLeaseRefusal.DIRECTORY_MISMATCH,
                (M3CanonicalAuthorityLeaseRegistry.resolve(
                    lease, fixture.group, File(fixture.directory, "wrong-directory"), owner,
                ) as M3CanonicalAuthorityLeaseResolution.Refused).reason,
            )

            val results = java.util.Collections.synchronizedList(mutableListOf<M3CanonicalAuthorityLeaseResolution>())
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = List(16) { index -> Thread {
                start.await()
                results += M3CanonicalAuthorityLeaseRegistry.resolve(
                    lease, fixture.group, fixture.directory, if (index % 2 == 0) owner else otherOwner,
                )
            }.also(Thread::start) }
            start.countDown(); threads.forEach(Thread::join)
            assertEquals(8, results.count { it is M3CanonicalAuthorityLeaseResolution.Resolved })
            assertEquals(8, results.count {
                it is M3CanonicalAuthorityLeaseResolution.Refused &&
                    it.reason == M3CanonicalAuthorityLeaseRefusal.OWNER_MISMATCH
            })
            assertTrue(M3CanonicalAuthorityLeaseRegistry.release(lease))
            assertTrue(!M3CanonicalAuthorityLeaseRegistry.release(lease))
            assertEquals(
                M3CanonicalAuthorityLeaseRefusal.STALE,
                (M3CanonicalAuthorityLeaseRegistry.resolve(
                    lease, fixture.group, fixture.directory, owner,
                ) as M3CanonicalAuthorityLeaseResolution.Refused).reason,
            )
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `owner close discards a fresh READY adjacent plan and releases its exact store`() {
        val fixture = activated("ready-close")
        try {
            val plan = adjacentPlan(fixture, requireNotNull(fixture.owner.activationState()))
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())

            assertEquals(M3SurfaceOwnershipCloseResult.Closed, fixture.owner.close())

            assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `one outstanding adjacent plan bounds repeated preparation and permits future admission`() {
        val fixture = activated("one-outstanding")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val first = adjacentPreparation(fixture, state, 2) as M3CanonicalMutationPreparation.Prepared
            val busy = adjacentPreparation(fixture, state, 3) as M3CanonicalMutationPreparation.Refused
            assertEquals(M3CanonicalMutationRefusal.ADJACENT_BUSY, busy.reason)
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())

            assertEquals(M3PreparedMutationDiscardResult.Discarded, first.mutation.discard())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val future = adjacentPreparation(fixture, state, 4) as M3CanonicalMutationPreparation.Prepared
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            future.mutation.discard()
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
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
                mutableListOf<M3CanonicalMutationPreparation>(),
            )
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = List(12) { index -> Thread {
                start.await()
                results += adjacentPreparation(fixture, state, index + 2)
            }.also(Thread::start) }
            start.countDown(); threads.forEach(Thread::join)

            assertEquals(1, results.count { it is M3CanonicalMutationPreparation.Prepared })
            assertEquals(11, results.count {
                it is M3CanonicalMutationPreparation.Refused &&
                    it.reason == M3CanonicalMutationRefusal.ADJACENT_BUSY
            })
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
            (results.single { it is M3CanonicalMutationPreparation.Prepared }
                as M3CanonicalMutationPreparation.Prepared).mutation.discard()
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
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
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(M3PreparedMutationDiscardResult.Discarded, plan.discard())
            assertEquals(M3PreparedMutationDiscardResult.AlreadyDiscarded, plan.discard())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val refused = fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3CanonicalAdjacentCommitRefusal.PLAN_DISCARDED, refused.reason)
            assertEquals(M3PreparedMutationDisposition.TERMINAL, refused.disposition)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `owner close releases an explicitly retryable pre ACK plan`() {
        val fixture = activated("retryable-close")
        try {
            val plan = adjacentPlan(fixture, requireNotNull(fixture.owner.activationState()))
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val refused = fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3CanonicalAdjacentCommitRefusal.CURRENT_UNACKNOWLEDGED, refused.reason)
            assertEquals(M3PreparedMutationDisposition.RETRYABLE, refused.disposition)
            assertEquals(M3PreparedMutationLifecycle.READY, plan.lifecycle())
            fixture.owner.close()
            assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `terminal commit refusal discards authority and a fresh plan can admit`() {
        val fixture = activated("terminal-commit")
        try {
            val initial = requireNotNull(fixture.owner.activationState())
            val current = initial.current as M3CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
            )
            val failedPlan = adjacentPlan(fixture, initial)
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            val refused = fixture.owner.commitAdjacentCanonicalMutation(
                failedPlan, M3CanonicalCommitFaults(cow = M3CanonicalCowFault.BEFORE_RESERVATION),
            ) as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3PreparedMutationDisposition.TERMINAL, refused.disposition)
            assertEquals(M3PreparedMutationLifecycle.DISCARDED, failedPlan.lifecycle())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())

            val fresh = adjacentPlan(fixture, initial)
            assertTrue(fixture.owner.commitAdjacentCanonicalMutation(fresh) is M3CanonicalAdjacentCommitResult.Committed)
            assertEquals(M3PreparedMutationLifecycle.CONSUMED, fresh.lifecycle())
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `commit reconcile and reopen exceptions discard their exact authority`() {
        listOf(
            M3CanonicalAdjacentOwnershipStage.COMMIT,
            M3CanonicalAdjacentOwnershipStage.RECONCILE,
            M3CanonicalAdjacentOwnershipStage.REOPEN,
        ).forEach { failedStage ->
            val fixture = activated("terminal-stage-${failedStage.name.lowercase()}")
            try {
                val initial = requireNotNull(fixture.owner.activationState())
                val current = initial.current as M3CanonicalActivationCurrent.Receipt
                fixture.owner.acknowledgeCanonicalCurrent(
                    M3CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
                )
                val plan = adjacentPlan(fixture, initial)
                assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                M3CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
                    if (observation.stage == failedStage) error("terminal-$failedStage")
                }
                val refused = try {
                    fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Refused
                } finally { M3CanonicalActivationTestHooks.onAdjacentOwnership = null }
                assertEquals(M3PreparedMutationDisposition.TERMINAL, refused.disposition)
                assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
                assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            } finally {
                M3CanonicalActivationTestHooks.onAdjacentOwnership = null
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
                val current = state.current as M3CanonicalActivationCurrent.Receipt
                fixture.owner.acknowledgeCanonicalCurrent(
                    M3CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision),
                )
                try {
                    fixture.owner.commitAdjacentCanonicalMutation(
                        plan, M3CanonicalCommitFaults(selector = M3CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH),
                    )
                    throw AssertionError("expected private selector process cut")
                } catch (_: M3CanonicalSimulatedProcessCrash) { }
                assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
                assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                fixture.owner.close()
                val transaction = File(fixture.directory, "m3-activation-${fixture.group.hash.joinToString("") { "%02x".format(it) }}-adjacent.transaction")
                val original = transaction.readBytes()
                when (corruption) {
                    "oversized" -> transaction.writeBytes(ByteArray(2_049))
                    "truncated" -> transaction.writeBytes(original.copyOf(10))
                    else -> transaction.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                }
                assertTrue(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget) is M3SurfaceOwnershipOpenResult.Refused)
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    @Test
    fun `oversized truncated and checksum corrupt ACK manifests fail closed`() {
        listOf("oversized", "truncated", "checksum").forEach { corruption ->
            val fixture = activated("manifest-$corruption")
            try {
                val state = requireNotNull(fixture.owner.activationState())
                val current = state.current as M3CanonicalActivationCurrent.Receipt
                try {
                    fixture.owner.acknowledgeCanonicalCurrent(
                        M3CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision),
                        M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_ROOT_SYNC,
                    )
                    throw AssertionError("expected process cut")
                } catch (_: M3CanonicalAcknowledgementProcessCrash) { }
                fixture.owner.close()
                val attempt = fixture.directory.listFiles().orEmpty().single { it.name.contains("-ack-") && it.name.endsWith(".attempt") }
                val original = attempt.readBytes()
                when (corruption) {
                    "oversized" -> attempt.writeBytes(ByteArray(2_049))
                    "truncated" -> attempt.writeBytes(original.copyOf(10))
                    else -> attempt.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                }
                assertTrue(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget) is M3SurfaceOwnershipOpenResult.Refused)
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
                val current = state.current as M3CanonicalActivationCurrent.Receipt
                assertTrue(current.identity.canonicalLength <= M3CanonicalActivationResources.MAX_CURRENT_BYTES)
                assertTrue(fixture.owner.acknowledgeCanonicalCurrent(
                    M3CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision),
                ) is M3CanonicalAcknowledgementResult.Acknowledged)
                val adjacent = fixture.owner.commitAdjacentCanonicalMutation(adjacentPlan(fixture, state, cycle + 2))
                assertTrue("cycle=$cycle adjacent=$adjacent", adjacent is M3CanonicalAdjacentCommitResult.Committed)
                val files = fixture.directory.walkTopDown().filter(File::isFile).toList()
                assertTrue(files.count { it.name.matches(Regex("m3-activation-.*-root-[0-9a-f]{64}\\.root")) } <= 2)
                assertTrue(files.count { it.name.matches(Regex("m3-activation-.*-current-[0-9a-f]{64}\\.receipt")) } <= 1)
                assertTrue(files.none { it.name.contains("-reclaim-") })
                assertEquals(0L, fixture.budget.reservedBytes)
                val physicalDelta = fixture.budget.allocatedBytes(fixture.directory) - physicalBaseline
                assertEquals(physicalDelta, fixture.budget.committedBytes - ledgerBaseline)
                assertTrue(fixture.budget.allocatedBytes(fixture.directory) <= M3CompactCanonicalStore.C17_TOTAL_BYTES)
            }
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `adjacent activation selector process cuts settle exact durable reservations`() {
        M3CanonicalAdjacentFault.entries.forEachIndexed { index, fault ->
            val fixture = activated("adjacent-pointer-$index")
            try {
                val initial = requireNotNull(fixture.owner.activationState())
                val current = initial.current as M3CanonicalActivationCurrent.Receipt
                fixture.owner.acknowledgeCanonicalCurrent(
                    M3CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
                )
                val committedBefore = fixture.budget.committedBytes
                val physicalBefore = fixture.budget.allocatedBytes(fixture.directory)
                val plan = adjacentPlan(fixture, initial)
                assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                try {
                    fixture.owner.commitAdjacentCanonicalMutation(
                        plan, M3CanonicalCommitFaults(adjacent = fault),
                    )
                    throw AssertionError("expected adjacent process crash")
                } catch (_: M3CanonicalAdjacentProcessCrash) { }
                assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
                assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
                fixture.owner.close()
                val reopened = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
                assertEquals(initial.cut.geometryRevision + 1, requireNotNull(reopened.activationState()).cut.geometryRevision)
                assertEquals(0L, fixture.budget.reservedBytes)
                val physical = fixture.budget.allocatedBytes(fixture.directory)
                assertEquals(physical - physicalBefore, fixture.budget.committedBytes - committedBefore)
                val again = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
                assertEquals(requireNotNull(reopened.activationState()).cut, requireNotNull(again.activationState()).cut)
                assertEquals(0L, fixture.budget.reservedBytes)
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    @Test
    fun `current none is already ready for one exact adjacent cut`() {
        val directory = Files.createTempDirectory("m3-ack-none").toFile()
        try {
            val group = M3SurfaceGroup("ack-none")
            val budget = CountingBudget()
            val legacy = opened(M3SurfaceOwnership.open(group, directory))
            val id = (legacy.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))) as M3SurfaceOwnershipResult.Accepted).owners.single().id
            legacy.close()
            M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val activation = (M3CanonicalActivation.prepare(group, directory, budget) as M3CanonicalActivationPreparation.Prepared).plan
            val owner = opened(M3SurfaceOwnership.open(group, directory, budget, activation))
            val state = requireNotNull(owner.activationState())
            assertEquals(M3CanonicalCurrentState.None, state.currentState)
            val fixture = Fixture(directory, group, budget, id, owner)
            assertTrue(owner.commitAdjacentCanonicalMutation(adjacentPlan(fixture, state)) is M3CanonicalAdjacentCommitResult.Committed)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `crossed private selector is forward repaired before reopen exposure`() {
        val fixture = activated("forward-repair")
        try {
            val initial = requireNotNull(fixture.owner.activationState())
            val current = initial.current as M3CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
            )
            val plan = adjacentPlan(fixture, initial)
            assertEquals(1, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            try {
                fixture.owner.commitAdjacentCanonicalMutation(
                    plan,
                    M3CanonicalCommitFaults(selector = M3CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH),
                )
                throw AssertionError("expected simulated process crash")
            } catch (_: M3CanonicalSimulatedProcessCrash) {
                // Reopen owns the durable forward repair.
            }
            assertEquals(M3PreparedMutationLifecycle.DISCARDED, plan.lifecycle())
            assertEquals(0, M3CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            fixture.owner.close()
            val reopened = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            val repaired = requireNotNull(reopened.activationState())
            assertEquals(initial.cut.geometryRevision + 1, repaired.cut.geometryRevision)
            assertTrue(repaired.current is M3CanonicalActivationCurrent.Receipt)
            assertTrue(fixture.directory.listFiles().orEmpty().none { it.name.endsWith(".transaction") })
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `owner blocks before ACK then admits one exact adjacent cut and reopens its current`() {
        val fixture = activated("adjacent")
        try {
            val initial = requireNotNull(fixture.owner.activationState())
            val plan = adjacentPlan(fixture, initial)

            val blocked = fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3CanonicalAdjacentCommitRefusal.CURRENT_UNACKNOWLEDGED, blocked.reason)
            assertEquals(M3PreparedMutationDisposition.RETRYABLE, blocked.disposition)
            val current = initial.current as M3CanonicalActivationCurrent.Receipt
            fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(current.identity.commandHash, initial.cut.geometryRevision, initial.cut.lineageRevision),
            )
            val committed = fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Committed
            assertEquals(M3PreparedMutationLifecycle.CONSUMED, plan.lifecycle())
            assertEquals(initial.cut.geometryRevision + 1, committed.state.cut.geometryRevision)
            assertTrue(committed.state.current is M3CanonicalActivationCurrent.Receipt)
            assertEquals(M3CanonicalCurrentState.Unacknowledged((committed.state.current as M3CanonicalActivationCurrent.Receipt).identity), committed.state.currentState)
            val duplicate = fixture.owner.commitAdjacentCanonicalMutation(plan) as M3CanonicalAdjacentCommitResult.Refused
            assertEquals(M3CanonicalAdjacentCommitRefusal.PLAN_DISCARDED, duplicate.reason)

            fixture.owner.close()
            val reopened = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            assertEquals(committed.state.cut, requireNotNull(reopened.activationState()).cut)
            assertTrue(requireNotNull(reopened.activationState()).current is M3CanonicalActivationCurrent.Receipt)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `exact acknowledgement is durable idempotent and revision neutral`() {
        val fixture = activated("exact")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as M3CanonicalActivationCurrent.Receipt
            val ack = M3CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision)

            val first = fixture.owner.acknowledgeCanonicalCurrent(ack)
            assertTrue("first=$first reserved=${fixture.budget.reservedBytes} committed=${fixture.budget.committedBytes} files=${fixture.directory.listFiles().orEmpty().map { it.name }}", first is M3CanonicalAcknowledgementResult.Acknowledged)
            assertEquals(M3CanonicalCurrentState.Acknowledged(current.identity), requireNotNull(fixture.owner.activationState()).currentState)
            assertEquals(M3CanonicalActivationCurrent.None, requireNotNull(fixture.owner.activationState()).current)

            val duplicate = fixture.owner.acknowledgeCanonicalCurrent(ack)
            assertTrue(duplicate is M3CanonicalAcknowledgementResult.Idempotent)
            fixture.owner.close()
            val reopened = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
            assertEquals(M3CanonicalCurrentState.Acknowledged(current.identity), requireNotNull(reopened.activationState()).currentState)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `mismatched or stale acknowledgement is a typed no op and preserves current`() {
        val fixture = activated("no-op")
        try {
            val state = requireNotNull(fixture.owner.activationState())
            val current = state.current as M3CanonicalActivationCurrent.Receipt
            val stale = fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision + 1, state.cut.lineageRevision),
            ) as M3CanonicalAcknowledgementResult.NoOp
            assertEquals(M3CanonicalAcknowledgementNoOp.STALE_REVISION, stale.reason)
            val mismatch = fixture.owner.acknowledgeCanonicalCurrent(
                M3CanonicalAcknowledgement(M3CanonicalReceiptBytes(ByteArray(32) { 7 }), state.cut.geometryRevision, state.cut.lineageRevision),
            ) as M3CanonicalAcknowledgementResult.NoOp
            assertEquals(M3CanonicalAcknowledgementNoOp.COMMAND_MISMATCH, mismatch.reason)
            assertTrue(requireNotNull(fixture.owner.activationState()).current is M3CanonicalActivationCurrent.Receipt)
        } finally { fixture.directory.deleteRecursively() }
    }

    @Test
    fun `acknowledgement recovery exposes old unacknowledged or complete acknowledged state`() {
        M3CanonicalAcknowledgementFault.entries.forEachIndexed { index, fault ->
            val fixture = activated("fault-$index")
            try {
                val state = requireNotNull(fixture.owner.activationState())
                val current = state.current as M3CanonicalActivationCurrent.Receipt
                val committedBefore = fixture.budget.committedBytes
                val physicalBefore = fixture.budget.allocatedBytes(fixture.directory)
                try {
                    fixture.owner.acknowledgeCanonicalCurrent(
                        M3CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision), fault,
                    )
                } catch (_: M3CanonicalAcknowledgementProcessCrash) {
                    // A process crash has no in-process cleanup; reopen owns the cut.
                }
                fixture.owner.close()
                val reopened = opened(M3SurfaceOwnership.open(fixture.group, fixture.directory, fixture.budget))
                val recovered = requireNotNull(reopened.activationState())
                assertEquals(state.cut, recovered.cut)
                assertTrue(recovered.currentState is M3CanonicalCurrentState.Unacknowledged || recovered.currentState is M3CanonicalCurrentState.Acknowledged)
                assertEquals(0L, fixture.budget.reservedBytes)
                assertEquals(
                    fixture.budget.allocatedBytes(fixture.directory) - physicalBefore,
                    fixture.budget.committedBytes - committedBefore,
                )
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    private fun activated(suffix: String): Fixture {
        val directory = Files.createTempDirectory("m3-ack-$suffix").toFile()
        val group = M3SurfaceGroup("ack-$suffix")
        val budget = CountingBudget()
        val legacy = opened(M3SurfaceOwnership.open(group, directory))
        val id = (legacy.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))) as M3SurfaceOwnershipResult.Accepted).owners.single().id
        legacy.transact(M3CanonicalTransactionCommand("current", M3CanonicalOperation.RELOCATION, 0, 0, listOf(id), listOf(M3CanonicalTarget(id, M3Voxel(1, 0, 0), 0, 0, 192))))
        legacy.close()
        M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
        val plan = (M3CanonicalActivation.prepare(group, directory, budget) as M3CanonicalActivationPreparation.Prepared).plan
        return Fixture(directory, group, budget, id, opened(M3SurfaceOwnership.open(group, directory, budget, plan)))
    }

    private fun candidate(x: Int) = M3SurfaceCandidate(null, M3Voxel(x, 0, 0), 0, 0, 192)
    private fun adjacentPlan(fixture: Fixture, state: M3CanonicalActivationState, targetX: Int = 2) =
        (adjacentPreparation(fixture, state, targetX) as M3CanonicalMutationPreparation.Prepared).mutation

    private fun adjacentPreparation(
        fixture: Fixture,
        state: M3CanonicalActivationState,
        targetX: Int,
        owner: M3SurfaceOwnership = fixture.owner,
    ) =
        (M3CompactCanonicalStore.openV6(fixture.group, fixture.directory, fixture.budget)
            as M3CompactCanonicalOpenResult.Opened).store.use { base ->
            val current = (state.current as? M3CanonicalActivationCurrent.Receipt)?.identity
            val store = requireNotNull(M3CanonicalCommitStore.open(fixture.directory, fixture.budget))
            store.use {
                val selected = store.reopen(base, current?.let { M3PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) })
                fun prepare(view: M3CanonicalStateView) = owner.prepareAdjacentMutation(
                    view, adjacentCommand(fixture, state, targetX),
                )
                when (selected) {
                    is M3CanonicalReopenResult.GenerationZero -> prepare(selected.view)
                    is M3CanonicalReopenResult.Selected -> selected.commit.use { prepare(it.view) }
                    is M3CanonicalReopenResult.Refused -> error("reopen refused: $selected")
                }
            }
        }

    private fun adjacentCommand(fixture: Fixture, state: M3CanonicalActivationState, targetX: Int) =
        M3CanonicalTransactionCommand(
            "adjacent-${state.cut.geometryRevision}", M3CanonicalOperation.RELOCATION,
            state.cut.geometryRevision, state.cut.lineageRevision, listOf(fixture.id),
            listOf(M3CanonicalTarget(fixture.id, M3Voxel(targetX, 0, 0), 0, 0, 192)),
        )

    private inline fun <T> withAdjacentView(
        fixture: Fixture,
        state: M3CanonicalActivationState,
        block: (M3CanonicalStateView) -> T,
    ): T = (M3CompactCanonicalStore.openV6(fixture.group, fixture.directory, fixture.budget)
        as M3CompactCanonicalOpenResult.Opened).store.use { base ->
        val current = (state.current as? M3CanonicalActivationCurrent.Receipt)?.identity
        requireNotNull(M3CanonicalCommitStore.open(fixture.directory, fixture.budget)).use { store ->
            when (val selected = store.reopen(
                base, current?.let { M3PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) },
            )) {
                is M3CanonicalReopenResult.GenerationZero -> block(selected.view)
                is M3CanonicalReopenResult.Selected -> selected.commit.use { block(it.view) }
                is M3CanonicalReopenResult.Refused -> error("reopen refused: $selected")
            }
        }
    }
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private data class Fixture(val directory: File, val group: M3SurfaceGroup, val budget: CountingBudget, val id: M3SurfaceId, val owner: M3SurfaceOwnership)
    private class CountingBudget : M3ExclusiveFakeStorageBudget() {
        private data class Token(val id: Int, val bytes: Long)
        private var next = 0
        private val outstanding = linkedMapOf<Token, Long>()
        private val attempts = linkedMapOf<String, MutableList<M3CanonicalPointerReservation>>()
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
            attempts.getOrPut(groupId) { mutableListOf() } += M3CanonicalPointerReservation(token, attemptId, 0, rootBeforeBytes, slotBeforeBytes, selectorBeforeBytes, commitBytes)
            return token
        }
        override fun activationAttempts(groupId: String) = attempts[groupId].orEmpty().toList()
        override fun commitActivationAttempt(reservation: M3CanonicalPointerReservation) {
            commit(reservation.token, reservation.commitBytes)
        }
        override fun releaseActivationAttempt(reservation: M3CanonicalPointerReservation) {
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
