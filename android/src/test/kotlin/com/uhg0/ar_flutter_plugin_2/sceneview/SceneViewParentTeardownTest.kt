package com.uhg0.ar_flutter_plugin_2.sceneview

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.lang.ref.WeakReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SceneViewParentTeardownTest {
    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    /** Stands in for a disposed host and the payload it keeps alive. */
    private class RetainedHost {
        val environment = ByteArray(1024 * 1024)
        var parentDestroys = 0
    }

    private val parent = TestLifecycleOwner()

    @Before
    fun mainThread() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @After
    fun resetMainThread() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun `released teardown leaves no observer on the parent lifecycle`() {
        val teardowns = List(6) { SceneViewParentTeardown(parent.lifecycle) {} }
        assertEquals(6, parent.lifecycle.observerCount)

        teardowns.forEach(SceneViewParentTeardown::release)
        teardowns.first().release()

        assertEquals(0, parent.lifecycle.observerCount)
        assertFalse(teardowns.any(SceneViewParentTeardown::isRegistered))
    }

    @Test
    fun `parent destroy runs the teardown once and then releases it`() {
        var destroys = 0
        val teardown = SceneViewParentTeardown(parent.lifecycle) { destroys++ }

        parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        teardown.release()

        assertEquals(1, destroys)
        assertFalse(teardown.isRegistered)
        assertEquals(0, parent.lifecycle.observerCount)
    }

    @Test
    fun `released teardown does not run on a later parent destroy`() {
        var destroys = 0
        val teardown = SceneViewParentTeardown(parent.lifecycle) { destroys++ }

        teardown.release()
        parent.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertEquals(0, destroys)
    }

    @Test
    fun `parent lifecycle does not retain a host after release`() {
        val retained = mutableListOf<WeakReference<RetainedHost>>()
        repeat(4) {
            val host = RetainedHost()
            val teardown = SceneViewParentTeardown(parent.lifecycle) {
                host.parentDestroys++
                host.environment[0] = 1
            }
            retained += WeakReference(host)
            teardown.release()
        }

        var attempts = 0
        while (retained.any { it.get() != null } && attempts++ < 20) {
            System.gc()
            Thread.sleep(10)
        }

        assertEquals(0, parent.lifecycle.observerCount)
        retained.forEach { assertNull("released host is collectable", it.get()) }
    }
}
