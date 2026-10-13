package com.ququla89.foliamarket.gui

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import com.ququla89.foliamarket.util.ItemSerializer
import com.ququla89.foliamarket.util.MessageUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Types of GUI menus.
 */
enum class GuiType {
    MARKET,
    MY_LISTINGS,
    CONFIRM_PURCHASE,
}

/**
 * Custom inventory holder to track GUI state.
 */
class MarketHolder(
    val type: GuiType,
    var page: Int = 0,
    var targetItemId: Long? = null,
) : InventoryHolder {
    private var inv: Inventory? = null

    fun setInventory(inventory: Inventory) {
        this.inv = inventory
    }

    override fun getInventory(): Inventory = inv ?: Bukkit.createInventory(this, 54, Component.text("Market"))
}

/**
 * Reactive inventory GUI manager providing differential slot updates.
 */
class MarketGui(
    private val plugin: Plugin,
    private val dao: MarketDao,
    private val economyService: EconomyService,
    private val scheduler: SchedulerAdapter,
    private var config: FileConfiguration,
) {
    val itemIdKey = NamespacedKey(plugin, "market_item_id")
    val actionKey = NamespacedKey(plugin, "gui_action")

    // Tracks open slot mapping per player for differential updates: slot -> MarketItem.id
    private val playerSlotState = ConcurrentHashMap<Player, MutableMap<Int, Long>>()

    companion object {
        const val PAGE_SIZE = 45
        const val PREV_SLOT = 45
        const val MY_LISTINGS_SLOT = 49
        const val NEXT_SLOT = 53

        // Actions
        const val ACTION_PREV = "PREV"
        const val ACTION_NEXT = "NEXT"
        const val ACTION_MY_LISTINGS = "MY_LISTINGS"
        const val ACTION_BACK_TO_MARKET = "BACK_TO_MARKET"
        const val ACTION_BUY_CONFIRM = "BUY_CONFIRM"
        const val ACTION_BUY_CANCEL = "BUY_CANCEL"
        const val ACTION_ITEM_CLICK = "ITEM_CLICK"
    }

    fun reloadConfig(newConfig: FileConfiguration) {
        this.config = newConfig
    }

    fun clearPlayerState(player: Player) {
        playerSlotState.remove(player)
    }

    /**
     * Opens or differentially updates the market marketplace page for a player.
     */
    fun openMarket(
        player: Player,
        page: Int = 0,
    ) {
        scheduler.runAsync {
            val totalActive = dao.countActiveItems()
            val totalPages = ((totalActive - 1) / PAGE_SIZE).coerceAtLeast(0)
            val validPage = page.coerceIn(0, totalPages)
            val items = dao.getActiveItems(validPage * PAGE_SIZE, PAGE_SIZE)

            scheduler.runAtEntity(player) {
                val currentTop = player.openInventory.topInventory
                val holder = currentTop.holder
                val inventory: Inventory

                val isReusing = holder is MarketHolder && holder.type == GuiType.MARKET
                if (isReusing) {
                    inventory = currentTop
                    holder.page = validPage
                } else {
                    val newHolder = MarketHolder(GuiType.MARKET, validPage)
                    val titleStr = config.getString("gui.title", "<gradient:#2b5876:#4e4376>マーケットプレイス</gradient>")
                    val title = MessageUtil.parse(titleStr)
                    inventory = Bukkit.createInventory(newHolder, 54, title)
                    newHolder.setInventory(inventory)
                    playerSlotState[player] = ConcurrentHashMap()
                }

                applyDifferentialMarketSlots(player, inventory, items, validPage, validPage < totalPages)

                if (!isReusing) {
                    player.openInventory(inventory)
                }
            }
        }
    }

    /**
     * Opens the player's own listings view.
     */
    fun openMyListings(
        player: Player,
        page: Int = 0,
    ) {
        scheduler.runAsync {
            val items = dao.getItemsBySeller(player.uniqueId)
            val totalItems = items.size
            val totalPages = ((totalItems - 1) / PAGE_SIZE).coerceAtLeast(0)
            val validPage = page.coerceIn(0, totalPages)
            val startIndex = validPage * PAGE_SIZE
            val pagedItems = items.drop(startIndex).take(PAGE_SIZE)

            scheduler.runAtEntity(player) {
                val titleStr = config.getString("gui.my-listings-title", "<gold>あなたの出品一覧</gold>")
                val newHolder = MarketHolder(GuiType.MY_LISTINGS, validPage)
                val inventory = Bukkit.createInventory(newHolder, 54, MessageUtil.parse(titleStr))
                newHolder.setInventory(inventory)

                // Fill items
                for (i in 0 until PAGE_SIZE) {
                    if (i < pagedItems.size) {
                        inventory.setItem(i, createItemDisplay(pagedItems[i], isOwnListing = true))
                    } else {
                        inventory.setItem(i, null)
                    }
                }

                // Fill navigation bar
                fillNavigationRow(inventory, validPage, validPage < totalPages, isMyListings = true)
                player.openInventory(inventory)
            }
        }
    }

    /**
     * Opens the purchase confirmation dialog.
     */
    fun openConfirmDialog(
        player: Player,
        itemId: Long,
    ) {
        scheduler.runAsync {
            val item = dao.getItemById(itemId)
            if (item == null) {
                scheduler.runAtEntity(player) {
                    MessageUtil.send(player, "<red>商品が見つかりませんでした。</red>")
                }
                return@runAsync
            }

            scheduler.runAtEntity(player) {
                val titleStr = config.getString("gui.confirm-title", "<gold>購入確認</gold>")
                val newHolder = MarketHolder(GuiType.CONFIRM_PURCHASE, 0, itemId)
                val inventory = Bukkit.createInventory(newHolder, 27, MessageUtil.parse(titleStr))
                newHolder.setInventory(inventory)

                val filler = createFillerPane()
                for (i in 0 until 27) {
                    inventory.setItem(i, filler)
                }

                // Confirm button (Slot 11)
                val confirmItem =
                    ItemStack(Material.LIME_CONCRETE).apply {
                        editMeta { meta ->
                            meta.displayName(
                                MessageUtil
                                    .parse(
                                        config.getString("messages.gui-confirm-buy-button", "<green><b>✔ 購入を確定する</b></green>"),
                                    ).decoration(TextDecoration.ITALIC, false),
                            )
                            meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_BUY_CONFIRM)
                            meta.persistentDataContainer.set(itemIdKey, PersistentDataType.LONG, itemId)
                        }
                    }
                inventory.setItem(11, confirmItem)

                // Preview item (Slot 13)
                val preview = createItemDisplay(item, isOwnListing = false)
                inventory.setItem(13, preview)

                // Cancel button (Slot 15)
                val cancelItem =
                    ItemStack(Material.RED_CONCRETE).apply {
                        editMeta { meta ->
                            meta.displayName(
                                MessageUtil
                                    .parse(
                                        config.getString("messages.gui-cancel-buy-button", "<red><b>✖ キャンセル</b></red>"),
                                    ).decoration(TextDecoration.ITALIC, false),
                            )
                            meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_BUY_CANCEL)
                        }
                    }
                inventory.setItem(15, cancelItem)

                player.openInventory(inventory)
            }
        }
    }

    /**
     * Differential slot updating: Only writes slots where the item has changed.
     */
    private fun applyDifferentialMarketSlots(
        player: Player,
        inventory: Inventory,
        items: List<MarketItem>,
        page: Int,
        hasNextPage: Boolean,
    ) {
        val slotMap = playerSlotState.getOrPut(player) { ConcurrentHashMap() }

        for (slot in 0 until PAGE_SIZE) {
            val previousId = slotMap[slot]
            if (slot < items.size) {
                val currentItem = items[slot]
                if (previousId != currentItem.id) {
                    inventory.setItem(slot, createItemDisplay(currentItem, isOwnListing = false))
                    slotMap[slot] = currentItem.id
                }
            } else {
                if (previousId != null || inventory.getItem(slot) != null) {
                    inventory.setItem(slot, null)
                    slotMap.remove(slot)
                }
            }
        }

        fillNavigationRow(inventory, page, hasNextPage, isMyListings = false)
    }

    private fun fillNavigationRow(
        inventory: Inventory,
        page: Int,
        hasNextPage: Boolean,
        isMyListings: Boolean,
    ) {
        val filler = createFillerPane()
        val navSlots = intArrayOf(45, 46, 47, 48, 49, 50, 51, 52, 53)
        for (slot in navSlots) {
            inventory.setItem(slot, filler)
        }

        // Previous button
        if (page > 0) {
            val prevItem =
                ItemStack(Material.ARROW).apply {
                    editMeta { meta ->
                        meta.displayName(
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-prev-page", "<aqua>◀ 前のページ</aqua>"),
                                    mapOf("page" to page.toString()),
                                ).decoration(TextDecoration.ITALIC, false),
                        )
                        meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_PREV)
                    }
                }
            inventory.setItem(PREV_SLOT, prevItem)
        }

        // Center button: My Listings or Back to Market
        if (isMyListings) {
            val backItem =
                ItemStack(Material.OAK_DOOR).apply {
                    editMeta { meta ->
                        meta.displayName(
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-back-to-market", "<yellow>◀ マーケットへ戻る</yellow>"),
                                ).decoration(TextDecoration.ITALIC, false),
                        )
                        meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_BACK_TO_MARKET)
                    }
                }
            inventory.setItem(MY_LISTINGS_SLOT, backItem)
        } else {
            val myListingsItem =
                ItemStack(Material.CHEST).apply {
                    editMeta { meta ->
                        meta.displayName(
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-my-listings-button", "<gold>★ 自分の出品を確認する</gold>"),
                                ).decoration(TextDecoration.ITALIC, false),
                        )
                        meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_MY_LISTINGS)
                    }
                }
            inventory.setItem(MY_LISTINGS_SLOT, myListingsItem)
        }

        // Next button
        if (hasNextPage) {
            val nextItem =
                ItemStack(Material.ARROW).apply {
                    editMeta { meta ->
                        meta.displayName(
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-next-page", "<aqua>次のページ ▶</aqua>"),
                                    mapOf("page" to (page + 2).toString()),
                                ).decoration(TextDecoration.ITALIC, false),
                        )
                        meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_NEXT)
                    }
                }
            inventory.setItem(NEXT_SLOT, nextItem)
        }
    }

    private fun createItemDisplay(
        item: MarketItem,
        isOwnListing: Boolean,
    ): ItemStack {
        val original = ItemSerializer.fromByteArray(item.itemBytes)
        val display = original.clone()

        val timeLeftMs = (item.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val hours = timeLeftMs / (3600 * 1000L)
        val minutes = (timeLeftMs % (3600 * 1000L)) / (60 * 1000L)
        val timeLeftFormatted = "${hours}時間 ${minutes}分"

        display.editMeta { meta ->
            val lore = meta.lore() ?: ArrayList()
            val newLore = ArrayList<Component>(lore)
            newLore.add(Component.empty())

            val sellerLine =
                MessageUtil
                    .parse(
                        config.getString("messages.gui-item-seller", "<gray>出品者: <white>{seller}</white></gray>"),
                        mapOf("seller" to item.sellerName),
                    ).decoration(TextDecoration.ITALIC, false)

            val priceLine =
                MessageUtil
                    .parse(
                        config.getString("messages.gui-item-price", "<gray>価格: <gold>{price} {currency}</gold></gray>"),
                        mapOf(
                            "price" to "%,.2f".format(item.price),
                            "currency" to economyService.getCurrencyName(),
                        ),
                    ).decoration(TextDecoration.ITALIC, false)

            val expiresLine =
                MessageUtil
                    .parse(
                        config.getString("messages.gui-item-expires", "<gray>有効期限: <aqua>{time_left}</aqua></gray>"),
                        mapOf("time_left" to timeLeftFormatted),
                    ).decoration(TextDecoration.ITALIC, false)

            newLore.add(sellerLine)
            newLore.add(priceLine)
            newLore.add(expiresLine)
            newLore.add(Component.empty())

            if (isOwnListing) {
                val cancelLine =
                    MessageUtil
                        .parse(
                            config.getString("messages.gui-item-click-to-cancel", "<red>▶ クリックして出品を取り下げる</red>"),
                        ).decoration(TextDecoration.ITALIC, false)
                newLore.add(cancelLine)
            } else {
                val buyLine =
                    MessageUtil
                        .parse(
                            config.getString("messages.gui-item-click-to-buy", "<yellow>▶ クリックして購入する</yellow>"),
                        ).decoration(TextDecoration.ITALIC, false)
                newLore.add(buyLine)
            }

            meta.lore(newLore)
            meta.persistentDataContainer.set(itemIdKey, PersistentDataType.LONG, item.id)
            meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_ITEM_CLICK)
        }

        return display
    }

    private fun createFillerPane(): ItemStack =
        ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            editMeta { meta ->
                meta.displayName(Component.text(" ").color(NamedTextColor.DARK_GRAY))
            }
        }
}
