package com.ququla89.foliamarket.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for mapping vanilla items to official Japanese translation names
 * and building unified search indexes.
 */
object ItemTranslationManager {
    private val translations = ConcurrentHashMap<String, String>()

    @Volatile
    private var isLoaded = false

    /**
     * Loads the Japanese translations from the bundled resource file.
     */
    fun load(classLoader: ClassLoader = ItemTranslationManager::class.java.classLoader) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                val stream =
                    classLoader.getResourceAsStream("lang/ja_jp.json")
                        ?: ItemTranslationManager::class.java.getResourceAsStream("/lang/ja_jp.json")
                        ?: Thread.currentThread().contextClassLoader.getResourceAsStream("lang/ja_jp.json")
                if (stream != null) {
                    InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                        val type = object : TypeToken<Map<String, String>>() {}.type
                        val map: Map<String, String>? = Gson().fromJson(reader, type)
                        if (map != null) {
                            translations.putAll(map)
                        }
                    }
                }
            } catch (e: Exception) {
                // If loading fails, fallback gracefully to material names
            } finally {
                isLoaded = true
            }
        }
    }

    private fun ensureLoaded() {
        if (!isLoaded) {
            load()
        }
    }

    /**
     * Returns true if translations have been loaded into memory.
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * Number of loaded translation entries.
     */
    fun size(): Int {
        ensureLoaded()
        return translations.size
    }

    /**
     * Retrieves the official Japanese translation for the given material.
     */
    fun getJapaneseName(material: Material): String? {
        ensureLoaded()

        // 1. Try material.translationKey() if available (works on running Bukkit/Paper server)
        val directKey = runCatching { material.translationKey() }.getOrNull()
        if (!directKey.isNullOrBlank()) {
            translations[directKey]?.let { return it }
            // Try swap between block and item if direct key not found
            if (directKey.startsWith("block.minecraft.")) {
                translations["item.minecraft." + directKey.removePrefix("block.minecraft.")]?.let { return it }
            } else if (directKey.startsWith("item.minecraft.")) {
                translations["block.minecraft." + directKey.removePrefix("item.minecraft.")]?.let { return it }
            }
        }

        // 2. Fallback key derived from material.name (works in offline/test environments)
        val nameLower = material.name.lowercase()
        val itemKey = "item.minecraft.$nameLower"
        translations[itemKey]?.let { return it }

        val blockKey = "block.minecraft.$nameLower"
        translations[blockKey]?.let { return it }

        return null
    }

    /**
     * Returns a human-friendly display name:
     * - Custom display name (if present)
     * - Official Japanese translation (if available)
     * - Formatted English material name (fallback)
     */
    fun getDisplayName(
        material: Material,
        customName: String? = null,
    ): String {
        if (!customName.isNullOrBlank()) {
            return customName
        }
        val jaName = getJapaneseName(material)
        if (!jaName.isNullOrBlank()) {
            return jaName
        }
        return material.name.lowercase().replace('_', ' ')
    }

    /**
     * Returns a human-friendly display name for an ItemStack.
     */
    fun getDisplayName(item: ItemStack): String {
        val customName =
            runCatching {
                val meta = item.itemMeta
                if (meta != null && meta.hasDisplayName()) {
                    meta.displayName()?.let { PlainTextComponentSerializer.plainText().serialize(it).trim() }
                } else {
                    null
                }
            }.getOrNull()

        return getDisplayName(item.type, customName)
    }

    /**
     * Builds a comprehensive search index combining:
     * - Custom display name (if present)
     * - Official Japanese name
     * - Material enum name (e.g. DIAMOND_SWORD)
     * - Formatted English names (e.g. DIAMOND SWORD, diamond sword)
     * Duplicate entries are omitted.
     */
    fun buildSearchIndex(
        material: Material,
        customName: String? = null,
    ): String {
        val parts = LinkedHashSet<String>()

        // 1. Custom display name (plain text)
        if (!customName.isNullOrBlank()) {
            parts.add(customName.trim())
        }

        // 2. Official Japanese name
        val jaName = getJapaneseName(material)
        if (!jaName.isNullOrBlank()) {
            parts.add(jaName.trim())
        }

        // 3. Enum name
        parts.add(material.name)

        // 4. English name with underscores replaced by spaces
        val spacedName = material.name.replace('_', ' ')
        parts.add(spacedName)
        parts.add(spacedName.lowercase())

        return parts.joinToString(" ")
    }

    /**
     * Builds a comprehensive search index for an ItemStack.
     */
    fun buildSearchIndex(item: ItemStack): String {
        val customName =
            runCatching {
                val meta = item.itemMeta
                if (meta != null && meta.hasDisplayName()) {
                    meta.displayName()?.let { PlainTextComponentSerializer.plainText().serialize(it).trim() }
                } else {
                    null
                }
            }.getOrNull()

        return buildSearchIndex(item.type, customName)
    }
}
