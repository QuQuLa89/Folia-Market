package com.ququla89.foliamarket

import com.ququla89.foliamarket.database.DatabaseManager
import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.engine.StressTestService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class StressTestServiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var databaseManager: DatabaseManager
    private lateinit var dao: MarketDao
    private lateinit var stressTestService: StressTestService
    private val logger = Logger.getLogger("StressTestTest")

    @BeforeEach
    fun setUp() {
        databaseManager = DatabaseManager(tempDir, poolSize = 10, connectionTimeoutMs = 5000L, logger = logger)
        dao = MarketDao(databaseManager)
        stressTestService = StressTestService(dao = dao, logger = logger)
    }

    @AfterEach
    fun tearDown() {
        databaseManager.close()
    }

    @Test
    fun testConcurrentStressTestSingleWinnerAndZeroDupes() {
        // 1. Arrange: insert an active listing
        val item =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "SellerPlayer",
                itemBytes = byteArrayOf(1, 2, 3),
                price = 100.0,
                listedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 3600000L,
                status = MarketItemStatus.ACTIVE,
            )
        val itemId = dao.insertItem(item)
        assertTrue(itemId > 0, "Item should be inserted with valid ID")

        // 2. Act: execute concurrent stress test with 20 synchronized threads
        val threadCount = 20
        val result = stressTestService.executeTest(itemId, threadCount)

        // 3. Assert: exactly 1 thread wins the CAS race, 19 threads roll back safely
        assertEquals(itemId, result.itemId)
        assertEquals(threadCount, result.threadCount)
        assertEquals(1, result.successCount, "Exactly 1 thread must succeed")
        assertEquals(19, result.alreadySoldCount, "19 threads must detect already sold and roll back")
        assertEquals(0, result.errorCount, "No unexpected errors should occur")
        assertTrue(result.isPassed, "Test must pass")
        assertFalse(result.isDupeDetected, "Dupe must NOT be detected")
        assertNotNull(result.winnerName, "Winning buyer name must be recorded")

        // Database state must be rolled back to ACTIVE so items never vanish
        val updatedItem = dao.getItemById(itemId)
        assertNotNull(updatedItem)
        assertEquals(MarketItemStatus.ACTIVE, updatedItem?.status, "Item status must be restored to ACTIVE after stress test")
    }

    @Test
    fun testHighConcurrencyStressTest50Threads() {
        // 1. Arrange: insert an active listing
        val item =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "SellerPlayer50",
                itemBytes = byteArrayOf(4, 5, 6),
                price = 250.0,
                listedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 3600000L,
                status = MarketItemStatus.ACTIVE,
            )
        val itemId = dao.insertItem(item)
        assertTrue(itemId > 0)

        // 2. Act: execute concurrent stress test with 50 threads
        val threadCount = 50
        val result = stressTestService.executeTest(itemId, threadCount)

        // 3. Assert: CAS exclusivity holds under high load
        assertEquals(1, result.successCount, "Exactly 1 thread must succeed under 50-thread contention")
        assertEquals(49, result.alreadySoldCount, "49 threads must roll back")
        assertEquals(0, result.errorCount)
        assertTrue(result.isPassed)
        assertFalse(result.isDupeDetected)

        // Database state must also be rolled back to ACTIVE
        val updatedItem50 = dao.getItemById(itemId)
        assertNotNull(updatedItem50)
        assertEquals(MarketItemStatus.ACTIVE, updatedItem50?.status, "Item status must be restored to ACTIVE after 50-thread test")
    }

    @Test
    fun testStressTestOnAlreadySoldItemFailsGracefully() {
        // 1. Arrange: insert an already SOLD listing
        val item =
            MarketItem(
                sellerUuid = UUID.randomUUID(),
                sellerName = "SellerPlayerSold",
                itemBytes = byteArrayOf(7, 8, 9),
                price = 500.0,
                listedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 3600000L,
                status = MarketItemStatus.SOLD,
            )
        val itemId = dao.insertItem(item)

        // 2. Act: run stress test with 10 threads
        val threadCount = 10
        val result = stressTestService.executeTest(itemId, threadCount)

        // 3. Assert: 0 successes, 10 rollbacks, isFailed = true
        assertEquals(0, result.successCount)
        assertEquals(10, result.alreadySoldCount)
        assertEquals(0, result.errorCount)
        assertTrue(result.isFailed)
        assertFalse(result.isDupeDetected)
    }
}
