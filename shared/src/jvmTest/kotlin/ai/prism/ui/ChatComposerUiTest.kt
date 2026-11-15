package ai.prism.ui

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.ui.components.ChatInputBar
import ai.prism.ui.components.InputAttachments
import ai.prism.ui.components.InputDrawing
import ai.prism.ui.components.InputVoice
import ai.prism.ui.components.MusicDurationSelector
import ai.prism.ui.theme.PrismTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatComposerUiTest {
    @Test
    fun phoneInputKeepsReadableWidthWithEveryTool() = checkComposer(320, 480, 1f, "phone_320_composer")

    @Test
    fun landscapeInputKeepsReadableWidthWithLargeText() = checkComposer(720, 360, 2f, "landscape_large_text_composer")

    private fun checkComposer(width: Int, height: Int, fontScale: Float, screenshot: String) = runDesktopComposeUiTest(width = width, height = height) {
        var draft by mutableStateOf("Создай песню на русском о дороге домой.\nТекст песни:\nТихий вечер за окном, мы с тобой идём домой.")
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                PrismTheme(AppSettings(darkMode = DarkMode.DARK)) {
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
                        ChatInputBar(
                            text = draft, onTextChange = { draft = it }, canSend = true, isGenerating = false,
                            onSend = {}, onStop = {},
                            attachments = InputAttachments(emptyList(), false, true, false, { null }, {}, {}, {}),
                            drawing = InputDrawing(false) {}, video = InputDrawing(false) {},
                            music = InputDrawing(true) {}, voice = InputVoice(false, false, 0f, true) {},
                            onPickDocuments = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        val field = onNodeWithTag("message_input").fetchSemanticsNode().boundsInRoot
        val button = onNodeWithTag("send_button").fetchSemanticsNode().boundsInRoot
        assertTrue(field.width >= width - 56, "Поле ввода слишком узкое: ${field.width}")
        assertTrue(button.height >= 40 && button.bottom <= height, "Кнопка отправки сжата или скрыта")
        assertTrue(field.bottom <= button.top, "Кнопки занимают строку текста")
        onNodeWithTag("send_button").assertIsDisplayed()
        onNodeWithTag("mic_button").assertIsDisplayed()
        val dir = File(System.getProperty("prism.screenshotDir")).apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$screenshot.png"))
    }

    @Test
    fun customDurationSupportsMinutesAndSecondsAndTenMinuteLimit() = runDesktopComposeUiTest(width = 360, height = 640) {
        var selected by mutableStateOf(30)
        setContent {
            PrismTheme(AppSettings()) { MusicDurationSelector(selected, true, { selected = it }) }
        }
        onNodeWithTag("music_duration").performClick()
        onNodeWithTag("music_seconds_600").performClick()
        assertEquals(600, selected)
        onNodeWithTag("music_duration").performClick()
        onNodeWithTag("music_duration_custom").performClick()
        onNodeWithTag("music_custom_minutes").performTextReplacement("10")
        onNodeWithTag("music_custom_seconds").performTextReplacement("1")
        onNodeWithTag("music_duration_apply").assertIsNotEnabled()
        onNodeWithTag("music_custom_minutes").performTextReplacement("1")
        onNodeWithTag("music_custom_seconds").performTextReplacement("15")
        val dir = File(System.getProperty("prism.screenshotDir")).apply { mkdirs() }
        ImageIO.write(onNodeWithTag("music_duration_dialog").captureToImage().toAwtImage(), "png", File(dir, "music_custom_duration.png"))
        onNodeWithTag("music_duration_apply").performClick()
        assertEquals(75, selected)
    }
}
