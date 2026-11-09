package ai.prism.ui

import ai.prism.data.chat.InMemoryChatRepository
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.repository.SettingsRepository
import ai.prism.presentation.root.DefaultRootComponent
import ai.prism.presentation.root.RootComponent
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.arkivanov.decompose.DecomposeSettings
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Сквозной UI-тест: навигация и переключение тем в широком (Windows) и узком (телефон) макетах.
 * Попутно сохраняет скриншоты экранов в `shared/build/screenshots`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalDecomposeApi::class)
class PrismAppUiTest {

    private val screenshotDir = File(System.getProperty("prism.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private class Harness(val root: RootComponent, val settings: SettingsRepository)

    private fun createHarness(): Harness {
        // Compose UI-тест выполняется вне потока Swing, поэтому проверку главного потока Decompose отключаем.
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val settings = DefaultSettingsRepository(MapKeyValueStore())
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            chatRepository = InMemoryChatRepository(),
            settingsRepository = settings,
            capabilities = PlatformCapabilities(supportsDynamicColor = false),
            mainContext = Dispatchers.Unconfined,
        )
        lifecycle.resume()
        return Harness(root, settings)
    }

    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(screenshotDir, "$name.png"))
    }

    @Test
    fun wideLayoutShowsSidebarAndSwitchesThemes() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val harness = createHarness()
        setContent { PrismApp(harness.root) }

        onNodeWithTag("welcome").assertIsDisplayed()
        screenshot("wide_1_expressive_light_welcome")

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        onNodeWithTag("message_input").performTextInput("Привет, Prism!")
        screenshot("wide_2_expressive_light_chat")

        onNodeWithTag("nav_models").performClick()
        onNodeWithTag("models_screen").assertIsDisplayed()

        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("settings_screen").assertIsDisplayed()
        screenshot("wide_3_expressive_light_settings")

        onNodeWithTag("theme_style_LIQUID_GLASS").performClick()
        assertEquals(ThemeStyle.LIQUID_GLASS, harness.settings.settings.value.themeStyle)
        screenshot("wide_4_glass_light_settings")

        onNodeWithTag("dark_mode_DARK").performClick()
        assertEquals(DarkMode.DARK, harness.settings.settings.value.darkMode)
        screenshot("wide_5_glass_dark_settings")

        onNodeWithTag("chat_item_chat-1").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        screenshot("wide_6_glass_dark_chat")

        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("theme_style_EXPRESSIVE").performClick()
        assertEquals(ThemeStyle.EXPRESSIVE, harness.settings.settings.value.themeStyle)
        screenshot("wide_7_expressive_dark_settings")
    }

    @Test
    fun compactLayoutUsesSeparateScreens() = runDesktopComposeUiTest(width = 412, height = 892) {
        val harness = createHarness()
        setContent { PrismApp(harness.root) }

        onNodeWithTag("welcome").assertDoesNotExist()
        onNodeWithTag("new_chat").assertIsDisplayed()
        screenshot("compact_1_expressive_light_empty")

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        screenshot("compact_2_expressive_light_chat")

        onNodeWithTag("back").performClick()
        onNodeWithTag("chat_item_chat-1").assertIsDisplayed()
        screenshot("compact_3_expressive_light_chats")

        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("settings_screen").assertIsDisplayed()
        onNodeWithTag("theme_style_LIQUID_GLASS").performClick()
        screenshot("compact_4_glass_light_settings")

        onNodeWithTag("dark_mode_DARK").performClick()
        screenshot("compact_5_glass_dark_settings")

        onNodeWithTag("back").performClick()
        onNodeWithTag("chat_item_chat-1").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        screenshot("compact_6_glass_dark_chat")
    }
}
