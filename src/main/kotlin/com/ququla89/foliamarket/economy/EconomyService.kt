package com.ququla89.foliamarket.economy

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * Service managing Vault economy integration with safe fallback when absent.
 */
class EconomyService(
    private val plugin: Plugin,
    private val logger: Logger,
) {
    private var vaultEconomy: Economy? = null

    init {
        setupEconomy()
    }

    private fun setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            logger.warning("[FoliaMarket] Vault プラグインが検出されませんでした。経済機能はダミーモードで待機します。")
            return
        }

        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
        if (rsp == null) {
            logger.warning("[FoliaMarket] Vault 経済プロバイダー（Essentials, Economy等）が登録されていません。")
            return
        }

        vaultEconomy = rsp.provider
        logger.info("[FoliaMarket] Vault 経済プロバイダーと正常に接続しました: ${vaultEconomy?.name}")
    }

    /**
     * Checks if a valid economy provider is currently active.
     */
    fun isAvailable(): Boolean = vaultEconomy != null

    /**
     * Gets the account balance of a player.
     */
    fun getBalance(player: OfflinePlayer): Double = vaultEconomy?.getBalance(player) ?: 0.0

    /**
     * Checks if a player has at least the specified amount.
     */
    fun has(
        player: OfflinePlayer,
        amount: Double,
    ): Boolean = vaultEconomy?.has(player, amount) ?: true

    /**
     * Withdraws funds from a player account.
     */
    fun withdraw(
        player: OfflinePlayer,
        amount: Double,
    ): Boolean {
        val econ = vaultEconomy ?: return true
        val response = econ.withdrawPlayer(player, amount)
        return response.transactionSuccess()
    }

    /**
     * Deposits funds into a player account.
     */
    fun deposit(
        player: OfflinePlayer,
        amount: Double,
    ): Boolean {
        val econ = vaultEconomy ?: return true
        val response = econ.depositPlayer(player, amount)
        return response.transactionSuccess()
    }

    /**
     * Returns currency symbol or name.
     */
    fun getCurrencyName(): String = vaultEconomy?.currencyNamePlural() ?: "円"

    /**
     * Formats an amount with currency name.
     */
    fun format(amount: Double): String = vaultEconomy?.format(amount) ?: "%,.2f %s".format(amount, getCurrencyName())
}
