package com.ququla89.foliamarket.database

import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.database.model.MarketSortMode
import com.ququla89.foliamarket.util.ItemSerializer
import com.ququla89.foliamarket.util.ItemTranslationManager
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import java.util.logging.Logger

/**
 * Data Access Object for marketplace items providing thread-safe, atomic operations.
 */
class MarketDao(
    private val databaseManager: DatabaseManager,
) {
    /**
     * Inserts a new market listing and returns the generated primary key.
     */
    fun insertItem(item: MarketItem): Long {
        val sql =
            """
            INSERT INTO market_items (
                seller_uuid, seller_name, item_name, item_bytes, price, listed_at, expires_at, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
                stmt.setString(1, item.sellerUuid.toString())
                stmt.setString(2, item.sellerName)
                stmt.setString(3, item.itemName)
                stmt.setBytes(4, item.itemBytes)
                stmt.setDouble(5, item.price)
                stmt.setLong(6, item.listedAt)
                stmt.setLong(7, item.expiresAt)
                stmt.setString(8, item.status.name)

                stmt.executeUpdate()
                val rs = stmt.generatedKeys
                return if (rs.next()) rs.getLong(1) else -1L
            }
        }
    }

    /**
     * Retrieves an item by its unique ID.
     */
    fun getItemById(id: Long): MarketItem? {
        val sql = "SELECT * FROM market_items WHERE id = ? LIMIT 1"
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    /**
     * Fetches active non-expired listings with pagination, sorting, and optional search keyword.
     */
    fun getActiveItems(
        offset: Int,
        limit: Int,
        sortMode: MarketSortMode = MarketSortMode.NEWEST,
        searchKeyword: String? = null,
    ): List<MarketItem> {
        val now = System.currentTimeMillis()
        val keyword = searchKeyword?.trim()?.takeIf { it.isNotEmpty() }
        val sql =
            if (keyword != null) {
                """
                SELECT * FROM market_items
                WHERE status = 'ACTIVE' AND expires_at > ?
                  AND (item_name LIKE ? OR seller_name LIKE ?)
                ORDER BY ${sortMode.orderByClause}
                LIMIT ? OFFSET ?
                """.trimIndent()
            } else {
                """
                SELECT * FROM market_items
                WHERE status = 'ACTIVE' AND expires_at > ?
                ORDER BY ${sortMode.orderByClause}
                LIMIT ? OFFSET ?
                """.trimIndent()
            }

        val list = ArrayList<MarketItem>(limit)
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                if (keyword != null) {
                    val pattern = "%$keyword%"
                    stmt.setString(2, pattern)
                    stmt.setString(3, pattern)
                    stmt.setInt(4, limit)
                    stmt.setInt(5, offset)
                } else {
                    stmt.setInt(2, limit)
                    stmt.setInt(3, offset)
                }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(mapRow(rs))
                    }
                }
            }
        }
        return list
    }

    /**
     * Counts the total number of active non-expired listings, optionally filtered by keyword.
     */
    fun countActiveItems(searchKeyword: String? = null): Int {
        val now = System.currentTimeMillis()
        val keyword = searchKeyword?.trim()?.takeIf { it.isNotEmpty() }
        val sql =
            if (keyword != null) {
                "SELECT COUNT(*) FROM market_items WHERE status = 'ACTIVE' AND expires_at > ? AND (item_name LIKE ? OR seller_name LIKE ?)"
            } else {
                "SELECT COUNT(*) FROM market_items WHERE status = 'ACTIVE' AND expires_at > ?"
            }

        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                if (keyword != null) {
                    val pattern = "%$keyword%"
                    stmt.setString(2, pattern)
                    stmt.setString(3, pattern)
                }
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    /**
     * Counts the number of active listings by a specific seller.
     */
    fun countActiveItemsBySeller(sellerUuid: UUID): Int {
        val now = System.currentTimeMillis()
        val sql =
            """
            SELECT COUNT(*) FROM market_items
            WHERE seller_uuid = ? AND status = 'ACTIVE' AND expires_at > ?
            """.trimIndent()

        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, sellerUuid.toString())
                stmt.setLong(2, now)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    /**
     * Retrieves all listings for a given seller, optionally filtered by status.
     */
    fun getItemsBySeller(
        sellerUuid: UUID,
        status: MarketItemStatus? = null,
    ): List<MarketItem> {
        val sql =
            if (status != null) {
                "SELECT * FROM market_items WHERE seller_uuid = ? AND status = ? ORDER BY listed_at DESC"
            } else {
                "SELECT * FROM market_items WHERE seller_uuid = ? ORDER BY listed_at DESC"
            }

        val list = ArrayList<MarketItem>()
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, sellerUuid.toString())
                if (status != null) {
                    stmt.setString(2, status.name)
                }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(mapRow(rs))
                    }
                }
            }
        }
        return list
    }

    /**
     * Atomically updates status using Compare-And-Swap (CAS).
     * Returns true if and only if the item was in fromStatus and transitioned to toStatus.
     */
    fun casUpdateStatus(
        id: Long,
        fromStatus: MarketItemStatus,
        toStatus: MarketItemStatus,
    ): Boolean {
        val sql = "UPDATE market_items SET status = ? WHERE id = ? AND status = ?"
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, toStatus.name)
                stmt.setLong(2, id)
                stmt.setString(3, fromStatus.name)
                val rowsAffected = stmt.executeUpdate()
                return rowsAffected > 0
            }
        }
    }

    /**
     * Automatically transitions overdue active items to EXPIRED status.
     */
    fun expireOverdueItems(currentTime: Long): Int {
        val sql = "UPDATE market_items SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expires_at <= ?"
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, currentTime)
                return stmt.executeUpdate()
            }
        }
    }

    /**
     * Retrieves IDs of active non-expired listings for tab completion.
     */
    fun getActiveItemIds(limit: Int = 50): List<Long> {
        val now = System.currentTimeMillis()
        val sql =
            """
            SELECT id FROM market_items
            WHERE status = 'ACTIVE' AND expires_at > ?
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent()

        val list = ArrayList<Long>(limit)
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(rs.getLong("id"))
                    }
                }
            }
        }
        return list
    }

    /**
     * Atomically updates status using Compare-And-Swap (CAS) with string parameters.
     */
    fun updateItemStatusCas(
        id: Long,
        fromStatus: String,
        toStatus: String,
    ): Boolean = casUpdateStatus(id, MarketItemStatus.valueOf(fromStatus), MarketItemStatus.valueOf(toStatus))

    /**
     * Updates the item_name (search index) for a specific item.
     */
    fun updateItemName(
        id: Long,
        newName: String,
    ): Boolean {
        val sql = "UPDATE market_items SET item_name = ? WHERE id = ?"
        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, newName)
                stmt.setLong(2, id)
                return stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Backfills search indexes for existing items in the database by deserializing
     * item bytes and building full Japanese + English search keywords.
     */
    fun backfillSearchIndexes(logger: Logger? = null): Int {
        val updates = ArrayList<Pair<Long, String>>()
        val selectSql = "SELECT id, item_bytes, item_name FROM market_items"

        databaseManager.getConnection().use { conn ->
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong("id")
                        val bytes = rs.getBytes("item_bytes") ?: continue
                        val currentName = rs.getString("item_name") ?: ""
                        try {
                            val item = ItemSerializer.fromByteArray(bytes)
                            val searchIndex = ItemTranslationManager.buildSearchIndex(item)
                            if (searchIndex.isNotBlank() && searchIndex != currentName) {
                                updates.add(id to searchIndex)
                            }
                        } catch (e: Exception) {
                            logger?.fine("Skipping backfill for item $id: ${e.message}")
                        }
                    }
                }
            }

            if (updates.isEmpty()) {
                return 0
            }

            val updateSql = "UPDATE market_items SET item_name = ? WHERE id = ?"
            conn.prepareStatement(updateSql).use { updateStmt ->
                for ((id, newIndex) in updates) {
                    updateStmt.setString(1, newIndex)
                    updateStmt.setLong(2, id)
                    updateStmt.addBatch()
                }
                updateStmt.executeBatch()
            }
        }
        return updates.size
    }

    private fun mapRow(rs: ResultSet): MarketItem =
        MarketItem(
            id = rs.getLong("id"),
            sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
            sellerName = rs.getString("seller_name"),
            itemName = rs.getString("item_name") ?: "",
            itemBytes = rs.getBytes("item_bytes"),
            price = rs.getDouble("price"),
            listedAt = rs.getLong("listed_at"),
            expiresAt = rs.getLong("expires_at"),
            status = MarketItemStatus.valueOf(rs.getString("status")),
        )
}
