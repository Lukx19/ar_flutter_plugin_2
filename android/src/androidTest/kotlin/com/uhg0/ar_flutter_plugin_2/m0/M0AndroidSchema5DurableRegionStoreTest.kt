package com.uhg0.ar_flutter_plugin_2.m0

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M0AndroidSchema5DurableRegionStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.noBackupFilesDir, "m0c-native-${UUID.randomUUID()}")
        check(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun nativeDirectorySyncPublishesAndRecoversTheVisibleRoot() {
        val store = nativeStore(root)
        assertEquals(0L, store.visibleRootId)
        assertTrue(store.publish(listOf(cut(2, 0), cut(2, 1))).published)

        val restarted = nativeStore(root)
        assertEquals(1L, restarted.visibleRootId)
        assertEquals(setOf(2L), restarted.visibleCuts.values.map { it.generation }.toSet())
        assertFalse(restarted.hasStaging)
        assertTrue(restarted.hasReceipt(1))
        assertTrue(restarted.hasActivation(1))
        assertTrue(restarted.hasEviction(1))
        assertTrue(restarted.hasMigration(1))
        assertTrue(restarted.hasTombstone(1))
    }

    @Test
    fun nativeDirectorySyncHandlesADeepAppPrivatePath() {
        var deep = root
        repeat(14) { index ->
            deep = File(deep, "segment_${index.toString().padStart(2, '0')}_${"x".repeat(32)}")
            check(deep.mkdirs())
        }

        val store = nativeStore(deep)
        assertTrue(store.publish(listOf(cut(2, 0))).published)
        val restarted = nativeStore(deep)
        assertEquals(1L, restarted.visibleRootId)
        assertEquals(2L, restarted.visibleCuts.values.single().generation)
    }

    @Test
    fun childProcessDeathBeforeRootSwitchKeepsTheOldPointerAndOrphanRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val directory = File(
            instrumentation.targetContext.noBackupFilesDir,
            "m0c-process-${UUID.randomUUID()}",
        )
        check(directory.mkdirs())
        try {
            testContext.startService(
                Intent(testContext, M0CrashBeforeRootSwitchService::class.java)
                    .putExtra(M0CrashBeforeRootSwitchService.EXTRA_DIRECTORY, directory.path),
            )
            val orphanRoot = File(directory, "roots/root_1.json")
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (!orphanRoot.isFile && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50L)
            }
            assertTrue("child process must persist the orphan root", orphanRoot.isFile)
            // The callback kills the child immediately after persistRoot and
            // before receipt/pointer publication; allow the process teardown
            // to finish before reopening the directory from this process.
            SystemClock.sleep(500L)

            val recovered = nativeStore(directory)
            assertEquals(0L, recovered.visibleRootId)
            assertEquals(setOf(1L), recovered.visibleCuts.values.map { it.generation }.toSet())
            assertTrue(recovered.hasRoot(1))
            assertFalse(recovered.hasReceipt(1))
            assertFalse(recovered.hasStaging)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun nativeStore(directory: File) = M0Schema5DurableRegionCutStore(
        directory = directory,
        initialCuts = listOf(cut(1, 0), cut(1, 1)),
        directorySync = M0DirectorySync.strictAndroid,
    )

    private fun cut(generation: Long, coordinate: Int) = M0RegionPairCut(
        region = M0RegionCoordinate(coordinate, 0, 0),
        generation = generation,
        geometryRevision = generation,
        coverageRevision = generation,
        captureEvaluatedThrough = 4,
        pendingThrough = 2,
    )
}
