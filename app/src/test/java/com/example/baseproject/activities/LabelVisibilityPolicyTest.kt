package com.example.baseproject.activities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelVisibilityPolicyTest {
    @Test
    fun entersOnlyWhenRegionIsLargeEnoughOnScreen() {
        assertFalse(LabelVisibilityPolicy.shouldShow(wasVisible = false, screenRadiusPx = 24.9f))
        assertTrue(LabelVisibilityPolicy.shouldShow(wasVisible = false, screenRadiusPx = 25f))
    }

    @Test
    fun keepsVisibleLabelThroughSmallZoomFluctuations() {
        assertTrue(LabelVisibilityPolicy.shouldShow(wasVisible = true, screenRadiusPx = 21f))
        assertFalse(LabelVisibilityPolicy.shouldShow(wasVisible = true, screenRadiusPx = 19.9f))
    }
}
