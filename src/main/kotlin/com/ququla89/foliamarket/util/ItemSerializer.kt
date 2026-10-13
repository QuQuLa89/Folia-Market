package com.ququla89.foliamarket.util

import org.bukkit.inventory.ItemStack

/**
 * Utility for serializing and deserializing Bukkit ItemStacks, preserving 1.21+ Data Components.
 */
object ItemSerializer {
    /**
     * Serializes an ItemStack into a byte array preserving all data components.
     */
    fun toByteArray(item: ItemStack): ByteArray = item.serializeAsBytes()

    /**
     * Deserializes an ItemStack from a byte array.
     */
    fun fromByteArray(bytes: ByteArray): ItemStack = ItemStack.deserializeBytes(bytes)
}
