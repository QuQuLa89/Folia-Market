package com.ququla89.foliamarket.database.model

import java.util.UUID

/**
 * Status of a market listing.
 */
enum class MarketItemStatus {
    ACTIVE,
    SOLD,
    CANCELLED,
    EXPIRED,
}

/**
 * Data model representing a market item entry in the database.
 */
data class MarketItem(
    val id: Long = 0L,
    val sellerUuid: UUID,
    val sellerName: String,
    val itemName: String = "",
    val itemBytes: ByteArray,
    val price: Double,
    val listedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val status: MarketItemStatus = MarketItemStatus.ACTIVE,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MarketItem

        if (id != other.id) return false
        if (sellerUuid != other.sellerUuid) return false
        if (sellerName != other.sellerName) return false
        if (itemName != other.itemName) return false
        if (!itemBytes.contentEquals(other.itemBytes)) return false
        if (price != other.price) return false
        if (listedAt != other.listedAt) return false
        if (expiresAt != other.expiresAt) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sellerUuid.hashCode()
        result = 31 * result + sellerName.hashCode()
        result = 31 * result + itemName.hashCode()
        result = 31 * result + itemBytes.contentHashCode()
        result = 31 * result + price.hashCode()
        result = 31 * result + listedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}
