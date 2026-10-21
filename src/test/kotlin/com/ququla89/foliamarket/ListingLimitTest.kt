package com.ququla89.foliamarket

import com.ququla89.foliamarket.database.DatabaseManager
import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItem
import com.ququla89.foliamarket.database.model.MarketItemStatus
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingLimitTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `simultaneous listing requests on independent connections cannot exceed seller limit`() {
        DatabaseManager(tempDir, logger = Logger.getLogger("ListingLimitTest")).use { database ->
            val daos = listOf(MarketDao(database), MarketDao(database))
            val item = item(UUID.randomUUID())
            val executor = Executors.newFixedThreadPool(24)
            val ready = CountDownLatch(24)
            val start = CountDownLatch(1)
            try {
                val results =
                    (0 until 24).map { index ->
                        executor.submit<Long?> {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            daos[index % daos.size].insertItemIfBelowLimit(item, 3)
                        }
                    }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val ids = results.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(3, ids.count { it != null && it > 0 })
                assertEquals(21, ids.count { it == null })
                assertEquals(3, daos.first().countActiveItemsBySeller(item.sellerUuid))
                assertNotNull(daos.last().insertItemIfBelowLimit(item(UUID.randomUUID()), 3))
            } finally {
                start.countDown()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `expired and closed listings do not consume capacity and zero limit rejects inserts`() {
        DatabaseManager(tempDir, logger = Logger.getLogger("ListingLimitTest")).use { database ->
            val dao = MarketDao(database)
            val item = item(UUID.randomUUID())
            dao.insertItem(item.copy(expiresAt = 1L))
            dao.insertItem(item.copy(status = MarketItemStatus.SOLD))
            assertNull(dao.insertItemIfBelowLimit(item, 0))
            assertNotNull(dao.insertItemIfBelowLimit(item, 1))
            assertNull(dao.insertItemIfBelowLimit(item, 1))
            assertEquals(1, dao.countActiveItemsBySeller(item.sellerUuid))
        }
    }

    @Test
    fun `failed insert rolls back and releases the pooled connection for the next request`() {
        DatabaseManager(tempDir, poolSize = 1, logger = Logger.getLogger("ListingLimitTest")).use { database ->
            val dao = MarketDao(database)
            database.getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TRIGGER reject_listing BEFORE INSERT ON market_items BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                    )
                }
            }
            val item = item(UUID.randomUUID())
            assertFailsWith<SQLException> { dao.insertItemIfBelowLimit(item, 1) }
            assertEquals(0, dao.countActiveItemsBySeller(item.sellerUuid))
            database.getConnection().use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER reject_listing") }
            }
            assertNotNull(dao.insertItemIfBelowLimit(item, 1))
        }
    }

    private fun item(seller: UUID): MarketItem {
        val now = System.currentTimeMillis()
        return MarketItem(
            sellerUuid = seller,
            sellerName = "QuQuLa89",
            itemName = "Stone",
            itemBytes = byteArrayOf(1),
            price = 10.0,
            listedAt = now,
            expiresAt = now + 3_600_000L,
            status = MarketItemStatus.ACTIVE,
        )
    }
}
