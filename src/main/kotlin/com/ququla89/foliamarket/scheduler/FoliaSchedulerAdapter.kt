package com.ququla89.foliamarket.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit

/**
 * Scheduler adapter implementation specifically for Folia multi-threaded regional ticking.
 */
class FoliaSchedulerAdapter(
    private val plugin: Plugin,
) : SchedulerAdapter {
    override fun runAtEntity(
        entity: Entity,
        task: Runnable,
    ) {
        entity.scheduler.run(plugin, { _ -> task.run() }, null)
    }

    override fun runAtLocation(
        location: Location,
        task: Runnable,
    ) {
        Bukkit.getRegionScheduler().run(plugin, location) { _ -> task.run() }
    }

    override fun runGlobal(task: Runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin) { _ -> task.run() }
    }

    override fun runAsync(task: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin) { _ -> task.run() }
    }

    override fun runTimerAsync(
        intervalTicks: Long,
        delayTicks: Long,
        task: Runnable,
    ): AutoCloseable {
        val delayMs = (delayTicks * 50L).coerceAtLeast(1L)
        val periodMs = (intervalTicks * 50L).coerceAtLeast(1L)

        val scheduledTask: ScheduledTask =
            Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                { _ -> task.run() },
                delayMs,
                periodMs,
                TimeUnit.MILLISECONDS,
            )

        return AutoCloseable { scheduledTask.cancel() }
    }

    override fun cancelAll() {
        Bukkit.getAsyncScheduler().cancelTasks(plugin)
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin)
    }
}
