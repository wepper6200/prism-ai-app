package ai.prism.domain.model

/**
 * Краткое содержание начала длинного диалога: в промпт идёт оно, а не сами старые сообщения.
 * [untilMessageId] — последнее сообщение, которое оно охватывает.
 */
data class ChatDigest(val text: String, val untilMessageId: String)

/** Факт о пользователе, который ассистент помнит во всех диалогах (хранится только на устройстве). */
data class UserMemory(val id: String, val text: String, val createdAt: Long)
