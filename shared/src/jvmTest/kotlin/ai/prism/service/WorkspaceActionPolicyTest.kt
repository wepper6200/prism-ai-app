package ai.prism.service

import ai.prism.service.agent.WorkspaceActionPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceActionPolicyTest {
    @Test
    fun originalRussianAndEnglishInstructionsAreRecognized() {
        for (request in listOf("Создай a.txt", "Пожалуйста исправь баг", "Можешь исправить файл?", "Добавь траву как в примере", "сделай правку в самом файле", "Please edit main.py")) {
            assertTrue(WorkspaceActionPolicy.requiresWrite(request), request)
        }
        for (request in listOf("Как создать a.txt?", "Покажи, как исправить файл", "Не меняй файл, напиши план", "Explain how to edit a.txt", "> Создай a.txt", "Посмотри код:\n```\nсоздай файл\n```")) {
            assertFalse(WorkspaceActionPolicy.requiresWrite(request), request)
        }
    }

    @Test
    fun continuationInheritsOnlyThePreviousUserTask() {
        assertTrue(WorkspaceActionPolicy.requiresWrite("Продолжай", listOf("Создай a.txt", "давай")))
        assertFalse(WorkspaceActionPolicy.requiresWrite("Продолжай", listOf("Как создать a.txt?")))
        assertFalse(WorkspaceActionPolicy.requiresWrite("Объясни код", listOf("Создай a.txt")))
        assertFalse(WorkspaceActionPolicy.requiresWrite("Продолжай"))
    }
}
