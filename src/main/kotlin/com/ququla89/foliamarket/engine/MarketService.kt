package com.ququla89.foliamarket.engine

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import com.ququla89.foliamarket.util.ItemSerializer
import com.ququla89.foliamarket.util.MessageUtil
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Core transactional engine handling thread-safe marketplace operations.
 */
class MarketService(
    private val dao: MarketDao,
    private val economyService: EconomyService,
    private val scheduler: SchedulerAdapter,
    private var config: FileConfiguration,
    private val logger: Logger,
) {
    var taxPercent: Double = config.getDouble("economy.tax-percent", 5.0)
        private set
    var minPrice: Double = config.getDouble("economy.min-price", 1.0)
        private set
    var maxPrice: Double = config.getDouble("economy.max-price", 1000000.0)
        private set
    var durationHours: Long = config.getLong("market.listing-duration-hours", 72L)
        private set
    var maxListingsPerPlayer: Int = config.getInt("market.max-listings-per-player", 10)
        private set

    fun reloadConfig(newConfig: FileConfiguration) {
        this.config = newConfig
        this.taxPercent = newConfig.getDouble("economy.tax-percent", 5.0)
        this.minPrice = newConfig.getDouble("economy.min-price", 1.0)
        this.maxPrice = newConfig.getDouble("economy.max-price", 1000000.0)
        this.durationHours = newConfig.getLong("market.listing-duration-hours", 72L)
        this.maxListingsPerPlayer = newConfig.getInt("market.max-listings-per-player", 10)
    }

    /**
     * Lists the item currently held in the player's main hand.
     */
    fun listItem(
        player: Player,
        price: Double,
    ): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val prefix = getPrefix()

        if (price < minPrice || price > maxPrice) {
            MessageUtil.send(
                player,
                config.getString("messages.price-out-of-range", "<red>価格が範囲外です</red>"),
                mapOf("min" to minPrice.toString(), "max" to maxPrice.toString()),
                prefix,
            )
            future.complete(false)
            return future
        }

        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.type.isAir || itemInHand.amount <= 0) {
            MessageUtil.send(
                player,
                config.getString("messages.air-item", "<red>空気は出品できません</red>"),
                prefix = prefix,
            )
            future.complete(false)
            return future
        }

        // Clone item to list and remove from player's inventory
        val itemToSell = itemInHand.clone()
        player.inventory.setItemInMainHand(ItemStack(Material.AIR))

        scheduler.runAsync {
            try {
                val activeCount = dao.countActiveItemsBySeller(player.uniqueId)
                if (activeCount >= maxListingsPerPlayer) {
                    // Refund item on player region thread
                    scheduler.runAtEntity(player) {
                        returnItemToPlayer(player, itemToSell)
                        MessageUtil.send(
                            player,
                            config.getString("messages.max-listings-reached", "<red>出品上限です</red>"),
                            mapOf("max" to maxListingsPerPlayer.toString()),
                            prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                val now = System.currentTimeMillis()
                val expiresAt = now + durationHours * 3600 * 1000L
                val bytes = ItemSerializer.toByteArray(itemToSell)
                val displayName = getItemDisplayName(itemToSell)

                val marketItem =
                    MarketItem(
                        sellerUuid = player.uniqueId,
                        sellerName = player.name,
                        itemName = displayName,
                        itemBytes = bytes,
                        price = price,
                        listedAt = now,
                        expiresAt = expiresAt,
                        status = MarketItemStatus.ACTIVE,
                    )

                val id = dao.insertItem(marketItem)
                if (id <= 0) {
                    scheduler.runAtEntity(player) {
                        returnItemToPlayer(player, itemToSell)
                        MessageUtil.send(player, "<red>データベース保存に失敗しました。</red>", prefix = prefix)
                    }
                    future.complete(false)
                    return@runAsync
                }

                scheduler.runAtEntity(player) {
                    MessageUtil.send(
                        player,
                        config.getString("messages.listing-success", "<green>出品しました</green>"),
                        mapOf(
                            "item" to displayName,
                            "price" to "%,.2f".format(price),
                            "currency" to economyService.getCurrencyName(),
                            "tax" to taxPercent.toString(),
                        ),
                        prefix,
                    )
                }
                future.complete(true)
            } catch (e: Exception) {
                logger.severe("Failed to list item for player ${player.name}: ${e.message}")
                scheduler.runAtEntity(player) {
                    returnItemToPlayer(player, itemToSell)
                    MessageUtil.send(player, "<red>予期せぬエラーが発生しました。</red>", prefix = prefix)
                }
                future.completeExceptionally(e)
            }
        }

        return future
    }

    /**
     * Atomically purchases an item from the market using Compare-And-Swap (CAS).
     */
    fun purchaseItem(
        buyer: Player,
        itemId: Long,
    ): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val prefix = getPrefix()

        scheduler.runAsync {
            try {
                val item = dao.getItemById(itemId)
                if (item == null || item.status != MarketItemStatus.ACTIVE || item.expiresAt <= System.currentTimeMillis()) {
                    scheduler.runAtEntity(buyer) {
                        MessageUtil.send(
                            buyer,
                            config.getString("messages.buy-failed-already-sold", "<red>売り切れました</red>"),
                            prefix = prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                if (item.sellerUuid == buyer.uniqueId) {
                    scheduler.runAtEntity(buyer) {
                        MessageUtil.send(buyer, "<red>自分が出品した商品は購入できません。</red>", prefix = prefix)
                    }
                    future.complete(false)
                    return@runAsync
                }

                // Balance pre-check
                if (!economyService.has(buyer, item.price)) {
                    scheduler.runAtEntity(buyer) {
                        MessageUtil.send(
                            buyer,
                            config.getString("messages.buy-insufficient-funds", "<red>残高不足です</red>"),
                            mapOf(
                                "price" to "%,.2f".format(item.price),
                                "currency" to economyService.getCurrencyName(),
                            ),
                            prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                // Atomic CAS update: ACTIVE -> SOLD
                val casSuccess = dao.casUpdateStatus(itemId, MarketItemStatus.ACTIVE, MarketItemStatus.SOLD)
                if (!casSuccess) {
                    scheduler.runAtEntity(buyer) {
                        MessageUtil.send(
                            buyer,
                            config.getString("messages.buy-failed-already-sold", "<red>売り切れました</red>"),
                            prefix = prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                // Financial transaction
                val withdrawSuccess = economyService.withdraw(buyer, item.price)
                if (!withdrawSuccess) {
                    // Rollback CAS if withdrawal failed
                    dao.casUpdateStatus(itemId, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)
                    scheduler.runAtEntity(buyer) {
                        MessageUtil.send(buyer, "<red>残高の引き落としに失敗しました。</red>", prefix = prefix)
                    }
                    future.complete(false)
                    return@runAsync
                }

                val taxAmount = item.price * (taxPercent / 100.0)
                val netPrice = item.price - taxAmount

                val seller = Bukkit.getOfflinePlayer(item.sellerUuid)
                economyService.deposit(seller, netPrice)

                val itemStack = ItemSerializer.fromByteArray(item.itemBytes)
                val displayName = getItemDisplayName(itemStack)

                // Deliver item and receipt on buyer's entity thread
                scheduler.runAtEntity(buyer) {
                    val dropped = returnItemToPlayer(buyer, itemStack)
                    if (dropped) {
                        MessageUtil.send(
                            buyer,
                            config.getString("messages.buy-inventory-full", "<yellow>足元にドロップしました</yellow>"),
                            prefix = prefix,
                        )
                    }

                    MessageUtil.send(
                        buyer,
                        config.getString("messages.buy-success", "<green>購入しました</green>"),
                        mapOf(
                            "item" to displayName,
                            "price" to "%,.2f".format(item.price),
                            "currency" to economyService.getCurrencyName(),
                        ),
                        prefix,
                    )

                    MessageUtil.sendReceipt(
                        buyer,
                        displayName,
                        itemStack.amount,
                        item.price,
                        economyService.getCurrencyName(),
                        item.sellerName,
                    )
                }

                // Notify seller if online
                val onlineSeller = Bukkit.getPlayer(item.sellerUuid)
                if (onlineSeller != null) {
                    scheduler.runAtEntity(onlineSeller) {
                        MessageUtil.send(
                            onlineSeller,
                            config.getString("messages.sale-notification", "<gold>売れました</gold>"),
                            mapOf(
                                "item" to displayName,
                                "buyer" to buyer.name,
                                "net_price" to "%,.2f".format(netPrice),
                                "tax_amount" to "%,.2f".format(taxAmount),
                                "currency" to economyService.getCurrencyName(),
                            ),
                            prefix,
                        )
                    }
                }

                future.complete(true)
            } catch (e: Exception) {
                logger.severe("Error during purchase of item $itemId by ${buyer.name}: ${e.message}")
                future.completeExceptionally(e)
            }
        }

        return future
    }

    /**
     * Cancels an active listing and returns the item to the seller.
     */
    fun cancelListing(
        seller: Player,
        itemId: Long,
    ): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val prefix = getPrefix()

        scheduler.runAsync {
            try {
                val item = dao.getItemById(itemId)
                if (item == null || item.sellerUuid != seller.uniqueId) {
                    scheduler.runAtEntity(seller) {
                        MessageUtil.send(
                            seller,
                            config.getString("messages.listing-cancel-failed", "<red>キャンセル失敗</red>"),
                            prefix = prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                // CAS update from ACTIVE or EXPIRED to CANCELLED
                val currentStatus = item.status
                if (currentStatus != MarketItemStatus.ACTIVE && currentStatus != MarketItemStatus.EXPIRED) {
                    scheduler.runAtEntity(seller) {
                        MessageUtil.send(
                            seller,
                            config.getString("messages.listing-cancel-failed", "<red>キャンセル失敗</red>"),
                            prefix = prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                val casSuccess = dao.casUpdateStatus(itemId, currentStatus, MarketItemStatus.CANCELLED)
                if (!casSuccess) {
                    scheduler.runAtEntity(seller) {
                        MessageUtil.send(
                            seller,
                            config.getString("messages.listing-cancel-failed", "<red>キャンセル失敗</red>"),
                            prefix = prefix,
                        )
                    }
                    future.complete(false)
                    return@runAsync
                }

                val itemStack = ItemSerializer.fromByteArray(item.itemBytes)
                val displayName = getItemDisplayName(itemStack)

                scheduler.runAtEntity(seller) {
                    returnItemToPlayer(seller, itemStack)
                    MessageUtil.send(
                        seller,
                        config.getString("messages.listing-cancel-success", "<yellow>回収しました</yellow>"),
                        mapOf("item" to displayName),
                        prefix,
                    )
                }

                future.complete(true)
            } catch (e: Exception) {
                logger.severe("Error cancelling listing $itemId for ${seller.name}: ${e.message}")
                future.completeExceptionally(e)
            }
        }

        return future
    }

    /**
     * Safely returns an item to the player's inventory or drops it at their location if full.
     * Returns true if any items were dropped on the ground.
     */
    fun returnItemToPlayer(
        player: Player,
        item: ItemStack,
    ): Boolean {
        val leftover = player.inventory.addItem(item)
        if (leftover.isNotEmpty()) {
            for (left in leftover.values) {
                player.world.dropItem(player.location, left)
            }
            return true
        }
        return false
    }

    private fun getItemDisplayName(item: ItemStack): String {
        val meta = item.itemMeta
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName()!!)
        }
        return item.type.name
            .lowercase()
            .replace('_', ' ')
    }

    private fun getPrefix(): String = config.getString("messages.prefix", "") ?: ""
}
