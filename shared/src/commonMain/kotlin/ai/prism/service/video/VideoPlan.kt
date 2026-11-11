package ai.prism.service.video

import ai.prism.domain.model.VideoModelSettings
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Как снять ролик нужной длины: число кадров каждого куска. Первый кусок снимается по описанию
 * (или по фото), следующие — продолжением от последнего кадра предыдущего; этот кадр в склейке
 * не повторяется. Кадров в куске — `frameStep·k + 1`, не больше `maxFrames`.
 */
object VideoPlan {

    fun segments(settings: VideoModelSettings, seconds: Int): List<Int> {
        // Длительность у модели не выбирается — снимается столько кадров, сколько задано в каталоге.
        if (settings.durations.isEmpty()) return listOf(settings.frames)
        val step = settings.frameStep.coerceAtLeast(1)
        val maxFrames = align(settings.maxFrames - 1, step, roundUp = false) + 1
        val wanted = align((seconds.coerceAtLeast(1) * settings.fps), step, roundUp = true) + 1
        if (wanted <= maxFrames || !settings.imageToVideo) return listOf(wanted.coerceAtMost(maxFrames))
        // Каждый следующий кусок добавляет (кадров − 1) новых кадров.
        val newPerSegment = maxFrames - 1
        val count = ceil((wanted - 1).toDouble() / newPerSegment).toInt()
        val perSegment = align(((wanted - 1).toDouble() / count).roundToInt(), step, roundUp = true) + 1
        return List(count) { perSegment.coerceAtMost(maxFrames) }
    }

    /** Кадров в склеенном ролике. */
    fun totalFrames(segments: List<Int>): Int =
        if (segments.isEmpty()) 0 else segments.first() + segments.drop(1).sumOf { it - 1 }

    /** Ближайшее кратное [step] (вверх или вниз), не меньше [step]. */
    private fun align(value: Int, step: Int, roundUp: Boolean): Int {
        val units = if (roundUp) (value + step - 1) / step else value / step
        return units.coerceAtLeast(1) * step
    }
}
