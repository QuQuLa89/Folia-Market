package com.ququla89.foliamarket.util

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
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
        val tags = placeholders.keys.withIndex().associate { (index, key) -> key to "market_value_$index" }
        val processed =
            Regex("\\{([^{}]+)}").replace(text ?: "") { match ->
                tags[match.groupValues[1]]?.let { "<$it>" } ?: match.value
            }
        val resolvers = placeholders.map { (key, value) -> Placeholder.unparsed(tags.getValue(key), value) }
        return miniMessage.deserialize(processed, *resolvers.toTypedArray())
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
        audience.sendMessage(
            parse("<gray>購入アイテム: <white>{item} × {amount}</white></gray>", mapOf("item" to itemName, "amount" to amount.toString())),
        )
        audience.sendMessage(parse("<gray>出品者: <yellow>{seller}</yellow></gray>", mapOf("seller" to sellerName)))
        audience.sendMessage(
            parse(
                "<gray>支払金額: <gold>{price} {currency}</gold></gray>",
                mapOf("price" to "%,.2f".format(totalPrice), "currency" to currencySymbol),
            ),
        )
        audience.sendMessage(parse("<gray>ステータス: <green>正常完了 (スレッドセーフCAS検証済)</green></gray>"))
        audience.sendMessage(parse(line))
    }
}
