package com.ququla89.foliamarket

import com.ququla89.foliamarket.database.DatabaseManager
import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.database.model.MarketSortMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class MarketDaoTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var databaseManager: DatabaseManager
    private lateinit var dao: MarketDao
    private val logger = Logger.getLogger("MarketDaoTest")

    @BeforeEach
    fun setUp() {
        databaseManager = DatabaseManager(tempDir, poolSize = 5, connectionTimeoutMs = 5000L, logger = logger)
        dao = MarketDao(databaseManager)
    }

    @AfterEach
    fun tearDown() {
        databaseManager.close()
    }

    @Test
    fun testInsertAndRetrieveItemWithItemName() {
        val sellerUuid = UUID.randomUUID()
        val item =
            MarketItem(
                sellerUuid = sellerUuid,
                sellerName = "Alice",
                itemName = "Netherite Sword",
                itemBytes = byteArrayOf(10, 20, 30),
                price = 1500.0,
                listedAt = 1000000L,
                expiresAt = 2000000L,
                status = MarketItemStatus.ACTIVE,
            )

        val id = dao.insertItem(item)
        assertTrue(id > 0, "Generated ID must be positive")

        val retrieved = dao.getItemById(id)
        assertNotNull(retrieved)
        assertEquals("Netherite Sword", retrieved?.itemName)
        assertEquals("Alice", retrieved?.sellerName)
        assertEquals(1500.0, retrieved?.price)
        assertEquals(sellerUuid, retrieved?.sellerUuid)
    }

    @Test
    fun testSortModeOrdering() {
        val now = System.currentTimeMillis()
        val future = now + 1000000L

        // Insert 3 items with distinct price, listedAt, and expiresAt
        val item1 =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "PlayerA",
                itemName = "Item Cheap Old ExpiryFar",
                itemBytes = byteArrayOf(1),
                price = 10.0,
                listedAt = now - 3000,
                expiresAt = future + 3000,
                status = MarketItemStatus.ACTIVE,
            )
        val item2 =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "PlayerB",
                itemName = "Item Medium Middle ExpiryClose",
                itemBytes = byteArrayOf(2),
                price = 50.0,
                listedAt = now - 2000,
                expiresAt = future + 1000,
                status = MarketItemStatus.ACTIVE,
            )
        val item3 =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "PlayerC",
                itemName = "Item Expensive New ExpiryMid",
                itemBytes = byteArrayOf(3),
                price = 100.0,
                listedAt = now - 1000,
                expiresAt = future + 2000,
                status = MarketItemStatus.ACTIVE,
            )

        val id1 = dao.insertItem(item1)
        val id2 = dao.insertItem(item2)
        val id3 = dao.insertItem(item3)

        // Test NEWEST sort: item3 (newest listed) -> item2 -> item1
        val newestItems = dao.getActiveItems(0, 10, MarketSortMode.NEWEST)
        assertEquals(listOf(id3, id2, id1), newestItems.map { it.id })

        // Test PRICE_ASC sort: item1 (10.0) -> item2 (50.0) -> item3 (100.0)
        val priceAscItems = dao.getActiveItems(0, 10, MarketSortMode.PRICE_ASC)
        assertEquals(listOf(id1, id2, id3), priceAscItems.map { it.id })

        // Test PRICE_DESC sort: item3 (100.0) -> item2 (50.0) -> item1 (10.0)
        val priceDescItems = dao.getActiveItems(0, 10, MarketSortMode.PRICE_DESC)
        assertEquals(listOf(id3, id2, id1), priceDescItems.map { it.id })

        // Test EXPIRING_SOON sort: item2 (expires first) -> item3 -> item1
        val expiringItems = dao.getActiveItems(0, 10, MarketSortMode.EXPIRING_SOON)
        assertEquals(listOf(id2, id3, id1), expiringItems.map { it.id })
    }

    @Test
    fun testSearchByItemNameAndSellerName() {
        val now = System.currentTimeMillis()
        val future = now + 1000000L

        val swordItem =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "BobTheBuilder",
                itemName = "Diamond Pickaxe",
                itemBytes = byteArrayOf(1),
                price = 200.0,
                listedAt = now,
                expiresAt = future,
                status = MarketItemStatus.ACTIVE,
            )
        val potionItem =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "AlchemistAlice",
                itemName = "Speed Potion",
                itemBytes = byteArrayOf(2),
                price = 50.0,
                listedAt = now,
                expiresAt = future,
                status = MarketItemStatus.ACTIVE,
            )

        dao.insertItem(swordItem)
        dao.insertItem(potionItem)

        // Search matching item name
        val searchByName = dao.getActiveItems(0, 10, MarketSortMode.NEWEST, searchKeyword = "Pickaxe")
        assertEquals(1, searchByName.size)
        assertEquals("Diamond Pickaxe", searchByName[0].itemName)
        assertEquals(1, dao.countActiveItems(searchKeyword = "Pickaxe"))

        // Search matching seller name
        val searchBySeller = dao.getActiveItems(0, 10, MarketSortMode.NEWEST, searchKeyword = "Alchemist")
        assertEquals(1, searchBySeller.size)
        assertEquals("Speed Potion", searchBySeller[0].itemName)
        assertEquals("AlchemistAlice", searchBySeller[0].sellerName)
        assertEquals(1, dao.countActiveItems(searchKeyword = "Alchemist"))

        // Search matching none
        val searchNone = dao.getActiveItems(0, 10, MarketSortMode.NEWEST, searchKeyword = "NonExistent")
        assertEquals(0, searchNone.size)
        assertEquals(0, dao.countActiveItems(searchKeyword = "NonExistent"))

        // Total count without keyword
        assertEquals(2, dao.countActiveItems(searchKeyword = null))
    }

    @Test
    fun testSortModeCycleNext() {
        var mode = MarketSortMode.NEWEST
        mode = mode.next()
        assertEquals(MarketSortMode.PRICE_ASC, mode)
        mode = mode.next()
        assertEquals(MarketSortMode.PRICE_DESC, mode)
        mode = mode.next()
        assertEquals(MarketSortMode.EXPIRING_SOON, mode)
        mode = mode.next()
        assertEquals(MarketSortMode.NEWEST, mode)
    }

    @Test
    fun testSearchByJapaneseKeywords() {
        val now = System.currentTimeMillis()
        val future = now + 1000000L

        val diamondSword =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "Steve",
                itemName = "ダイヤモンドの剣 DIAMOND_SWORD DIAMOND SWORD diamond sword",
                itemBytes = byteArrayOf(1),
                price = 500.0,
                listedAt = now,
                expiresAt = future,
                status = MarketItemStatus.ACTIVE,
            )
        val stonePickaxe =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "Alex",
                itemName = "丸石のツルハシ COBBLESTONE_PICKAXE cobblestone pickaxe",
                itemBytes = byteArrayOf(2),
                price = 30.0,
                listedAt = now,
                expiresAt = future,
                status = MarketItemStatus.ACTIVE,
            )

        dao.insertItem(diamondSword)
        dao.insertItem(stonePickaxe)

        // Search by Japanese kanji / katakana
        val searchByDia = dao.getActiveItems(0, 10, searchKeyword = "ダイヤ")
        assertEquals(1, searchByDia.size)
        assertTrue(searchByDia[0].itemName.contains("ダイヤモンドの剣"))

        val searchBySword = dao.getActiveItems(0, 10, searchKeyword = "剣")
        assertEquals(1, searchBySword.size)
        assertEquals("Steve", searchBySword[0].sellerName)

        val searchByTsuruhashi = dao.getActiveItems(0, 10, searchKeyword = "ツルハシ")
        assertEquals(1, searchByTsuruhashi.size)
        assertEquals("Alex", searchByTsuruhashi[0].sellerName)

        // Search by English material keywords
        val searchByEnglish = dao.getActiveItems(0, 10, searchKeyword = "diamond")
        assertEquals(1, searchByEnglish.size)

        // Search not matching
        val searchNotFound = dao.getActiveItems(0, 10, searchKeyword = "ネザライト")
        assertEquals(0, searchNotFound.size)
    }

    @Test
    fun testUpdateItemName() {
        val now = System.currentTimeMillis()
        val item =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "Trader",
                itemName = "old_name",
                itemBytes = byteArrayOf(1),
                price = 100.0,
                listedAt = now,
                expiresAt = now + 100000L,
                status = MarketItemStatus.ACTIVE,
            )
        val id = dao.insertItem(item)
        val updated = dao.updateItemName(id, "ダイヤモンドの剣 DIAMOND_SWORD diamond sword")
        assertTrue(updated)

        val retrieved = dao.getItemById(id)
        assertNotNull(retrieved)
        assertEquals("ダイヤモンドの剣 DIAMOND_SWORD diamond sword", retrieved?.itemName)
    }
}
