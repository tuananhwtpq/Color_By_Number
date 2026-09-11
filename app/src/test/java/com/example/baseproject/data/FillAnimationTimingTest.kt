package com.pixlory.color.by.number.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FillAnimationTimingTest {
    @Test
    fun durationGrowsWithVisibleScreenDistanceWithoutAHardMaximum() {
        assertEquals(220f, FillAnimationTiming.durationMs(0f), 0.001f)
        assertEquals(420f, FillAnimationTiming.durationMs(400f), 0.001f)
        assertEquals(620f, FillAnimationTiming.durationMs(800f), 0.001f)
        assertEquals(5_220f, FillAnimationTiming.durationMs(10_000f), 0.001f)
    }

    @Test
    fun radiusProgressMovesAtAConstantPerceptibleSpeed() {
        assertEquals(0f, FillAnimationTiming.easedProgress(0f, 200f), 0.001f)
        assertEquals(0.25f, FillAnimationTiming.easedProgress(50f, 200f), 0.001f)
        assertEquals(0.5f, FillAnimationTiming.easedProgress(100f, 200f), 0.001f)
        assertEquals(1f, FillAnimationTiming.easedProgress(200f, 200f), 0.001f)
        assertEquals(1f, FillAnimationTiming.easedProgress(300f, 200f), 0.001f)
    }

    @Test
    fun aDroppedFrameCannotSkipMoreThanTwoNormalFramesOfAnimation() {
        assertEquals(0f, FillAnimationTiming.animationStepMs(-10f), 0.001f)
        assertEquals(16.67f, FillAnimationTiming.animationStepMs(16.67f), 0.001f)
        assertEquals(34f, FillAnimationTiming.animationStepMs(100f), 0.001f)
    }
}
