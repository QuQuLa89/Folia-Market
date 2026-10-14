package com.ququla89.foliamarket.command

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketSortMode
import com.ququla89.foliamarket.engine.MarketService
import com.ququla89.foliamarket.engine.StressTestService
import com.ququla89.foliamarket.gui.MarketGui
import com.ququla89.foliamarket.util.MessageUtil
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Command executor and tab completer for /market commands.
 */
class MarketCommand(
    private val plugin: Plugin,
    private val marketService: MarketService,
    private val stressTestService: StressTestService,
    private val marketDao: MarketDao,
    private val marketGui: MarketGui,
    private var config: FileConfiguration,
    private val reloadCallback: () -> Unit,
) : CommandExecutor,
    TabCompleter {
    fun reloadConfig(newConfig: FileConfiguration) {
        this.config = newConfig
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val prefix = config.getString("messages.prefix", "") ?: ""

        if (args.isEmpty()) {
            if (sender !is Player) {
                MessageUtil.send(sender, config.getString("messages.player-only", "<red>プレイヤーのみ実行可能です。</red>"), prefix = prefix)
                return true
            }
            if (!sender.hasPermission("foliamarket.use")) {
                MessageUtil.send(sender, config.getString("messages.no-permission", "<red>権限がありません。</red>"), prefix = prefix)
                return true
            }
            marketGui.openMarket(sender, 0)
            return true
        }

        when (args[0].lowercase()) {
            "sell" -> {
                if (sender !is Player) {
                    MessageUtil.send(sender, config.getString("messages.player-only", "<red>プレイヤーのみ実行可能です。</red>"), prefix = prefix)
                    return true
                }
                if (!sender.hasPermission("foliamarket.sell")) {
                    MessageUtil.send(sender, config.getString("messages.no-permission", "<red>権限がありません。</red>"), prefix = prefix)
                    return true
                }
                if (args.size < 2) {
                    MessageUtil.send(
                        sender,
                        "<yellow>使用方法: /$label sell <価格></yellow>",
                        prefix = prefix,
                    )
                    return true
                }

                val price = args[1].toDoubleOrNull()
                if (price == null || price <= 0.0) {
                    MessageUtil.send(
                        sender,
                        config.getString("messages.invalid-price", "<red>有効な数値を入力してください。</red>"),
                        mapOf(
                            "min" to marketService.minPrice.toString(),
                            "max" to marketService.maxPrice.toString(),
                        ),
                        prefix,
                    )
                    return true
                }

                marketService.listItem(sender, price)
                return true
            }

            "search", "s" -> {
                if (sender !is Player) {
                    MessageUtil.send(sender, config.getString("messages.player-only", "<red>プレイヤーのみ実行可能です。</red>"), prefix = prefix)
                    return true
                }
                if (!sender.hasPermission("foliamarket.use")) {
                    MessageUtil.send(sender, config.getString("messages.no-permission", "<red>権限がありません。</red>"), prefix = prefix)
                    return true
                }
                if (args.size < 2) {
                    MessageUtil.send(
                        sender,
                        config.getString("messages.search-usage", "<yellow>使用方法: /$label search <キーワード></yellow>"),
                        prefix = prefix,
                    )
                    return true
                }

                val keyword = args.drop(1).joinToString(" ").trim()
                if (keyword.isEmpty()) {
                    MessageUtil.send(
                        sender,
                        config.getString("messages.search-usage", "<yellow>使用方法: /$label search <キーワード></yellow>"),
                        prefix = prefix,
                    )
                    return true
                }

                marketGui.openMarket(sender, page = 0, sortMode = MarketSortMode.NEWEST, searchKeyword = keyword)
                return true
            }

            "listings", "my" -> {
                if (sender !is Player) {
                    MessageUtil.send(sender, config.getString("messages.player-only", "<red>プレイヤーのみ実行可能です。</red>"), prefix = prefix)
                    return true
                }
                if (!sender.hasPermission("foliamarket.use")) {
                    MessageUtil.send(sender, config.getString("messages.no-permission", "<red>権限がありません。</red>"), prefix = prefix)
                    return true
                }
                marketGui.openMyListings(sender, 0)
                return true
            }

            "reload" -> {
                if (!sender.hasPermission("foliamarket.admin")) {
                    MessageUtil.send(sender, config.getString("messages.no-permission", "<red>権限がありません。</red>"), prefix = prefix)
                    return true
                }

                reloadCallback()
                MessageUtil.send(
                    sender,
                    config.getString("messages.reload-success", "<green>設定ファイルを再読み込みしました。</green>"),
                    prefix = prefix,
                )
                return true
            }

            "debug" -> {
                if (!sender.hasPermission("foliamarket.admin")) {
                    MessageUtil.send(sender, config.getString("messages.no-permission", "<red>権限がありません。</red>"), prefix = prefix)
                    return true
                }

                if (!plugin.config.getBoolean("debug-mode", false)) {
                    MessageUtil.send(
                        sender,
                        "<red>デバッグ機能は無効化されています。config.yml の debug-mode を true に設定してください。</red>",
                        prefix = prefix,
                    )
                    return true
                }

                if (args.size < 2 || !args[1].equals("stress", ignoreCase = true)) {
                    MessageUtil.send(
                        sender,
                        "<yellow>使用方法: /$label debug stress <出品ID> [スレッド数 (2-100)]</yellow>",
                        prefix = prefix,
                    )
                    return true
                }

                if (args.size < 3) {
                    MessageUtil.send(
                        sender,
                        "<yellow>使用方法: /$label debug stress <出品ID> [スレッド数 (2-100)]</yellow>",
                        prefix = prefix,
                    )
                    return true
                }

                val itemId = args[2].toLongOrNull()
                if (itemId == null || itemId <= 0) {
                    MessageUtil.send(
                        sender,
                        "<red>有効な出品ID（正の数値）を指定してください。</red>",
                        prefix = prefix,
                    )
                    return true
                }

                val threads =
                    if (args.size >= 4) {
                        val parsed = args[3].toIntOrNull()
                        if (parsed == null || parsed !in 2..100) {
                            MessageUtil.send(
                                sender,
                                "<red>スレッド数は 2 から 100 の間で指定してください。（指定値: ${args[3]}）</red>",
                                prefix = prefix,
                            )
                            return true
                        }
                        parsed
                    } else {
                        20
                    }

                stressTestService.runStressTest(sender, itemId, threads, prefix)
                return true
            }

            else -> {
                val isDebugMode = plugin.config.getBoolean("debug-mode", false)
                val adminHelp =
                    if (sender.hasPermission("foliamarket.admin") && isDebugMode) {
                        "\n<gray>- </gray><yellow>/$label debug stress <ID> [スレッド数]</yellow><gray>: 並行購入ストレステストを実行します</gray>"
                    } else {
                        ""
                    }
                MessageUtil.send(
                    sender,
                    "<gradient:#00b4db:#0083b0><b>[FoliaMarket]</b></gradient> コマンドヘルプ:\n" +
                        "<gray>- </gray><yellow>/$label</yellow><gray>: マーケットプレイスを開きます</gray>\n" +
                        "<gray>- </gray><yellow>/$label search <キーワード></yellow><gray>: アイテムまたは出品者を検索します</gray>\n" +
                        "<gray>- </gray><yellow>/$label sell <価格></yellow><gray>: 手持ちのアイテムを出品します</gray>\n" +
                        "<gray>- </gray><yellow>/$label listings</yellow><gray>: 自分の出品一覧を開きます</gray>\n" +
                        "<gray>- </gray><yellow>/$label reload</yellow><gray>: 設定ファイルを再読み込みします</gray>$adminHelp",
                )
                return true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val isDebugMode = plugin.config.getBoolean("debug-mode", false)

        if (args.size == 1) {
            val suggestions = mutableListOf<String>()
            if (sender.hasPermission("foliamarket.use")) {
                suggestions.add("listings")
                suggestions.add("search")
            }
            if (sender.hasPermission("foliamarket.sell")) {
                suggestions.add("sell")
            }
            if (sender.hasPermission("foliamarket.admin")) {
                suggestions.add("reload")
                if (isDebugMode) {
                    suggestions.add("debug")
                }
            }
            return suggestions.filter { it.startsWith(args[0], ignoreCase = true) }
        }

        if (args[0].equals("debug", ignoreCase = true) && (!isDebugMode || !sender.hasPermission("foliamarket.admin"))) {
            return emptyList()
        }

        if (args.size == 2) {
            if (args[0].equals("sell", ignoreCase = true)) {
                return listOf("100", "500", "1000", "5000").filter { it.startsWith(args[1]) }
            }
            if (args[0].equals("debug", ignoreCase = true) && sender.hasPermission("foliamarket.admin")) {
                return listOf("stress").filter { it.startsWith(args[1], ignoreCase = true) }
            }
        }

        if (args.size == 3 && args[0].equals("debug", ignoreCase = true) && args[1].equals("stress", ignoreCase = true)) {
            if (sender.hasPermission("foliamarket.admin")) {
                val activeIds = marketDao.getActiveItemIds(50).map { it.toString() }
                return activeIds.filter { it.startsWith(args[2]) }
            }
        }

        if (args.size == 4 && args[0].equals("debug", ignoreCase = true) && args[1].equals("stress", ignoreCase = true)) {
            if (sender.hasPermission("foliamarket.admin")) {
                return listOf("10", "20", "50", "100").filter { it.startsWith(args[3]) }
            }
        }

        return emptyList()
    }
}
