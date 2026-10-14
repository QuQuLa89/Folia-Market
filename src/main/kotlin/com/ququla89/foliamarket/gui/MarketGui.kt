package com.ququla89.foliamarket.gui

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.database.model.MarketSortMode
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
    var sortMode: MarketSortMode = MarketSortMode.NEWEST,
    var searchKeyword: String? = null,
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
    val statusKey = NamespacedKey(plugin, "market_item_status")

    // Tracks open slot mapping per player for differential updates: slot -> MarketItem.id
    private val playerSlotState = ConcurrentHashMap<Player, MutableMap<Int, Long>>()

    companion object {
        const val PAGE_SIZE = 45
        const val PREV_SLOT = 45
        const val BALANCE_SLOT = 47
        const val SEARCH_SLOT = 48
        const val MY_LISTINGS_SLOT = 49
        const val SORT_SLOT = 51
        const val NEXT_SLOT = 53

        // Actions
        const val ACTION_PREV = "PREV"
        const val ACTION_NEXT = "NEXT"
        const val ACTION_MY_LISTINGS = "MY_LISTINGS"
        const val ACTION_BACK_TO_MARKET = "BACK_TO_MARKET"
        const val ACTION_BUY_CONFIRM = "BUY_CONFIRM"
        const val ACTION_BUY_CANCEL = "BUY_CANCEL"
        const val ACTION_ITEM_CLICK = "ITEM_CLICK"
        const val ACTION_BALANCE = "BALANCE"
        const val ACTION_SEARCH = "SEARCH"
        const val ACTION_SEARCH_CLEAR = "SEARCH_CLEAR"
        const val ACTION_SORT = "SORT"
    }

    fun reloadConfig(newConfig: FileConfiguration) {
        this.config = newConfig
    }

    fun getMessage(
        key: String,
        default: String,
    ): String = config.getString(key, default) ?: default

    fun clearPlayerState(player: Player) {
        playerSlotState.remove(player)
    }

    /**
     * Opens or differentially updates the market marketplace page for a player.
     */
    fun openMarket(
        player: Player,
        page: Int = 0,
        sortMode: MarketSortMode = MarketSortMode.NEWEST,
        searchKeyword: String? = null,
    ) {
        scheduler.runAsync {
            val totalActive = dao.countActiveItems(searchKeyword)
            val totalPages = ((totalActive - 1) / PAGE_SIZE).coerceAtLeast(0)
            val validPage = page.coerceIn(0, totalPages)
            val items = dao.getActiveItems(validPage * PAGE_SIZE, PAGE_SIZE, sortMode, searchKeyword)

            scheduler.runAtEntity(player) {
                val currentTop = player.openInventory.topInventory
                val holder = currentTop.holder
                val inventory: Inventory

                val isReusing =
                    holder is MarketHolder && holder.type == GuiType.MARKET && holder.searchKeyword == searchKeyword
                if (isReusing) {
                    inventory = currentTop
                    holder.page = validPage
                    holder.sortMode = sortMode
                } else {
                    val newHolder =
                        MarketHolder(
                            type = GuiType.MARKET,
                            page = validPage,
                            sortMode = sortMode,
                            searchKeyword = searchKeyword,
                        )
                    val titleStr =
                        if (!searchKeyword.isNullOrBlank()) {
                            config.getString(
                                "gui.search-title",
                                "<gradient:#2b5876:#4e4376><bold>検索: </bold><yellow>{keyword}</yellow></gradient>",
                            )
                        } else {
                            config.getString("gui.title", "<gradient:#2b5876:#4e4376><bold>マーケットプレイス</bold></gradient>")
                        }
                    val title = MessageUtil.parse(titleStr, mapOf("keyword" to (searchKeyword ?: "")))
                    inventory = Bukkit.createInventory(newHolder, 54, title)
                    newHolder.setInventory(inventory)
                    playerSlotState[player] = ConcurrentHashMap()
                }

                applyDifferentialMarketSlots(
                    player = player,
                    inventory = inventory,
                    items = items,
                    page = validPage,
                    hasNextPage = validPage < totalPages,
                    sortMode = sortMode,
                    searchKeyword = searchKeyword,
                )

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
                fillNavigationRow(player, inventory, validPage, validPage < totalPages, isMyListings = true)
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
        sortMode: MarketSortMode,
        searchKeyword: String?,
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

        fillNavigationRow(
            player = player,
            inventory = inventory,
            page = page,
            hasNextPage = hasNextPage,
            isMyListings = false,
            sortMode = sortMode,
            searchKeyword = searchKeyword,
        )
    }

    private fun fillNavigationRow(
        player: Player,
        inventory: Inventory,
        page: Int,
        hasNextPage: Boolean,
        isMyListings: Boolean,
        sortMode: MarketSortMode = MarketSortMode.NEWEST,
        searchKeyword: String? = null,
    ) {
        val filler = createFillerPane()
        val navSlots = intArrayOf(45, 46, 47, 48, 49, 50, 51, 52, 53)
        for (slot in navSlots) {
            inventory.setItem(slot, filler)
        }

        // Previous button (Slot 45)
        if (page > 0) {
            val prevItem =
                ItemStack(Material.ARROW).apply {
                    editMeta { meta ->
                        meta.displayName(
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-prev-page", "<aqua>◀ 前のページ ({page})</aqua>"),
                                    mapOf("page" to page.toString()),
                                ).decoration(TextDecoration.ITALIC, false),
                        )
                        meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_PREV)
                    }
                }
            inventory.setItem(PREV_SLOT, prevItem)
        }

        // Player balance display (Slot 47)
        inventory.setItem(BALANCE_SLOT, createBalanceDisplay(player))

        if (!isMyListings) {
            // Search / Clear Search button (Slot 48)
            inventory.setItem(SEARCH_SLOT, createSearchDisplay(searchKeyword))
        }

        // Center button: My Listings or Back to Market (Slot 49)
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

            // Sort button (Slot 51)
            inventory.setItem(SORT_SLOT, createSortDisplay(sortMode))
        }

        // Next button (Slot 53)
        if (hasNextPage) {
            val nextItem =
                ItemStack(Material.ARROW).apply {
                    editMeta { meta ->
                        meta.displayName(
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-next-page", "<aqua>次のページ ({page}) ▶</aqua>"),
                                    mapOf("page" to (page + 2).toString()),
                                ).decoration(TextDecoration.ITALIC, false),
                        )
                        meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_NEXT)
                    }
                }
            inventory.setItem(NEXT_SLOT, nextItem)
        }
    }

    private fun createSearchDisplay(searchKeyword: String?): ItemStack =
        if (searchKeyword.isNullOrBlank()) {
            ItemStack(Material.SPYGLASS).apply {
                editMeta { meta ->
                    meta.displayName(
                        MessageUtil
                            .parse(
                                config.getString("messages.gui-search-button", "<aqua><b>🔍 商品を検索</b></aqua>"),
                            ).decoration(TextDecoration.ITALIC, false),
                    )
                    meta.lore(
                        listOf(
                            Component.empty(),
                            MessageUtil
                                .parse(
                                    config.getString("messages.gui-search-button-lore", "<gray>クリックしてチャットに検索コマンドを入力</gray>"),
                                ).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                        ),
                    )
                    meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_SEARCH)
                }
            }
        } else {
            ItemStack(Material.BARRIER).apply {
                editMeta { meta ->
                    meta.displayName(
                        MessageUtil
                            .parse(
                                config.getString("messages.gui-search-clear-button", "<red><b>❌ 検索を解除</b></red>"),
                            ).decoration(TextDecoration.ITALIC, false),
                    )
                    meta.lore(
                        listOf(
                            Component.empty(),
                            MessageUtil
                                .parse(
                                    config.getString(
                                        "messages.gui-search-current",
                                        "<gray>現在の検索条件: <yellow>{keyword}</yellow></gray>",
                                    ),
                                    mapOf("keyword" to searchKeyword),
                                ).decoration(TextDecoration.ITALIC, false),
                            MessageUtil
                                .parse(
                                    config.getString(
                                        "messages.gui-search-clear-info",
                                        "<yellow>▶ クリックして全件表示に戻る</yellow>",
                                    ),
                                ).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                        ),
                    )
                    meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_SEARCH_CLEAR)
                }
            }
        }

    private fun createSortDisplay(sortMode: MarketSortMode): ItemStack =
        ItemStack(Material.HOPPER).apply {
            editMeta { meta ->
                meta.displayName(
                    MessageUtil
                        .parse(
                            config.getString("messages.gui-sort-title", "<gold><b>🧭 並び替え</b></gold>"),
                        ).decoration(TextDecoration.ITALIC, false),
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                for (mode in MarketSortMode.entries) {
                    if (mode == sortMode) {
                        lore.add(
                            MessageUtil
                                .parse("<green><b>✔ ${mode.displayName}</b></green>")
                                .decoration(TextDecoration.ITALIC, false),
                        )
                    } else {
                        lore.add(
                            MessageUtil
                                .parse("<dark_gray>  ${mode.displayName}</dark_gray>")
                                .decoration(TextDecoration.ITALIC, false),
                        )
                    }
                }
                lore.add(Component.empty())
                lore.add(
                    MessageUtil
                        .parse(
                            config.getString("messages.gui-sort-click-info", "<yellow>▶ クリックしてソート順を切り替える</yellow>"),
                        ).decoration(TextDecoration.ITALIC, false),
                )
                lore.add(Component.empty())
                meta.lore(lore)
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_SORT)
            }
        }

    private fun createBalanceDisplay(player: Player): ItemStack {
        val balance = economyService.getBalance(player)
        val formattedBalance = "%,.2f".format(balance)
        val currency = economyService.getCurrencyName()

        return ItemStack(Material.GOLD_INGOT).apply {
            editMeta { meta ->
                val title =
                    MessageUtil
                        .parse(
                            config.getString("messages.gui-balance-title", "<gold><b>💰 あなたの所持金</b></gold>"),
                        ).decoration(TextDecoration.ITALIC, false)
                meta.displayName(title)

                val loreLine =
                    MessageUtil
                        .parse(
                            config.getString(
                                "messages.gui-balance-lore",
                                "<yellow>残高: <white><b>{balance} {currency}</b></white></yellow>",
                            ),
                            mapOf("balance" to formattedBalance, "currency" to currency),
                        ).decoration(TextDecoration.ITALIC, false)

                meta.lore(listOf(Component.empty(), loreLine, Component.empty()))
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, ACTION_BALANCE)
            }
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
            val newLore = ArrayList<Component>()

            if (isOwnListing) {
                val badge =
                    when (item.status) {
                        MarketItemStatus.ACTIVE -> MessageUtil.parse("<green><b>● [出品中]</b></green>")
                        MarketItemStatus.SOLD -> MessageUtil.parse("<gold><b>✔ [購入済 / 売約済み]</b></gold>")
                        MarketItemStatus.CANCELLED -> MessageUtil.parse("<gray><b>✖ [取り下げ済み]</b></gray>")
                        MarketItemStatus.EXPIRED -> MessageUtil.parse("<red><b>⏰ [期限切れ]</b></red>")
                    }.decoration(TextDecoration.ITALIC, false)
                newLore.add(badge)
            }

            if (lore.isNotEmpty()) {
                if (isOwnListing) {
                    newLore.add(Component.empty())
                }
                newLore.addAll(lore)
            }
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
                val footerLine =
                    when (item.status) {
                        MarketItemStatus.ACTIVE ->
                            MessageUtil.parse(
                                config.getString(
                                    "messages.gui-item-click-to-cancel",
                                    "<red>▶ クリックして出品を取り下げる</red>",
                                ),
                            )
                        MarketItemStatus.SOLD ->
                            MessageUtil.parse(
                                config.getString(
                                    "messages.gui-item-sold-info",
                                    "<dark_gray>他のプレイヤーに購入されました</dark_gray>",
                                ),
                            )
                        MarketItemStatus.CANCELLED ->
                            MessageUtil.parse(
                                config.getString(
                                    "messages.gui-item-cancelled-info",
                                    "<dark_gray>出品を取り下げました（回収済み）</dark_gray>",
                                ),
                            )
                        MarketItemStatus.EXPIRED ->
                            MessageUtil.parse(
                                config.getString(
                                    "messages.gui-item-click-to-reclaim",
                                    "<yellow>▶ クリックして回収する</yellow>",
                                ),
                            )
                    }.decoration(TextDecoration.ITALIC, false)
                newLore.add(footerLine)
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
            meta.persistentDataContainer.set(statusKey, PersistentDataType.STRING, item.status.name)
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
