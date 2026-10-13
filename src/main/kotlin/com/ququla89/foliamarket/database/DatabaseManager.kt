package com.ququla89.foliamarket.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.util.logging.Logger

/**
 * Manages the SQLite database connection pool using HikariCP.
 */
class DatabaseManager(
    dataFolder: File,
    poolSize: Int = 10,
    connectionTimeoutMs: Long = 10000L,
    private val logger: Logger,
) : AutoCloseable {
    private val dataSource: HikariDataSource

    init {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        val dbFile = File(dataFolder, "market.db")
        val config =
            HikariConfig().apply {
                poolName = "FoliaMarket-Pool"
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = poolSize
                minimumIdle = (poolSize / 2).coerceAtLeast(1)
                connectionTimeout = connectionTimeoutMs
                connectionInitSql = "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;"
                isAutoCommit = true
            }

        dataSource = HikariDataSource(config)
        initSchema()
        logger.info("SQLite database initialized successfully at: ${dbFile.name} (WAL mode enabled)")
    }

    /**
     * Obtains a connection from the pool.
     */
    fun getConnection(): Connection = dataSource.connection

    private fun initSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS market_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item_bytes BLOB NOT NULL,
                        price REAL NOT NULL,
                        listed_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        status TEXT NOT NULL
                    );
                    """.trimIndent(),
                )

                stmt.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_market_items_status_expires
                    ON market_items(status, expires_at);
                    """.trimIndent(),
                )

                stmt.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_market_items_seller
                    ON market_items(seller_uuid, status);
                    """.trimIndent(),
                )
            }
        }
    }

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
            logger.info("Database connection pool closed.")
        }
    }
}
