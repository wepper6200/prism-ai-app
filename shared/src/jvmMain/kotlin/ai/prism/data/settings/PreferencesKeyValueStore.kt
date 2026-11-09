package ai.prism.data.settings

import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

/**
 * Настройки Desktop в `java.util.prefs` (на Windows — ветка реестра текущего пользователя).
 * Если хранилище недоступно, значение остаётся в памяти до конца сеанса.
 */
internal class PreferencesKeyValueStore(
    private val preferences: Preferences = Preferences.userRoot().node(NODE),
) : KeyValueStore {

    override fun getString(key: String): String? = preferences.get(key, null)

    override fun putString(key: String, value: String) {
        preferences.put(key, value)
        try {
            preferences.flush()
        } catch (e: BackingStoreException) {
            System.err.println("Prism AI: не удалось сохранить настройку «$key»: ${e.message}")
        }
    }

    private companion object {
        const val NODE = "ai/prism/settings"
    }
}
