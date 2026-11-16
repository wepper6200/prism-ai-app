package ai.prism.data.settings

import java.util.UUID
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

/** Desktop preferences; long drafts use durable chunks with an atomic root pointer. */
internal class PreferencesKeyValueStore(
    private val preferences: Preferences = Preferences.userRoot().node(NODE),
) : KeyValueStore {
    @Synchronized
    override fun getString(key: String): String? {
        val value = preferences.get(key, null) ?: return null
        if (!value.startsWith(PREFIX)) return value
        val id = value.removePrefix(PREFIX)
        return try {
            if (!preferences.nodeExists("$CHUNKS/$id")) return null
            val node = preferences.node("$CHUNKS/$id")
            val count = node.getInt("count", -1).takeIf { it in 1..100_000 } ?: return null
            buildString { repeat(count) { append(node.get(it.toString(), null) ?: return null) } }
        } catch (_: BackingStoreException) { null }
    }

    @Synchronized
    override fun putString(key: String, value: String) {
        val previous = preferences.get(key, null)?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
        try {
            if (value.length > Preferences.MAX_VALUE_LENGTH || value.startsWith(PREFIX)) {
                val id = UUID.randomUUID().toString()
                val node = preferences.node("$CHUNKS/$id")
                var start = 0
                var count = 0
                do {
                    var end = (start + Preferences.MAX_VALUE_LENGTH - 2).coerceAtMost(value.length)
                    if (end < value.length && value[end - 1].isHighSurrogate()) end--
                    node.put((count++).toString(), value.substring(start, end))
                    start = end
                } while (start < value.length)
                node.putInt("count", count)
                node.flush() // Commit every chunk before publishing the pointer.
                preferences.put(key, PREFIX + id)
            } else preferences.put(key, value)
            preferences.flush()
            // Only remove the old snapshot after the new root value was flushed successfully.
            if (previous != null && preferences.nodeExists("$CHUNKS/$previous")) {
                preferences.node("$CHUNKS/$previous").removeNode()
                preferences.flush()
            }
        } catch (e: BackingStoreException) {
            System.err.println("Prism AI: не удалось сохранить настройку «$key»: " + e.message)
        }
    }
    private companion object {
        const val NODE = "ai/prism/settings"
        const val CHUNKS = "large-values-v1"
        const val PREFIX = "@prism-chunks-v1:"
    }
}
