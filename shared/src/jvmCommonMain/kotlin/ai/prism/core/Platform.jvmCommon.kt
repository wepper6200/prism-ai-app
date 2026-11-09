package ai.prism.core

import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun newId(): String = UUID.randomUUID().toString()
