package com.ququla89.foliamarket.engine

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import com.ququla89.foliamarket.util.ItemSerializer
import com.ququla89.foliamarket.util.ItemTranslationManager
import com.ququla89.foliamarket.util.MessageUtil
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Base64
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

        if (!economyService.isAvailable()) {
            MessageUtil.send(player, "<red>経済システムが利用できないため、出品できません。</red>", prefix = prefix)
            future.complete(false)
            return future
        }

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

        // Serialize before removing the item so failures cannot destroy the only recoverable copy.
        val itemToSell = itemInHand.clone()
        val now = System.currentTimeMillis()
        val displayName = getItemDisplayName(itemToSell)
        val marketItem =
            MarketItem(
                sellerUuid = player.uniqueId,
                sellerName = player.name,
                itemName = ItemTranslationManager.buildSearchIndex(itemToSell),
                itemBytes = ItemSerializer.toByteArray(itemToSell),
                price = price,
                listedAt = now,
                expiresAt = now + durationHours * 3600 * 1000L,
                status = MarketItemStatus.ACTIVE,
            )
        player.inventory.setItemInMainHand(ItemStack(Material.AIR))

        scheduler.runAsync {
            try {
                val id = dao.insertItemIfBelowLimit(marketItem, maxListingsPerPlayer)
                if (id == null) {
                    returnFailedListing(player, itemToSell, marketItem, future) {
                        MessageUtil.send(
                            player,
                            config.getString("messages.max-listings-reached", "<red>出品上限です</red>"),
                            mapOf("max" to maxListingsPerPlayer.toString()),
                            prefix,
                        )
                    }
                    return@runAsync
                }
                if (id <= 0) {
                    returnFailedListing(player, itemToSell, marketItem, future) {
                        MessageUtil.send(player, "<red>データベース保存に失敗しました。</red>", prefix = prefix)
                    }
                    return@runAsync
                }

                future.complete(true)
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
            } catch (e: Exception) {
                logger.severe("Failed to list item for player ${player.name}: ${e.message}")
                if (!future.isDone) {
                    returnFailedListing(player, itemToSell, marketItem, future) {
                        MessageUtil.send(player, "<red>予期せぬエラーが発生しました。</red>", prefix = prefix)
                    }
                }
            }
        }

        return future
    }

    private fun returnFailedListing(
        player: Player,
        item: ItemStack,
        listing: MarketItem,
        future: CompletableFuture<Boolean>,
        notify: () -> Unit,
    ) {
        val recover = {
            scheduler.runAsync {
                try {
                    // EXPIRED listings remain reclaimable in My Listings and do not consume the listing limit.
                    val id = dao.insertItem(listing.copy(status = MarketItemStatus.EXPIRED))
                    check(id > 0) { "Recovery listing was not saved" }
                    logger.warning("Stored undelivered listing as reclaimable item $id for ${listing.sellerUuid}")
                    future.complete(false)
                } catch (error: Exception) {
                    logger.severe(
                        "Item recovery required for ${listing.sellerUuid}: ${error.message}; " +
                            "itemBytesBase64=${Base64.getEncoder().encodeToString(listing.itemBytes)}",
                    )
                    future.completeExceptionally(error)
                }
            }
        }
        scheduler.runAtEntity(player, retired = recover) {
            if (!player.isOnline) {
                recover()
                return@runAtEntity
            }
            returnItemToPlayer(player, item)
            future.complete(false)
            notify()
        }
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

        if (!economyService.isAvailable()) {
            MessageUtil.send(buyer, "<red>経済システムが利用できないため、購入できません。</red>", prefix = prefix)
            future.complete(false)
            return future
        }

        scheduler.runAsync {
            var reservedItem: MarketItem? = null
            var charged = false
            var delivered = false
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
                reservedItem = item

                // Financial transaction
                val withdrawSuccess = economyService.withdraw(buyer, item.price)
                if (!withdrawSuccess) {
                    // Rollback CAS if withdrawal failed
                    rollbackPurchase(buyer, itemId, item, false, future)
                    scheduler.runAtEntity(buyer) {
                        MessageUtil.send(buyer, "<red>残高の引き落としに失敗しました。</red>", prefix = prefix)
                    }
                    return@runAsync
                }
                charged = true

                val taxAmount = item.price * (taxPercent / 100.0)
                val netPrice = item.price - taxAmount

                val itemStack = ItemSerializer.fromByteArray(item.itemBytes)
                val displayName = getItemDisplayName(itemStack)

                // Deliver item and receipt on buyer's entity thread
                scheduler.runAtEntity(buyer, retired = { rollbackPurchase(buyer, itemId, item, true, future) }) {
                    if (!buyer.isOnline) {
                        rollbackPurchase(buyer, itemId, item, true, future)
                        return@runAtEntity
                    }
                    val dropped = returnItemToPlayer(buyer, itemStack)
                    delivered = true
                    // Pay the seller only after delivery; retired buyers require only a buyer refund.
                    scheduler.runAsync {
                        try {
                            val seller = Bukkit.getOfflinePlayer(item.sellerUuid)
                            check(economyService.deposit(seller, netPrice)) { "Seller payment failed" }
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
                        } catch (error: Exception) {
                            logger.severe(
                                "Seller payout requires recovery: item=$itemId seller=${item.sellerUuid} amount=$netPrice: ${error.message}",
                            )
                        }
                    }
                    future.complete(true)
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
            } catch (e: Exception) {
                logger.severe("Error during purchase of item $itemId by ${buyer.name}: ${e.message}")
                val item = reservedItem
                if (item != null && !delivered && !future.isDone) {
                    rollbackPurchase(buyer, itemId, item, charged, future)
                } else if (!future.isDone) {
                    future.completeExceptionally(e)
                }
            }
        }

        return future
    }

    private fun rollbackPurchase(
        buyer: Player,
        itemId: Long,
        item: MarketItem,
        charged: Boolean,
        future: CompletableFuture<Boolean>,
    ) {
        // Retired callbacks must not perform blocking database or economy work on the entity thread.
        scheduler.runAsync {
            try {
                if (charged) check(economyService.deposit(buyer, item.price)) { "Buyer refund failed" }
                check(dao.casUpdateStatus(itemId, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)) { "Sale rollback failed" }
                future.complete(false)
            } catch (error: Exception) {
                logger.severe("Purchase recovery required: item=$itemId buyer=${buyer.uniqueId} price=${item.price}: ${error.message}")
                future.completeExceptionally(error)
            }
        }
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

                val itemStack = ItemSerializer.fromByteArray(item.itemBytes)
                val displayName = getItemDisplayName(itemStack)
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

                val restore = {
                    scheduler.runAsync {
                        try {
                            check(dao.casUpdateStatus(itemId, MarketItemStatus.CANCELLED, currentStatus)) { "Cancellation rollback failed" }
                            future.complete(false)
                        } catch (error: Exception) {
                            logger.severe("Cancellation recovery required: item=$itemId seller=${seller.uniqueId}: ${error.message}")
                            future.completeExceptionally(error)
                        }
                    }
                }
                scheduler.runAtEntity(seller, retired = restore) {
                    if (!seller.isOnline) {
                        restore()
                        return@runAtEntity
                    }
                    returnItemToPlayer(seller, itemStack)
                    future.complete(true)
                    MessageUtil.send(
                        seller,
                        config.getString("messages.listing-cancel-success", "<yellow>回収しました</yellow>"),
                        mapOf("item" to displayName),
                        prefix,
                    )
                }
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

    private fun getItemDisplayName(item: ItemStack): String = ItemTranslationManager.getDisplayName(item)

    private fun getPrefix(): String = config.getString("messages.prefix", "") ?: ""
}
