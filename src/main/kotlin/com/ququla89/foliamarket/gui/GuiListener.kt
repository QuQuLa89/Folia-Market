package com.ququla89.foliamarket.gui

import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.engine.MarketService
import com.ququla89.foliamarket.util.MessageUtil
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Event listener protecting marketplace GUIs against illicit clicks and routing user actions.
 */
class GuiListener(
    private val marketGui: MarketGui,
    private val marketService: MarketService,
) : Listener {
    private val clickDebounce = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? MarketHolder ?: return
        val player = event.whoClicked as? Player ?: return

        // Always cancel clicks inside custom market inventory
        if (event.rawSlot < event.inventory.size) {
            event.isCancelled = true
        }

        // Also prevent shift-clicking into market inventory from bottom inventory
        if (event.isShiftClick) {
            event.isCancelled = true
        }

        if (event.rawSlot < 0 || event.rawSlot >= event.inventory.size) {
            return
        }

        val clickedItem = event.currentItem ?: return
        val meta = clickedItem.itemMeta ?: return

        // Anti-spam debounce (300ms)
        val now = System.currentTimeMillis()
        val lastClick = clickDebounce[player.uniqueId] ?: 0L
        if (now - lastClick < 300L) {
            return
        }
        clickDebounce[player.uniqueId] = now

        val action = meta.persistentDataContainer.get(marketGui.actionKey, PersistentDataType.STRING) ?: return
        val itemId = meta.persistentDataContainer.get(marketGui.itemIdKey, PersistentDataType.LONG)

        when (action) {
            MarketGui.ACTION_PREV -> {
                if (holder.type == GuiType.MY_LISTINGS) {
                    marketGui.openMyListings(player, holder.page - 1)
                } else {
                    marketGui.openMarket(player, holder.page - 1, holder.sortMode, holder.searchKeyword)
                }
            }

            MarketGui.ACTION_NEXT -> {
                if (holder.type == GuiType.MY_LISTINGS) {
                    marketGui.openMyListings(player, holder.page + 1)
                } else {
                    marketGui.openMarket(player, holder.page + 1, holder.sortMode, holder.searchKeyword)
                }
            }

            MarketGui.ACTION_MY_LISTINGS -> {
                marketGui.openMyListings(player, 0)
            }

            MarketGui.ACTION_BACK_TO_MARKET -> {
                marketGui.openMarket(player, 0, holder.sortMode, holder.searchKeyword)
            }

            MarketGui.ACTION_BALANCE -> {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }

            MarketGui.ACTION_SORT -> {
                val nextMode = holder.sortMode.next()
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
                marketGui.openMarket(player, 0, nextMode, holder.searchKeyword)
            }

            MarketGui.ACTION_SEARCH -> {
                player.closeInventory()
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                val searchGuide =
                    marketGui.getMessage(
                        "messages.search-guide",
                        "<yellow>検索したいアイテム名または出品者名を指定してください: </yellow><click:suggest_command:'/market search '><aqua><u>[/market search <キーワード>]</u></aqua></click>",
                    )
                val prefix = marketGui.getMessage("messages.prefix", "")
                MessageUtil.send(player, searchGuide, prefix = prefix)
            }

            MarketGui.ACTION_SEARCH_CLEAR -> {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 0.8f)
                marketGui.openMarket(player, 0, holder.sortMode, searchKeyword = null)
            }

            MarketGui.ACTION_ITEM_CLICK -> {
                if (itemId != null) {
                    if (holder.type == GuiType.MY_LISTINGS) {
                        val statusStr = meta.persistentDataContainer.get(marketGui.statusKey, PersistentDataType.STRING)
                        val status = statusStr?.let { runCatching { MarketItemStatus.valueOf(it) }.getOrNull() }

                        if (status == MarketItemStatus.SOLD || status == MarketItemStatus.CANCELLED) {
                            val msg =
                                marketGui.getMessage(
                                    "messages.listing-already-closed",
                                    "<yellow>この商品は既に取り下げ済みまたは売却済みです。</yellow>",
                                )
                            MessageUtil.send(player, msg)
                            return
                        }

                        // Cancel own listing
                        marketService.cancelListing(player, itemId).thenAccept { success ->
                            if (success) {
                                marketGui.openMyListings(player, holder.page)
                            }
                        }
                    } else if (holder.type == GuiType.MARKET) {
                        // Open confirmation dialog
                        marketGui.openConfirmDialog(player, itemId)
                    }
                }
            }

            MarketGui.ACTION_BUY_CONFIRM -> {
                val targetId = holder.targetItemId ?: itemId
                if (targetId != null) {
                    player.closeInventory()
                    marketService.purchaseItem(player, targetId)
                }
            }

            MarketGui.ACTION_BUY_CANCEL -> {
                marketGui.openMarket(player, 0, holder.sortMode, holder.searchKeyword)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder as? MarketHolder ?: return
        for (slot in event.rawSlots) {
            if (slot < event.inventory.size) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? MarketHolder ?: return
        val player = event.player as? Player ?: return
        marketGui.clearPlayerState(player)
        clickDebounce.remove(player.uniqueId)
    }
}
