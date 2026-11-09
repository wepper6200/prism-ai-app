package ai.prism.core

/** Текущее время, мс с начала эпохи. */
expect fun currentTimeMillis(): Long

/** Случайный уникальный идентификатор (UUID). */
expect fun newId(): String
