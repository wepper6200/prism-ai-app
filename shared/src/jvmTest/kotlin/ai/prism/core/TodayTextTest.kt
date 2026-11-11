package ai.prism.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class TodayTextTest {

    @Test
    fun todayIncludesIsoDateAndYear() {
        val text = todayText()
        val today = LocalDate.now()
        assertTrue(text.endsWith("($today)"), text)
        assertTrue(today.year.toString() in text.substringBefore(" ("), text)
    }
}
