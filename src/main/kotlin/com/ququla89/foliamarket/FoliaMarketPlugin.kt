package com.ququla89.foliamarket

import com.ququla89.foliamarket.command.MarketCommand
import com.ququla89.foliamarket.database.DatabaseManager
import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.economy.EconomyService
import com.ququla89.foliamarket.engine.MarketService
import com.ququla89.foliamarket.gui.GuiListener
import com.ququla89.foliamarket.gui.GuiType
import com.ququla89.foliamarket.gui.MarketGui
import com.ququla89.foliamarket.gui.MarketHolder
import com.ququla89.foliamarket.scheduler.FoliaSchedulerAdapter
import com.ququla89.foliamarket.scheduler.PaperSchedulerAdapter
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.plugin.java.JavaPlugin

/**
 * Main plugin class for FoliaMarket.
 */
class FoliaMarketPlugin : JavaPlugin() {
    private lateinit var schedulerAdapter: SchedulerAdapter
    private lateinit var databaseManager: DatabaseManager
    private lateinit var marketDao: MarketDao
    private lateinit var economyService: EconomyService
    private lateinit var marketService: MarketService
    private lateinit var marketGui: MarketGui
    private var isFoliaPlatform: Boolean = false
    private var expirationTaskHandle: AutoCloseable? = null
    private var guiRefreshTaskHandle: AutoCloseable? = null

    override fun onEnable() {
        saveDefaultConfig()

        // 1. Detect server platform (Folia vs Paper)
        isFoliaPlatform = detectFolia()
        schedulerAdapter =
            if (isFoliaPlatform) {
                logger.info("Folia マルチスレッド環境を検出しました。FoliaSchedulerAdapter を初期化します。")
                FoliaSchedulerAdapter(this)
            } else {
                logger.info("Paper / Bukkit 環境を検出しました。PaperSchedulerAdapter を初期化します。")
                PaperSchedulerAdapter(this)
            }

        // 2. Initialize database
        val poolSize = config.getInt("market.database.maximum-pool-size", 10)
        val timeoutMs = config.getLong("market.database.connection-timeout-ms", 10000L)
        databaseManager = DatabaseManager(dataFolder, poolSize, timeoutMs, logger)
        marketDao = MarketDao(databaseManager)

        // 3. Initialize economy
        economyService = EconomyService(this, logger)

        // 4. Initialize transaction engine & GUI
        marketService = MarketService(marketDao, economyService, schedulerAdapter, config, logger)
        marketGui = MarketGui(this, marketDao, economyService, schedulerAdapter, config)

        // 5. Register GUI event listener
        server.pluginManager.registerEvents(GuiListener(marketGui, marketService), this)

        // 6. Register /market command
        registerMarketCommand()

        // 7. Schedule background expiration task (runs every 60 seconds)
        expirationTaskHandle =
            schedulerAdapter.runTimerAsync(
                intervalTicks = 1200L,
                delayTicks = 200L,
            ) {
                val expiredCount = marketDao.expireOverdueItems(System.currentTimeMillis())
                if (expiredCount > 0) {
                    logger.info("[FoliaMarket] 期限切れの出品 $expiredCount 件のステータスを更新しました。")
                }
            }

        // 8. Schedule reactive GUI refresh timer
        val refreshIntervalSeconds = config.getLong("gui.refresh-interval-seconds", 5L)
        if (refreshIntervalSeconds > 0) {
            val refreshTicks = refreshIntervalSeconds * 20L
            guiRefreshTaskHandle =
                schedulerAdapter.runTimerAsync(
                    intervalTicks = refreshTicks,
                    delayTicks = refreshTicks,
                ) {
                    for (player in Bukkit.getOnlinePlayers()) {
                        val topInv = player.openInventory.topInventory
                        val holder = topInv.holder
                        if (holder is MarketHolder && holder.type == GuiType.MARKET) {
                            schedulerAdapter.runAtEntity(player) {
                                marketGui.openMarket(player, holder.page)
                            }
                        }
                    }
                }
        }

        logger.info("FoliaMarket v${pluginMeta.version} が正常に有効化されました。")
    }

    override fun onDisable() {
        expirationTaskHandle?.close()
        guiRefreshTaskHandle?.close()

        if (::schedulerAdapter.isInitialized) {
            schedulerAdapter.cancelAll()
        }

        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }

        logger.info("FoliaMarket を正常に無効化しました。")
    }

    private fun reloadPlugin() {
        reloadConfig()
        val newConfig = config
        marketService.reloadConfig(newConfig)
        marketGui.reloadConfig(newConfig)
    }

    private fun registerMarketCommand() {
        val executor =
            MarketCommand(
                plugin = this,
                marketService = marketService,
                marketGui = marketGui,
                config = config,
                reloadCallback = { reloadPlugin() },
            )

        val bukkitCommand =
            object : BukkitCommand(
                "market",
                "FoliaMarket のメインコマンドです。",
                "/market [sell <価格>|listings|reload]",
                listOf("foliamarket", "fm"),
            ) {
                override fun execute(
                    sender: CommandSender,
                    commandLabel: String,
                    args: Array<out String>,
                ): Boolean = executor.onCommand(sender, this, commandLabel, args)

                override fun tabComplete(
                    sender: CommandSender,
                    alias: String,
                    args: Array<out String>,
                ): List<String> = executor.onTabComplete(sender, this, alias, args)
            }

        server.commandMap.register("foliamarket", bukkitCommand)
    }

    private fun detectFolia(): Boolean =
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
}
