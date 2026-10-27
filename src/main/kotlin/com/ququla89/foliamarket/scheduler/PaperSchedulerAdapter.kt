package com.ququla89.foliamarket.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * Standard Bukkit scheduler adapter fallback for Paper environments.
 */
class PaperSchedulerAdapter(
    private val plugin: Plugin,
) : SchedulerAdapter {
    override fun runAtEntity(
        entity: Entity,
        retired: (() -> Unit)?,
        task: Runnable,
    ) {
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                if ((entity is Player && !entity.isOnline) || (entity !is Player && !entity.isValid)) {
                    retired?.invoke()
                } else {
                    task.run()
                }
            },
        )
    }

    override fun runAtLocation(
        location: Location,
        task: Runnable,
    ) {
        Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun runGlobal(task: Runnable) {
        Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun runAsync(task: Runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task)
    }

    override fun runTimerAsync(
        intervalTicks: Long,
        delayTicks: Long,
        task: Runnable,
    ): AutoCloseable {
        val bukkitTask: BukkitTask =
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                task,
                delayTicks,
                intervalTicks,
            )
        return AutoCloseable { bukkitTask.cancel() }
    }

    override fun cancelAll() {
        Bukkit.getScheduler().cancelTasks(plugin)
    }
}
