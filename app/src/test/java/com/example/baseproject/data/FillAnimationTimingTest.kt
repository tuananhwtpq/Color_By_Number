package com.pixlory.color.by.number.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillAnimationTimingTest {
    @Test
    fun durationIsShortAndBoundedByScreenSpaceRadius() {
        assertEquals(120f, FillAnimationTiming.durationMs(0f), 0.001f)
        assertTrue(FillAnimationTiming.durationMs(800f) > FillAnimationTiming.durationMs(80f))
        assertEquals(260f, FillAnimationTiming.durationMs(10_000f), 0.001f)
    }

    @Test
    fun progressUsesEaseOutAndFinishesAtOne() {
        assertEquals(0f, FillAnimationTiming.easedProgress(0f, 200f), 0.001f)
        assertTrue(FillAnimationTiming.easedProgress(50f, 200f) > 0.25f)
        assertEquals(1f, FillAnimationTiming.easedProgress(200f, 200f), 0.001f)
        assertEquals(1f, FillAnimationTiming.easedProgress(300f, 200f), 0.001f)
    }
}
