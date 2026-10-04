package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Virtual time controls the real latest-only lanes; no sleeps or success scalars. */
internal class PressureObservationScheduler : AbstractExecutorService(), ScheduledExecutorService {
    var nowNs = 1_000_000_000L
        private set
    private var closed = false
    private var sequence = 0L
    private val tasks = PriorityQueue<ScheduledTask<*>>(compareBy({ it.dueNs }, { it.sequence }))

    fun advanceBy(nanos: Long) {
        require(nanos >= 0)
        val end = nowNs + nanos
        var executed = 0
        while (tasks.peek()?.dueNs?.let { it <= end } == true) {
            check(++executed <= 10_000) { "unbounded virtual-time campaign" }
            val task = tasks.remove()
            nowNs = maxOf(nowNs, task.dueNs)
            task.run()
        }
        nowNs = end
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        schedule(Callable { command.run(); Unit }, delay, unit)

    override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
        check(!closed)
        return ScheduledTask(callable, nowNs + unit.toNanos(delay), ++sequence).also(tasks::add)
    }

    private inner class ScheduledTask<V>(callable: Callable<V>, val dueNs: Long, val sequence: Long) :
        FutureTask<V>(callable), ScheduledFuture<V> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(dueNs - nowNs, TimeUnit.NANOSECONDS)
        override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS)
            .compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }

    override fun execute(command: Runnable) { schedule(command, 0, TimeUnit.NANOSECONDS) }
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> {
        closed = true
        val remaining = tasks.map { it as Runnable }.toMutableList()
        tasks.clear()
        return remaining
    }
    override fun isShutdown() = closed
    override fun isTerminated() = closed && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
        error("observation lanes must not use a periodic catch-up scheduler")
    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        error("observation lanes must schedule one current delivery")
}
