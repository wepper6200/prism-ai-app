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
}
