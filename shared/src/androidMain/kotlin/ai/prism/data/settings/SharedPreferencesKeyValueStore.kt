package ai.prism.data.settings

import android.content.Context

internal class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {

    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    private companion object {
        const val FILE_NAME = "prism_settings"
    }
}
