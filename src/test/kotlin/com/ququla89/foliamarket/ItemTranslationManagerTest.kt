package com.ququla89.foliamarket

import com.ququla89.foliamarket.util.ItemTranslationManager
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ItemTranslationManagerTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            ItemTranslationManager.load()
        }
    }

    @Test
    fun testDictionaryLoaded() {
        assertTrue(ItemTranslationManager.isLoaded())
        assertTrue(ItemTranslationManager.size() > 1000, "Dictionary should have thousands of entries")
    }

    @Test
    fun testGetJapaneseName() {
        val diamondSword = ItemTranslationManager.getJapaneseName(Material.DIAMOND_SWORD)
        assertEquals("ダイヤモンドの剣", diamondSword)

        val stone = ItemTranslationManager.getJapaneseName(Material.STONE)
        assertEquals("石", stone)

        val goldenApple = ItemTranslationManager.getJapaneseName(Material.GOLDEN_APPLE)
        assertEquals("金のリンゴ", goldenApple)

        val bow = ItemTranslationManager.getJapaneseName(Material.BOW)
        assertEquals("弓", bow)
    }

    @Test
    fun testBuildSearchIndex() {
        val defaultIndex = ItemTranslationManager.buildSearchIndex(Material.DIAMOND_SWORD)
        assertTrue(defaultIndex.contains("ダイヤモンドの剣"))
        assertTrue(defaultIndex.contains("DIAMOND_SWORD"))
        assertTrue(defaultIndex.contains("diamond sword"))

        val customIndex = ItemTranslationManager.buildSearchIndex(Material.DIAMOND_SWORD, "勇者の剣")
        assertTrue(customIndex.contains("勇者の剣"))
        assertTrue(customIndex.contains("ダイヤモンドの剣"))
        assertTrue(customIndex.contains("DIAMOND_SWORD"))
    }

    @Test
    fun testGetDisplayName() {
        val defaultName = ItemTranslationManager.getDisplayName(Material.DIAMOND_SWORD)
        assertEquals("ダイヤモンドの剣", defaultName)

        val customName = ItemTranslationManager.getDisplayName(Material.DIAMOND_SWORD, "カスタム剣")
        assertEquals("カスタム剣", customName)
    }
}
