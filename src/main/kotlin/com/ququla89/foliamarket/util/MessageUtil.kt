package com.ququla89.foliamarket.util

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Utility for parsing and sending Adventure MiniMessage components.
 */
object MessageUtil {
    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val plainSerializer: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()

    /**
     * Parses a MiniMessage formatted string into a Kyori Component.
     */
    fun parse(
        text: String?,
        placeholders: Map<String, String> = emptyMap(),
    ): Component {
        var processed = text ?: ""
        for ((key, value) in placeholders) {
            processed = processed.replace("{$key}", value)
        }
        return miniMessage.deserialize(processed)
    }

    /**
     * Converts a Component to plain text string.
     */
    fun toPlainText(component: Component): String = plainSerializer.serialize(component)

    /**
     * Sends a formatted MiniMessage to an audience (e.g. Player, Console).
     */
    fun send(
        audience: Audience,
        text: String?,
        placeholders: Map<String, String> = emptyMap(),
        prefix: String = "",
    ) {
        val raw = text ?: ""
        val fullMessage =
            if (prefix.isNotEmpty() && !raw.startsWith(prefix)) {
                "$prefix$raw"
            } else {
                raw
            }
        audience.sendMessage(parse(fullMessage, placeholders))
    }

    /**
     * Sends a rich receipt breakdown to a buyer.
     */
    fun sendReceipt(
        audience: Audience,
        itemName: String,
        amount: Int,
        totalPrice: Double,
        currencySymbol: String,
        sellerName: String,
    ) {
        val line = "<dark_gray>----------------------------------------</dark_gray>"
        audience.sendMessage(parse(line))
        audience.sendMessage(parse("<gradient:#f12711:#f5af19><b>        ★ 取引完了レシート ★</b></gradient>"))
        audience.sendMessage(parse("<gray>購入アイテム: <white>$itemName × $amount</white></gray>"))
        audience.sendMessage(parse("<gray>出品者: <yellow>$sellerName</yellow></gray>"))
        audience.sendMessage(parse("<gray>支払金額: <gold>%,.2f %s</gold></gray>".format(totalPrice, currencySymbol)))
        audience.sendMessage(parse("<gray>ステータス: <green>正常完了 (スレッドセーフCAS検証済)</green></gray>"))
        audience.sendMessage(parse(line))
    }
}
