package ai.prism.core

/** Текущее время, мс с начала эпохи. */
expect fun currentTimeMillis(): Long

/** Случайный уникальный идентификатор (UUID). */
expect fun newId(): String

/** Сегодняшняя дата на устройстве словами и в ISO 8601: «среда, 11 ноября 2026 г. (2026-11-11)». */
expect fun todayText(): String
