package com.ququla89.foliamarket.scheduler

import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * Common scheduling abstraction layer supporting both Paper and Folia.
 */
interface SchedulerAdapter {
    /**
     * Executes a task in the thread context owned by the specified entity.
     */
    fun runAtEntity(
        entity: Entity,
        task: Runnable,
    )

    /**
     * Executes a task in the region thread context of the specified location.
     */
    fun runAtLocation(
        location: Location,
        task: Runnable,
    )

    /**
     * Executes a task in the global server context.
     */
    fun runGlobal(task: Runnable)

    /**
     * Executes a task asynchronously off the main server tick threads.
     */
    fun runAsync(task: Runnable)

    /**
     * Schedules a repeating asynchronous task.
     *
     * @param intervalTicks interval in Minecraft ticks (1 tick = 50ms)
     * @param delayTicks initial delay in ticks
     * @param task the runnable task
     * @return an AutoCloseable handle to cancel the task
     */
    fun runTimerAsync(
        intervalTicks: Long,
        delayTicks: Long = 0L,
        task: Runnable,
    ): AutoCloseable

    /**
     * Cancels all tasks scheduled by this adapter.
     */
    fun cancelAll()
}
