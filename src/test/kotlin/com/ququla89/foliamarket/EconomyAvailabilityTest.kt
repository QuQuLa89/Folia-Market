package com.ququla89.foliamarket

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.engine.MarketService
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EconomyAvailabilityTest {
    private val logger = Logger.getLogger("EconomyAvailabilityTest")

    @Test
    fun `missing Vault or missing provider rejects money operations and transactions before side effects`() {
        for (vaultInstalled in listOf(false, true)) {
            mockStatic(Bukkit::class.java).use { bukkit ->
                val plugin = mock(Plugin::class.java)
                val plugins = mock(PluginManager::class.java)
                val services = mock(ServicesManager::class.java)
                bukkit.`when`<PluginManager> { Bukkit.getPluginManager() }.thenReturn(plugins)
                bukkit.`when`<ServicesManager> { Bukkit.getServicesManager() }.thenReturn(services)
                if (vaultInstalled) `when`(plugins.getPlugin("Vault")).thenReturn(plugin)
                val economy = EconomyService(plugin, logger)
                val player = mock(Player::class.java)
                assertFalse(economy.isAvailable())
                for (amount in listOf(0.0, 100.0)) {
                    assertFalse(economy.has(player, amount))
                    assertFalse(economy.withdraw(player, amount))
                    assertFalse(economy.deposit(player, amount))
                }

                val dao = mock(MarketDao::class.java)
                val scheduler = mock(SchedulerAdapter::class.java)
                val market = MarketService(dao, economy, scheduler, YamlConfiguration(), logger)
                assertFalse(market.listItem(player, 100.0).join())
                assertFalse(market.purchaseItem(player, 1L).join())
                verifyNoInteractions(dao, scheduler)
                verify(player, org.mockito.Mockito.never()).getInventory()
            }
        }
    }

    @Test
    fun `connected provider preserves successful and failed payment results`() {
        mockStatic(Bukkit::class.java).use { bukkit ->
            val plugin = mock(Plugin::class.java)
            val plugins = mock(PluginManager::class.java)
            val services = mock(ServicesManager::class.java)
            val provider = mock(Economy::class.java)
            val player = mock(Player::class.java)
            bukkit.`when`<PluginManager> { Bukkit.getPluginManager() }.thenReturn(plugins)
            bukkit.`when`<ServicesManager> { Bukkit.getServicesManager() }.thenReturn(services)
            `when`(plugins.getPlugin("Vault")).thenReturn(plugin)
            `when`(services.getRegistration(Economy::class.java)).thenReturn(
                RegisteredServiceProvider(Economy::class.java, provider, ServicePriority.Normal, plugin),
            )
            val economy = EconomyService(plugin, logger)
            assertTrue(economy.isAvailable())
            `when`(provider.has(player, 100.0)).thenReturn(true)
            assertTrue(economy.has(player, 100.0))
            for (type in listOf(EconomyResponse.ResponseType.SUCCESS, EconomyResponse.ResponseType.FAILURE)) {
                val response = EconomyResponse(100.0, 200.0, type, "")
                `when`(provider.withdrawPlayer(player, 100.0)).thenReturn(response)
                `when`(provider.depositPlayer(player, 100.0)).thenReturn(response)
                kotlin.test.assertEquals(type == EconomyResponse.ResponseType.SUCCESS, economy.withdraw(player, 100.0))
                kotlin.test.assertEquals(type == EconomyResponse.ResponseType.SUCCESS, economy.deposit(player, 100.0))
            }
        }
    }

    @Test
    fun `available economy still rolls back sale on withdrawal failure without delivery`() {
        val player = mock(Player::class.java)
        val buyerId = UUID.randomUUID()
        `when`(player.uniqueId).thenReturn(buyerId)
        val economy = mock(EconomyService::class.java)
        `when`(economy.isAvailable()).thenReturn(true)
        `when`(economy.has(player, 100.0)).thenReturn(true)
        val dao = mock(MarketDao::class.java)
        val item =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "QuQuLa89",
                itemBytes = byteArrayOf(1),
                price = 100.0,
                listedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60_000L,
                status = MarketItemStatus.ACTIVE,
            )
        `when`(dao.getItemById(1L)).thenReturn(item)
        `when`(dao.casUpdateStatus(1L, MarketItemStatus.ACTIVE, MarketItemStatus.SOLD)).thenReturn(true)
        val scheduler = mock(SchedulerAdapter::class.java)
        doAnswer {
            it.getArgument<Runnable>(0).run()
            null
        }.`when`(scheduler).runAsync(org.mockito.ArgumentMatchers.any(Runnable::class.java) ?: Runnable {})
        val market = MarketService(dao, economy, scheduler, YamlConfiguration(), logger)

        assertFalse(market.purchaseItem(player, 1L).join())

        verify(economy).withdraw(player, 100.0)
        verify(dao).casUpdateStatus(1L, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)
        verify(player, org.mockito.Mockito.never()).getInventory()
    }
}
