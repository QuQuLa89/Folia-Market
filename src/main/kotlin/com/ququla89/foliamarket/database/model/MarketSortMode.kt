package com.ququla89.foliamarket.database.model

/**
 * Enumeration representing available sorting orders for marketplace listings.
 */
enum class MarketSortMode(
    val displayName: String,
    val orderByClause: String,
) {
    NEWEST("新着順", "listed_at DESC, id DESC"),
    PRICE_ASC("価格が安い順", "price ASC, id ASC"),
    PRICE_DESC("価格が高い順", "price DESC, id DESC"),
    EXPIRING_SOON("期限が近い順", "expires_at ASC, id ASC"),
    ;

    /**
     * Cycles to the next sort mode:
     * NEWEST -> PRICE_ASC -> PRICE_DESC -> EXPIRING_SOON -> NEWEST
     */
    fun next(): MarketSortMode {
        val allModes = entries
        val nextIndex = (ordinal + 1) % allModes.size
        return allModes[nextIndex]
    }
}
