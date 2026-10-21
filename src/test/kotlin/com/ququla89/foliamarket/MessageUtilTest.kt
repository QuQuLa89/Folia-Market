package com.ququla89.foliamarket

import com.ququla89.foliamarket.util.MessageUtil
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageUtilTest {
    private val hostileName = "</white><click:run_command:'/op QuQuLa89'><red>商品</red></click><hover:show_text:'偽装'>!"

    @Test
    fun `dynamic placeholders stay literal and keep template formatting`() {
        val component = MessageUtil.parse("<green>{item} {item} {unknown}</green>", mapOf("item" to hostileName))
        assertEquals("$hostileName $hostileName {unknown}", MessageUtil.toPlainText(component))
        assertEquals(NamedTextColor.GREEN, component.color())
        assertNoEvents(component)
    }

    @Test
    fun `placeholder values are never expanded as other placeholders or resolver tags`() {
        val value = "{currency}<market_value_1><reset>\\<red>"
        val component = MessageUtil.parse("{item} {currency}", mapOf("item" to value, "currency" to "円"))
        assertEquals("$value 円", MessageUtil.toPlainText(component))
        assertNoEvents(component)
        assertEquals("", MessageUtil.toPlainText(MessageUtil.parse(null)))
    }

    @Test
    fun `receipt treats item seller and currency as plain text`() {
        val messages = mutableListOf<Component>()
        val audience = mock(Audience::class.java)
        doAnswer {
            messages += it.getArgument<Component>(0)
            null
        }.`when`(audience).sendMessage(org.mockito.ArgumentMatchers.any(Component::class.java))
        MessageUtil.sendReceipt(audience, hostileName, 2, 100.0, hostileName, hostileName)
        assertTrue(messages.any { MessageUtil.toPlainText(it).contains("$hostileName × 2") })
        assertEquals(3, messages.count { MessageUtil.toPlainText(it).contains(hostileName) })
        messages.forEach(::assertNoEvents)
    }

    private fun assertNoEvents(component: Component) {
        assertNull(component.clickEvent())
        assertNull(component.hoverEvent())
        component.children().forEach(::assertNoEvents)
    }
}
