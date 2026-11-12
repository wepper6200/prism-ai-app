package ai.prism.ui

import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.model.VideoModelSettings
import ai.prism.domain.documents.DocumentStatus
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.chat.PickedDocument
import ai.prism.presentation.root.DefaultRootComponent
import ai.prism.presentation.root.RootComponent
import ai.prism.testing.FakeAppUpdater
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testEmbeddingModel
import ai.prism.testing.testImageModel
import ai.prism.testing.testModel
import ai.prism.testing.testRecognizer
import ai.prism.testing.testVoice
import ai.prism.testing.testVision
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.arkivanov.decompose.DecomposeSettings
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Сквозной UI-тест: диалог с (подставной) моделью, экран моделей, навигация и темы
 * в широком (Windows) и узком (телефон) макетах. Скриншоты — в `shared/build/screenshots`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalDecomposeApi::class)
class PrismAppUiTest {

    private val screenshotDir = File(System.getProperty("prism.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    /**
     * Каталог для скриншота экрана моделей: скачанная модель со зрением, модель без скачанного модуля зрения,
     * скачивающаяся и слишком тяжёлая модели.
     */
    private val models = FakeModelRepository(
        entries = listOf(
            ModelEntry(testModel(vision = testVision()), ModelStatus.Downloaded, fitsInMemory = true),
            ModelEntry(
                testModel(id = "gemma-4-e2b-old", name = "Gemma 4 E2B (без зрения)", vision = testVision("gemma-4-e2b-old")),
                ModelStatus.Paused(downloadedBytes = 2_841_481_184, totalBytes = 3_398_849_280, error = null),
                fitsInMemory = true,
                chatReady = true,
            ),
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
            ModelEntry(testImageModel(), ModelStatus.Downloaded, fitsInMemory = true),
            ModelEntry(testEmbeddingModel(), ModelStatus.Downloaded, fitsInMemory = true),
            ModelEntry(
                testImageModel(id = "wan-test", name = "Wan 2.2 TI2V 5B").copy(
                    kind = ModelKind.VIDEO,
                    image = null,
                    video = VideoModelSettings(width = 64, height = 32, frames = 3, fps = 16, steps = 2, imageToVideo = true),
                ),
                ModelStatus.Downloaded,
                fitsInMemory = true,
            ),
        ),
    )

    private fun createApp(app: TestApp = TestApp(models = models)): Pair<TestApp, RootComponent> {
        // Compose UI-тест выполняется вне потока Swing, поэтому проверку главного потока Decompose отключаем.
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle = lifecycle), app.dependencies, Dispatchers.Unconfined)
        lifecycle.resume()
        return app to root
    }

    /** «Фотография» для вложения: закат с солнцем. */
    private fun photo(): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(0x1E3A8A), 0f, 480f, Color(0xF97316))
        g.fillRect(0, 0, 640, 480)
        g.color = Color(0xFDE68A)
        g.fillOval(250, 230, 140, 140)
        g.color = Color(0x0F172A)
        g.fillRect(0, 380, 640, 100)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Скриншот всего окна или одного узла [tag] (диалоги — отдельный корень сцены). */
    private fun ComposeUiTest.screenshot(name: String, tag: String? = null) {
        waitForIdle()
        val node = if (tag == null) onRoot() else onNodeWithTag(tag)
        val image = node.captureToImage().toAwtImage()
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

    @Test
    fun compactChatWithImage() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (_, root) = createApp()
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        val chat = assertIs<RootComponent.Child.Chat>(root.stack.value.active.instance).component
        chat.onIntent(ChatIntent.AttachImages(listOf(photo())))
        waitForIdle()
        onNodeWithTag("attachment_strip").assertIsDisplayed()
        onNodeWithTag("message_input").performTextInput("Что на фото?")
        screenshot("compact_7_draft_with_image")

        onNodeWithTag("send_button").performClick()
        waitForIdle()
        onNodeWithTag("message_images").assertIsDisplayed()
        onNodeWithText("Привет! Я Prism AI.", substring = true).assertIsDisplayed()
        assertEquals(1, chat.state.value.messages.first().images.size)
        screenshot("compact_8_chat_with_image")
    }

    /** У активной модели не скачан модуль зрения: вместо выбора фото — объяснение и переход к моделям. */
    @Test
    fun visionDialogExplainsMissingModule() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (_, root) = createApp()
        models.setActive("gemma-4-e2b-old")
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("attach_image").performClick()
        onNodeWithTag("vision_dialog").assertIsDisplayed()
        screenshot("compact_9_vision_dialog", tag = "vision_dialog")

        onNodeWithTag("vision_open_models").performClick()
        onNodeWithTag("models_screen").assertIsDisplayed()
        onNodeWithTag("download_vision_gemma-4-e2b-old").assertIsDisplayed()
    }

    /** Режим «Нарисовать»: описание отправляется модели изображений, ответ — картинка. */
    @Test
    fun compactChatDrawsPicture() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (app, root) = createApp()
        app.imageGenerator.png = photo()
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("draw_toggle").performClick()
        onNodeWithTag("message_input").performTextInput("a sunset over the sea")
        screenshot("compact_10_draw_mode")
        onNodeWithTag("send_button").performClick()
        waitForIdle()

        onNodeWithTag("generated_image").assertIsDisplayed()
        assertEquals("a sunset over the sea", app.imageGenerator.lastRequest?.prompt)
        screenshot("compact_11_drawn_picture")
    }

    /** Файлы диалога: строка файлов над полем ввода, ответ с источниками. */
    @Test
    fun compactChatWithDocuments() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (_, root) = createApp()
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        val chat = assertIs<RootComponent.Child.Chat>(root.stack.value.active.instance).component
        chat.onIntent(
            ChatIntent.AttachDocuments(
                listOf(
                    PickedDocument("Правила отпусков.pdf", "Отпуск — 28 дней в году.\n---\nЗаявление — за две недели.".encodeToByteArray()),
                    PickedDocument("скан.jpg", byteArrayOf(1, 2, 3)),
                ),
            ),
        )
        waitForIdle()
        onNodeWithTag("document_strip").assertIsDisplayed()
        assertEquals(
            listOf(DocumentStatus.READY, DocumentStatus.FAILED),
            chat.state.value.documents.map { it.status },
        )
        onNodeWithTag("message_input").performTextInput("Сколько дней отпуска?")
        screenshot("compact_12_documents")

        onNodeWithTag("send_button").performClick()
        waitForIdle()
        onNodeWithTag("message_sources").assertIsDisplayed()
        onNodeWithText("Правила отпусков.pdf (с. 1, 2)", substring = true).assertIsDisplayed()
        screenshot("compact_13_answer_with_sources")
    }

    /** Ассистент с папкой проекта: правка файла ждёт разрешения, после — шаги под ответом. */
    @Test
    fun compactAgentAsksBeforeEditing() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (app, root) = createApp()
        val folder = Files.createTempDirectory("prism-ui-project").toFile()
        folder.resolve("main.py").writeText("print('old')\n")
        app.engine.queuedReplies += listOf(
            "Поправлю вывод.\n<tool name=\"edit_file\">\n<path>main.py</path>\n<old>print('old')</old>\n<new>print('new')</new>\n</tool>",
        )
        app.engine.queuedReplies += listOf("Готово: программа теперь печатает new.")
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        val chat = assertIs<RootComponent.Child.Chat>(root.stack.value.active.instance).component
        chat.onIntent(ChatIntent.ChooseWorkspace(folder.path))
        waitForIdle()
        onNodeWithText(folder.name).assertIsDisplayed()
        onNodeWithTag("message_input").performTextInput("Поменяй вывод на new")
        onNodeWithTag("send_button").performClick()
        waitForIdle()

        onNodeWithTag("pending_action").assertIsDisplayed()
        screenshot("compact_14_agent_approval")
        onNodeWithTag("pending_approve").performClick()
        waitForIdle()

        assertEquals("print('new')\n", folder.resolve("main.py").readText())
        onNodeWithTag("agent_steps").assertIsDisplayed()
        screenshot("compact_15_agent_done")
    }

    /** Режим «Видео»: описание уходит модели видео, ответ — проигрываемый ролик. */
    @Test
    fun compactChatMakesVideo() = runDesktopComposeUiTest(width = 412, height = 892) {
        val (app, root) = createApp()
        app.imageGenerator.avi = checkNotNull(javaClass.getResourceAsStream("/three-frames.avi")).readBytes()
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("video_toggle").performClick()
        onNodeWithTag("message_input").performTextInput("a red sunset over the sea")
        onNodeWithTag("send_button").performClick()
        waitForIdle()

        onNodeWithTag("generated_video").assertIsDisplayed()
        assertEquals(3, app.imageGenerator.lastVideoRequest?.frames)
        screenshot("compact_16_video")
    }

    /** Обновление: по кнопке в настройках находится новая версия, затем скачивается и ставится. */
    @Test
    fun compactSettingsInstallsUpdate() = runDesktopComposeUiTest(width = 412, height = 892) {
        val updater = FakeAppUpdater()
        val (_, root) = createApp(TestApp(models = models, updater = updater))
        setContent { PrismApp(root) }

        onNodeWithTag("nav_settings").performClick()
        onNodeWithTag("update_check").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Доступна версия 0.9.0").assertExists()
        onNodeWithTag("update_install").performScrollTo()
        screenshot("compact_19_update_available")

        onNodeWithTag("update_install").performClick()
        waitForIdle()
        assertEquals(listOf("/updates/PrismAI-0.9.0-android-arm64.apk"), updater.installed)
    }

    /** Голос: микрофон диктует текст в поле ввода, кнопка под ответом озвучивает его. */
    @Test
    fun compactVoiceDictatesAndSpeaks() = runDesktopComposeUiTest(width = 412, height = 892) {
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val app = TestApp(
            models = FakeModelRepository(
                entries = listOf(
                    ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
                    ModelEntry(testRecognizer(), ModelStatus.Downloaded, fitsInMemory = true),
                    ModelEntry(testVoice(), ModelStatus.Downloaded, fitsInMemory = true),
                ),
            ),
        )
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle = lifecycle), app.dependencies, Dispatchers.Unconfined)
        lifecycle.resume()
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("conversation_button").assertIsDisplayed()
        onNodeWithTag("mic_button").performClick()
        waitForIdle()
        onNodeWithText("Какая сегодня погода?").assertIsDisplayed()
        screenshot("compact_17_dictation")

        onNodeWithTag("send_button").performClick()
        waitForIdle()
        onNodeWithTag("speak_button").performClick()
        waitForIdle()
        assertEquals(listOf("Привет!", "Я Призм эй-ай."), app.speechEngine.spoken)
        screenshot("compact_18_spoken_answer")
    }

    /** Без модели распознавания речи микрофон объясняет, что скачать. */
    @Test
    fun voiceDialogAsksToDownloadRecognizer() = runDesktopComposeUiTest(width = 412, height = 892) {
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val app = TestApp(
            models = FakeModelRepository(
                entries = listOf(
                    ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
                    ModelEntry(testRecognizer(), ModelStatus.NotDownloaded, fitsInMemory = true),
                    ModelEntry(testVoice(), ModelStatus.NotDownloaded, fitsInMemory = true),
                ),
            ),
        )
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle = lifecycle), app.dependencies, Dispatchers.Unconfined)
        lifecycle.resume()
        setContent { PrismApp(root) }

        onNodeWithTag("new_chat").performClick()
        onNodeWithTag("mic_button").performClick()
        onNodeWithTag("voice_dialog").assertIsDisplayed()
        screenshot("compact_19_voice_dialog", tag = "voice_dialog")
        assertEquals(0, app.audioInput.started)

        onNodeWithTag("voice_open_models").performClick()
        onNodeWithTag("models_screen").assertIsDisplayed()
    }
}
