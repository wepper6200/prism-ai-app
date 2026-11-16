package ai.prism.data.settings

import java.util.prefs.AbstractPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class LongPreferencesTest {
    private class MemoryNode(parent: AbstractPreferences? = null, name: String = "") : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()
        private val children = mutableMapOf<String, MemoryNode>()
        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun removeNodeSpi() { values.clear() }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
        override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { MemoryNode(this, name) }
        override fun syncSpi() = Unit
        override fun flushSpi() = Unit
    }
    @Test
    fun longRussianLyricsAndEmojiSurviveReopeningAndReplacingWithShortDraft() {
        val preferences = MemoryNode()
        val lyrics = "Строка песни 🎵\n".repeat(2400)
        PreferencesKeyValueStore(preferences).putString("chat_drafts", lyrics)
        assertEquals(lyrics, PreferencesKeyValueStore(preferences).getString("chat_drafts"))
        PreferencesKeyValueStore(preferences).putString("chat_drafts", "short")
        assertEquals("short", PreferencesKeyValueStore(preferences).getString("chat_drafts"))
        PreferencesKeyValueStore(preferences).putString("chat_drafts", lyrics + "финал")
        assertEquals(lyrics + "финал", PreferencesKeyValueStore(preferences).getString("chat_drafts"))
    }
    @Test
    fun existingPlainPreferencesStayCompatibleAndMarkerTextCanBeSaved() {
        val preferences = MemoryNode()
        preferences.put("old", "старый черновик")
        val store = PreferencesKeyValueStore(preferences)
        assertEquals("старый черновик", store.getString("old"))
        store.putString("literal", "@prism-chunks-v1:literal")
        assertEquals("@prism-chunks-v1:literal", PreferencesKeyValueStore(preferences).getString("literal"))
    }
}
