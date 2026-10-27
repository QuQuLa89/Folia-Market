package com.ququla89.foliamarket

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.engine.MarketService
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketDeliveryTest {
    private class QueuedScheduler : SchedulerAdapter {
        data class Delivery(
            val retired: (() -> Unit)?,
            val task: Runnable,
        )

        val asynchronous = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<Delivery>()

        override fun runAtEntity(
            entity: Entity,
            retired: (() -> Unit)?,
            task: Runnable,
        ) {
            deliveries.addLast(Delivery(retired, task))
        }

        override fun runAsync(task: Runnable) {
            asynchronous.addLast(task)
        }

        fun flushAsync() {
            while (asynchronous.isNotEmpty()) asynchronous.removeFirst().run()
        }

        override fun runAtLocation(
            location: Location,
            task: Runnable,
        ) = error("Unexpected location task")

        override fun runGlobal(task: Runnable) = error("Unexpected global task")

        override fun runTimerAsync(
            intervalTicks: Long,
            delayTicks: Long,
            task: Runnable,
        ): AutoCloseable = error("Unexpected timer")

        override fun cancelAll() = Unit
    }

    private class Fixture(
        status: MarketItemStatus = MarketItemStatus.ACTIVE,
    ) {
        val dao = mock(MarketDao::class.java)
        val economy = mock(EconomyService::class.java)
        val scheduler = QueuedScheduler()
        val player = mock(Player::class.java)
        val inventory = mock(PlayerInventory::class.java)
        val stack = mock(ItemStack::class.java)
        val bytes = byteArrayOf(1, 2, 3)
        val listing =
            MarketItem(
                id = 1L,
                sellerUuid = UUID.randomUUID(),
                sellerName = "QuQuLa89",
                itemBytes = bytes,
                price = 100.0,
                listedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60_000L,
                status = status,
            )
        val market = spy(MarketService(dao, economy, scheduler, YamlConfiguration(), Logger.getLogger("MarketDeliveryTest")))

        init {
            `when`(player.uniqueId).thenReturn(UUID.randomUUID())
            `when`(player.name).thenReturn("QuQuLa89")
            `when`(player.isOnline).thenReturn(true)
            `when`(player.inventory).thenReturn(inventory)
            `when`(inventory.itemInMainHand).thenReturn(stack)
            val material = spy(Material.STONE)
            doReturn(false).`when`(material).isAir
            doReturn("block.minecraft.stone").`when`(material).translationKey()
            `when`(stack.type).thenReturn(material)
            `when`(stack.amount).thenReturn(1)
            `when`(stack.clone()).thenReturn(stack)
            `when`(stack.serializeAsBytes()).thenReturn(bytes)
            `when`(economy.isAvailable()).thenReturn(true)
            `when`(economy.has(player, 100.0)).thenReturn(true)
            `when`(economy.withdraw(player, 100.0)).thenReturn(true)
            `when`(economy.deposit(player, 100.0)).thenReturn(true)
            `when`(economy.getCurrencyName()).thenReturn("円")
            `when`(dao.getItemById(1L)).thenReturn(listing)
            `when`(dao.casUpdateStatus(1L, MarketItemStatus.ACTIVE, MarketItemStatus.SOLD)).thenReturn(true)
            `when`(dao.casUpdateStatus(1L, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)).thenReturn(true)
            doReturn(false).`when`(market).returnItemToPlayer(player, stack)
        }
    }

    @Test
    fun `retired and offline buyers are refunded before the listing is restored without seller payment`() {
        for (retired in listOf(false, true)) {
            val f = Fixture()
            mockStatic(ItemStack::class.java).use { items ->
                items.`when`<ItemStack> { ItemStack.deserializeBytes(f.bytes) }.thenReturn(f.stack)
                val result = f.market.purchaseItem(f.player, 1L)
                f.scheduler.flushAsync()
                assertFalse(result.isDone)
                val delivery = f.scheduler.deliveries.removeFirst()
                if (retired) {
                    delivery.retired!!.invoke()
                } else {
                    `when`(f.player.isOnline).thenReturn(false)
                    delivery.task.run()
                }
                // The retired callback only schedules compensation; it does not block an entity thread.
                verify(f.economy, never()).deposit(f.player, 100.0)
                f.scheduler.flushAsync()
                assertFalse(result.join())
                val order = org.mockito.Mockito.inOrder(f.economy, f.dao)
                order.verify(f.economy).deposit(f.player, 100.0)
                order.verify(f.dao).casUpdateStatus(1L, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)
                verify(f.market, never()).returnItemToPlayer(f.player, f.stack)
                verify(
                    f.economy,
                    never(),
                ).deposit(org.mockito.ArgumentMatchers.any(OfflinePlayer::class.java) ?: f.player, org.mockito.ArgumentMatchers.eq(95.0))
            }
        }
    }

    @Test
    fun `successful purchase completes on delivery and credits seller only afterwards`() {
        val f = Fixture()
        val seller = mock(OfflinePlayer::class.java)
        mockStatic(ItemStack::class.java).use { items ->
            mockStatic(Bukkit::class.java).use { bukkit ->
                items.`when`<ItemStack> { ItemStack.deserializeBytes(f.bytes) }.thenReturn(f.stack)
                bukkit.`when`<OfflinePlayer> { Bukkit.getOfflinePlayer(f.listing.sellerUuid) }.thenReturn(seller)
                `when`(f.economy.deposit(seller, 95.0)).thenReturn(true)
                val result = f.market.purchaseItem(f.player, 1L)
                f.scheduler.flushAsync()
                assertFalse(result.isDone)
                f.scheduler.deliveries
                    .removeFirst()
                    .task
                    .run()
                assertTrue(result.join())
                verify(f.market).returnItemToPlayer(f.player, f.stack)
                verify(f.economy, never()).deposit(seller, 95.0)
                f.scheduler.flushAsync()
                verify(f.economy).deposit(seller, 95.0)
                verify(f.economy, never()).deposit(f.player, 100.0)
            }
        }
    }

    @Test
    fun `failed refund leaves the sale reserved and reports the compensation failure`() {
        val f = Fixture()
        `when`(f.economy.deposit(f.player, 100.0)).thenReturn(false)
        mockStatic(ItemStack::class.java).use { items ->
            items.`when`<ItemStack> { ItemStack.deserializeBytes(f.bytes) }.thenReturn(f.stack)
            val result = f.market.purchaseItem(f.player, 1L)
            f.scheduler.flushAsync()
            f.scheduler.deliveries
                .removeFirst()
                .retired!!
                .invoke()
            f.scheduler.flushAsync()
            assertFailsWith<CompletionException> { result.join() }
            verify(f.dao, never()).casUpdateStatus(1L, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)
        }
    }

    @Test
    fun `invalid item data refunds the buyer and restores the sale`() {
        val f = Fixture()
        mockStatic(ItemStack::class.java).use { items ->
            items.`when`<ItemStack> { ItemStack.deserializeBytes(f.bytes) }.thenThrow(IllegalArgumentException("Invalid item"))
            val result = f.market.purchaseItem(f.player, 1L)
            f.scheduler.flushAsync()
            assertFalse(result.join())
            verify(f.economy).deposit(f.player, 100.0)
            verify(f.dao).casUpdateStatus(1L, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)
        }
    }

    @Test
    fun `undelivered cancellation restores the original active or expired status`() {
        for (status in listOf(MarketItemStatus.ACTIVE, MarketItemStatus.EXPIRED)) {
            for (retired in listOf(false, true)) {
                val f = Fixture(status)
                `when`(f.player.uniqueId).thenReturn(f.listing.sellerUuid)
                `when`(f.dao.casUpdateStatus(1L, status, MarketItemStatus.CANCELLED)).thenReturn(true)
                `when`(f.dao.casUpdateStatus(1L, MarketItemStatus.CANCELLED, status)).thenReturn(true)
                mockStatic(ItemStack::class.java).use { items ->
                    items.`when`<ItemStack> { ItemStack.deserializeBytes(f.bytes) }.thenReturn(f.stack)
                    val result = f.market.cancelListing(f.player, 1L)
                    f.scheduler.flushAsync()
                    assertFalse(result.isDone)
                    val delivery = f.scheduler.deliveries.removeFirst()
                    if (retired) {
                        delivery.retired!!.invoke()
                    } else {
                        `when`(f.player.isOnline).thenReturn(false)
                        delivery.task.run()
                    }
                    f.scheduler.flushAsync()
                    assertFalse(result.join())
                    verify(f.dao).casUpdateStatus(1L, MarketItemStatus.CANCELLED, status)
                    verify(f.market, never()).returnItemToPlayer(f.player, f.stack)
                }
            }
        }
    }

    @Test
    fun `failed listings return online or remain reclaimable after retirement`() {
        for (databaseError in listOf(false, true)) {
            for (retired in listOf(false, true)) {
                val f = Fixture()
                val listingCaptor = ArgumentCaptor.forClass(MarketItem::class.java)
                if (databaseError) {
                    `when`(
                        f.dao.insertItemIfBelowLimit(
                            org.mockito.ArgumentMatchers.any() ?: f.listing,
                            org.mockito.ArgumentMatchers.anyInt(),
                        ),
                    ).thenThrow(IllegalStateException("Database failure"))
                } else {
                    `when`(
                        f.dao.insertItemIfBelowLimit(
                            org.mockito.ArgumentMatchers.any() ?: f.listing,
                            org.mockito.ArgumentMatchers.anyInt(),
                        ),
                    ).thenReturn(null)
                }
                `when`(f.dao.insertItem(org.mockito.ArgumentMatchers.any() ?: f.listing)).thenReturn(2L)
                mockConstruction(ItemStack::class.java).use {
                    val result = f.market.listItem(f.player, 100.0)
                    f.scheduler.flushAsync()
                    assertFalse(result.isDone)
                    val delivery = f.scheduler.deliveries.removeFirst()
                    if (retired) delivery.retired!!.invoke() else delivery.task.run()
                    f.scheduler.flushAsync()
                    assertFalse(result.join())
                    if (retired) {
                        verify(f.dao).insertItem(listingCaptor.capture() ?: f.listing)
                        val recovery = listingCaptor.value
                        assertEquals(MarketItemStatus.EXPIRED, recovery.status)
                        assertEquals(f.player.uniqueId, recovery.sellerUuid)
                        assertContentEquals(f.bytes, recovery.itemBytes)
                        verify(f.market, never()).returnItemToPlayer(f.player, f.stack)
                    } else {
                        verify(f.market).returnItemToPlayer(f.player, f.stack)
                        verify(f.dao, never()).insertItem(org.mockito.ArgumentMatchers.any() ?: f.listing)
                    }
                }
            }
        }
    }
}
