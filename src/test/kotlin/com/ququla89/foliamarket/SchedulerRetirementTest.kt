package com.ququla89.foliamarket

import com.ququla89.foliamarket.scheduler.FoliaSchedulerAdapter
import com.ququla89.foliamarket.scheduler.PaperSchedulerAdapter
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals

class SchedulerRetirementTest {
    @Test
    fun `Folia immediately compensates an already retired entity`() {
        val plugin = mock(Plugin::class.java)
        val player = mock(Player::class.java)
        `when`(player.scheduler).thenReturn(mock(EntityScheduler::class.java))
        var retired = 0
        var executed = 0
        FoliaSchedulerAdapter(plugin).runAtEntity(player, { retired++ }, Runnable { executed++ })
        assertEquals(1, retired)
        assertEquals(0, executed)
    }

    @Test
    fun `Folia passes retirement callbacks to accepted entity tasks`() {
        for (retire in listOf(false, true)) {
            val plugin = mock(Plugin::class.java)
            val player = mock(Player::class.java)
            val scheduler = mock(EntityScheduler::class.java)
            val scheduled = mock(ScheduledTask::class.java)
            `when`(player.scheduler).thenReturn(scheduler)
            var callback: Runnable? = null
            var task: Consumer<ScheduledTask>? = null
            doAnswer {
                task = it.getArgument(1)
                callback = it.getArgument(2)
                scheduled
            }.`when`(scheduler).run(eq(plugin), any(), any())
            var retired = 0
            var executed = 0
            FoliaSchedulerAdapter(plugin).runAtEntity(player, { retired++ }, Runnable { executed++ })
            assertEquals(0, retired)
            assertEquals(0, executed)
            if (retire) callback!!.run() else task!!.accept(scheduled)
            assertEquals(if (retire) 1 else 0, retired)
            assertEquals(if (retire) 0 else 1, executed)
        }
    }

    @Test
    fun `Paper rechecks online state when the scheduled task executes`() {
        for (online in listOf(false, true)) {
            mockStatic(Bukkit::class.java).use { bukkit ->
                val plugin = mock(Plugin::class.java)
                val scheduler = mock(BukkitScheduler::class.java)
                val player = mock(Player::class.java)
                `when`(player.isOnline).thenReturn(online)
                bukkit.`when`<BukkitScheduler> { Bukkit.getScheduler() }.thenReturn(scheduler)
                var task: Runnable? = null
                doAnswer {
                    task = it.getArgument(1)
                    mock(BukkitTask::class.java)
                }.`when`(scheduler).runTask(eq(plugin), any(Runnable::class.java))
                var retired = 0
                var executed = 0
                PaperSchedulerAdapter(plugin).runAtEntity(player, { retired++ }, Runnable { executed++ })
                task!!.run()
                assertEquals(if (online) 0 else 1, retired)
                assertEquals(if (online) 1 else 0, executed)
            }
        }
    }
}
