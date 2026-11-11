package ai.prism.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun newId(): String = UUID.randomUUID().toString()

actual fun todayText(): String {
    val today = LocalDate.now()
    return today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) + " (" + today + ")"
}
