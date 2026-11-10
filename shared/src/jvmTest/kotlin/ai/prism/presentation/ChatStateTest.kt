package ai.prism.presentation

import ai.prism.presentation.chat.ChatState
import ai.prism.presentation.chat.DrawSupport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStateTest {

    private fun drawing(draft: String, canTranslate: Boolean) = ChatState(
        chatId = "chat",
        draft = draft,
        drawMode = true,
        draw = DrawSupport.READY,
        canTranslateDrawing = canTranslate,
    )

    @Test
    fun russianDescriptionNeedsChatModelForTranslation() {
        val state = drawing("аниме девушка", canTranslate = false)
        assertTrue(state.drawNeedsTranslator)
        assertFalse(state.canSend)
    }

    @Test
    fun englishOrTranslatableDescriptionCanBeSent() {
        assertTrue(drawing("an anime girl", canTranslate = false).canSend)
        assertTrue(drawing("аниме девушка", canTranslate = true).canSend)
        assertFalse(drawing("an anime girl", canTranslate = false).drawNeedsTranslator)
    }

    @Test
    fun videoModeAcceptsPhotoOnlyForCapableModel() {
        val base = ChatState(chatId = "chat", draft = "a cat", videoMode = true, video = DrawSupport.READY)
        assertTrue(base.canSend)
        val photo = ai.prism.domain.model.ImageAttachment("p", "p.jpg", 10, 10)
        assertFalse(base.copy(attachments = listOf(photo)).canSend)
        assertFalse(base.canAttachMore)
        val capable = base.copy(videoFromPhoto = true)
        assertTrue(capable.canAttachMore)
        assertTrue(capable.copy(attachments = listOf(photo)).canSend)
        assertFalse(base.copy(video = DrawSupport.NOT_DOWNLOADED).canSend)
        assertTrue(base.copy(draft = "кот", canTranslateDrawing = false).drawNeedsTranslator)
    }
}
