package ai.prism.data.settings

/** Простое платформенное хранилище строк «ключ → значение» для настроек. */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)
}
