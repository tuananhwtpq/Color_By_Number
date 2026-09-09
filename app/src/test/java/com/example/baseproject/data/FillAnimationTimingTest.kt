package com.example.baseproject.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillAnimationTimingTest {
    @Test
    fun durationIsShortAndBoundedByRegionSize() {
        assertEquals(140f, FillAnimationTiming.durationMs(0), 0.001f)
        assertTrue(FillAnimationTiming.durationMs(10_000) > FillAnimationTiming.durationMs(100))
        assertEquals(220f, FillAnimationTiming.durationMs(1_000_000), 0.001f)
    }

    @Test
    fun progressUsesEaseOutAndFinishesAtOne() {
        assertEquals(0f, FillAnimationTiming.easedProgress(0f, 200f), 0.001f)
        assertTrue(FillAnimationTiming.easedProgress(50f, 200f) > 0.25f)
        assertEquals(1f, FillAnimationTiming.easedProgress(200f, 200f), 0.001f)
        assertEquals(1f, FillAnimationTiming.easedProgress(300f, 200f), 0.001f)
    }
}
