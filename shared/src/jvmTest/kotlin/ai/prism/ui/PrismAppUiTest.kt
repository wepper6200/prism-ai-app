package ai.prism.ui

import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.ThemeStyle
import ai.prism.presentation.root.DefaultRootComponent
import ai.prism.presentation.root.RootComponent
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testModel
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
 * Сквозной UI-тест: диалог с (подставной) моделью, экран моделей, навигация и темы
 * в широком (Windows) и узком (телефон) макетах. Скриншоты — в `shared/build/screenshots`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalDecomposeApi::class)
class PrismAppUiTest {

    private val screenshotDir = File(System.getProperty("prism.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    /** Каталог для скриншота экрана моделей: скачанная, скачивающаяся и слишком тяжёлая модели. */
    private val models = FakeModelRepository(
        entries = listOf(
            ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
            ModelEntry(
                testModel(id = "gemma-4-e4b", name = "Gemma 4 E4B", minRamMb = 8192, recommendedFor = listOf("desktop")),
                ModelStatus.Downloading(downloadedBytes = 1_900_000_000, totalBytes = 4_590_807_392),
                fitsInMemory = true,
            ),
            ModelEntry(
                testModel(id = "gemma-4-26b", name = "Gemma 4 26B A4B", minRamMb = 24576, recommendedFor = emptyList()),
                ModelStatus.NotDownloaded,
                fitsInMemory = false,
            ),
        ),
    )

    private fun createApp(): Pair<TestApp, RootComponent> {
        // Compose UI-тест выполняется вне потока Swing, поэтому проверку главного потока Decompose отключаем.
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val app = TestApp(models = models)
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle = lifecycle), app.dependencies, Dispatchers.Unconfined)
        lifecycle.resume()
        return app to root
    }

    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(screenshotDir, "$name.png"))
    }

    private fun ComposeUiTest.sendMessage(text: String) {
        onNodeWithTag("message_input").performTextInput(text)
        onNodeWithTag("send_button").performClick()
        waitForIdle()
    }

    @Test
    fun wideLayoutChatsWithModelAndSwitchesThemes() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val (app, root) = createApp()
        setContent { PrismApp(root) }

        onNodeWithTag("welcome").assertIsDisplayed()
        screenshot("wide_1_expressive_light_welcome")

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        sendMessage("Привет! Кто ты?")
        onNodeWithText("Привет! Я Prism AI.", substring = true).assertIsDisplayed()
        assertEquals("Привет! Кто ты?", root.chatList.state.value.chats.single().title)
        screenshot("wide_2_expressive_light_chat")

        onNodeWithTag("nav_models").performClick()
        onNodeWithTag("models_screen").assertIsDisplayed()
        screenshot("wide_3_expressive_light_models")

        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("theme_style_LIQUID_GLASS").performClick()
        assertEquals(ThemeStyle.LIQUID_GLASS, app.settings.settings.value.themeStyle)
        onNodeWithTag("dark_mode_DARK").performClick()
        assertEquals(DarkMode.DARK, app.settings.settings.value.darkMode)
        screenshot("wide_4_glass_dark_settings")

        val chatId = root.chatList.state.value.chats.single().id
        onNodeWithTag("chat_item_$chatId").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        screenshot("wide_5_glass_dark_chat")

        onNodeWithTag("nav_models").performClick()
        screenshot("wide_6_glass_dark_models")
    }

    @Test
    fun compactLayoutUsesSeparateScreens() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (app, root) = createApp()
        setContent { PrismApp(root) }

        onNodeWithTag("welcome").assertDoesNotExist()
        screenshot("compact_1_expressive_light_empty")

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        screenshot("compact_2_expressive_light_new_chat")
        sendMessage("Расскажи о себе")
        onNodeWithText("Привет! Я Prism AI.", substring = true).assertIsDisplayed()
        screenshot("compact_3_expressive_light_chat")

        onNodeWithTag("back").performClick()
        val chatId = root.chatList.state.value.chats.single().id
        onNodeWithTag("chat_item_$chatId").assertIsDisplayed()

        onNodeWithTag("nav_models").performClick()
        onNodeWithTag("models_screen").assertIsDisplayed()
        screenshot("compact_4_expressive_light_models")
        onNodeWithTag("back").performClick()

        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("theme_style_LIQUID_GLASS").performClick()
        onNodeWithTag("dark_mode_DARK").performClick()
        assertEquals(ThemeStyle.LIQUID_GLASS, app.settings.settings.value.themeStyle)
        screenshot("compact_5_glass_dark_settings")

        onNodeWithTag("back").performClick()
        onNodeWithTag("chat_item_$chatId").performClick()
        onNodeWithTag("chat_screen").assertIsDisplayed()
        screenshot("compact_6_glass_dark_chat")
    }
}
