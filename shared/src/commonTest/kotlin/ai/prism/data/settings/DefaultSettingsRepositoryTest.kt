package ai.prism.data.settings

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.TextEncoderMode
import ai.prism.domain.model.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class MapKeyValueStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class DefaultSettingsRepositoryTest {

    @Test
    fun emptyStoreGivesDefaults() {
        val repository = DefaultSettingsRepository(MapKeyValueStore())
        assertEquals(AppSettings(), repository.settings.value)
    }

    @Test
    fun settingsSurviveRestart() {
        val store = MapKeyValueStore()
        DefaultSettingsRepository(store).apply {
            setThemeStyle(ThemeStyle.LIQUID_GLASS)
            setDarkMode(DarkMode.DARK)
            setDynamicColor(false)
            setSafeMode(true)
            setTextEncoderMode(TextEncoderMode.Q4)
        }

        val restarted = DefaultSettingsRepository(store)

        assertEquals(
            AppSettings(
                themeStyle = ThemeStyle.LIQUID_GLASS,
                darkMode = DarkMode.DARK,
                dynamicColor = false,
                safeMode = true,
                textEncoderMode = TextEncoderMode.Q4,
            ),
            restarted.settings.value,
        )
    }

    @Test
    fun corruptedValuesFallBackToDefaults() {
        val store = MapKeyValueStore().apply {
            values["theme_style"] = "NEON"
            values["dark_mode"] = ""
            values["dynamic_color"] = "maybe"
            values["text_encoder_mode"] = "Q2"
        }
        assertEquals(AppSettings(), DefaultSettingsRepository(store).settings.value)
    }
}
