package ai.prism.service

import ai.prism.domain.model.VideoModelSettings
import ai.prism.service.video.VideoPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoPlanTest {

    /** Wan 2.2 TI2V: 24 кадра в секунду, до 121 кадра (5 с) за раз, длиннее — продолжением от последнего кадра. */
    private val wan22 = VideoModelSettings(frames = 49, fps = 24, imageToVideo = true, maxFrames = 121, durations = listOf(2, 3, 5, 10, 15))

    @Test
    fun shortVideosAreOneSegmentOfFourKPlusOneFrames() {
        assertEquals(listOf(49), VideoPlan.segments(wan22, 2))
        assertEquals(listOf(73), VideoPlan.segments(wan22, 3))
        assertEquals(listOf(121), VideoPlan.segments(wan22, 5))
    }

    @Test
    fun longVideosAreChainedSegments() {
        val ten = VideoPlan.segments(wan22, 10)
        assertEquals(listOf(121, 121), ten)
        assertEquals(241, VideoPlan.totalFrames(ten))
        assertEquals(listOf(121, 121, 121), VideoPlan.segments(wan22, 15))
        val seven = VideoPlan.segments(wan22, 7)
        assertEquals(listOf(85, 85), seven)
        assertEquals(169, VideoPlan.totalFrames(seven))
    }

    @Test
    fun modelWithoutImageToVideoIsCappedToOneSegment() {
        val wan21 = VideoModelSettings(frames = 17, fps = 16, imageToVideo = false, maxFrames = 33, durations = listOf(1, 2))
        assertEquals(listOf(17), VideoPlan.segments(wan21, 1))
        assertEquals(listOf(33), VideoPlan.segments(wan21, 2))
        assertEquals(listOf(33), VideoPlan.segments(wan21, 10))
        assertEquals(listOf(1, 2), wan21.availableDurations)
    }

    @Test
    fun ltxUsesEightKPlusOneFrames() {
        val ltx = VideoModelSettings(frames = 121, fps = 24, frameStep = 8, imageToVideo = true, maxFrames = 121, durations = listOf(3, 5, 10))
        assertEquals(listOf(73), VideoPlan.segments(ltx, 3))
        assertEquals(listOf(121, 121), VideoPlan.segments(ltx, 10))
    }

    @Test
    fun withoutDurationsTheCatalogFrameCountIsUsed() {
        val legacy = VideoModelSettings(frames = 3, fps = 16, imageToVideo = true)
        assertEquals(listOf(3), VideoPlan.segments(legacy, 5))
    }
}
