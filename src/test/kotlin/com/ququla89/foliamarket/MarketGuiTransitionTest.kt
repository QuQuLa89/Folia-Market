package com.ququla89.foliamarket

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.database.model.MarketSortMode
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.engine.MarketService
import com.ququla89.foliamarket.gui.GuiListener
import com.ququla89.foliamarket.gui.GuiType
import com.ququla89.foliamarket.gui.MarketGui
import com.ququla89.foliamarket.gui.MarketHolder
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketGuiTransitionTest {
    @Test
    fun `opening market preserves new slot state after the old market holder closes`() {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("FoliaMarket")
        val player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        val oldInventory = mock(Inventory::class.java)
        `when`(oldInventory.holder).thenReturn(MarketHolder(GuiType.MY_LISTINGS))
        val newInventory = mock(Inventory::class.java)
        val view = mock(InventoryView::class.java)
        `when`(view.topInventory).thenReturn(oldInventory)
        `when`(player.openInventory).thenReturn(view)
        val scheduler = mock(SchedulerAdapter::class.java)
        doAnswer {
            it.getArgument<Runnable>(0).run()
            null
        }.`when`(scheduler)
            .runAsync(any(Runnable::class.java) ?: Runnable {})
        doAnswer {
            it.getArgument<Runnable>(2).run()
            null
        }.`when`(scheduler)
            .runAtEntity(any(Entity::class.java) ?: player, isNull(), any(Runnable::class.java) ?: Runnable {})
        val dao = mock(MarketDao::class.java)
        val item =
            MarketItem(
                id = 42L,
                sellerUuid = UUID.randomUUID(),
                sellerName = "QuQuLa89",
                itemBytes = byteArrayOf(1),
                price = 100.0,
                listedAt = 1L,
                expiresAt = Long.MAX_VALUE,
                status = MarketItemStatus.ACTIVE,
            )
        `when`(dao.countActiveItems(null)).thenReturn(1)
        `when`(dao.getActiveItems(0, MarketGui.PAGE_SIZE, MarketSortMode.NEWEST, null)).thenReturn(listOf(item))
        val economy = mock(EconomyService::class.java)
        `when`(economy.getCurrencyName()).thenReturn("円")
        val gui = MarketGui(plugin, dao, economy, scheduler, YamlConfiguration())
        val listener = GuiListener(gui, mock(MarketService::class.java))
        val close = mock(InventoryCloseEvent::class.java)
        `when`(close.inventory).thenReturn(oldInventory)
        `when`(close.player).thenReturn(player)
        doAnswer {
            listener.onInventoryClose(close)
            `when`(view.topInventory).thenReturn(newInventory)
            null
        }.`when`(player).openInventory(newInventory)
        val stack = mock(ItemStack::class.java)
        `when`(stack.clone()).thenReturn(stack)

        mockStatic(Bukkit::class.java).use { bukkit ->
            mockStatic(ItemStack::class.java).use { items ->
                mockConstruction(ItemStack::class.java).use {
                    items.`when`<ItemStack> { ItemStack.deserializeBytes(item.itemBytes) }.thenReturn(stack)
                    bukkit
                        .`when`<Inventory> {
                            Bukkit.createInventory(any(InventoryHolder::class.java), eq(54), any(Component::class.java))
                        }.thenAnswer {
                            `when`(newInventory.holder).thenReturn(it.getArgument(0))
                            newInventory
                        }
                    gui.openMarket(player)
                    val field = MarketGui::class.java.getDeclaredField("playerSlotState").apply { isAccessible = true }
                    val state = field.get(gui) as Map<*, *>
                    assertEquals(mapOf(0 to 42L), state[player])
                    gui.openMarket(player)
                    verify(player, times(1)).openInventory(newInventory)
                    verify(newInventory, times(1)).setItem(0, stack)
                    assertEquals(mapOf(0 to 42L), state[player])
                }
            }
        }
    }
}
