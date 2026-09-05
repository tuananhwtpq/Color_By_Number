package com.example.baseproject.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FillRegionCollectorTest {
    @Test
    fun collectsOnlyConnectedMaskPixels() {
        val width = 5
        val height = 3
        val maskColor = 0x000001

        val maskPixels = intArrayOf(
            0, 0, 0, 0, 0,
            0, maskColor, maskColor, maskColor, 0,
            0, 0, 0, 0, 0,
        )

        val result = FillRegionCollector.collect(
            maskPixels = maskPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            startX = 1,
            startY = 1,
            expectedRegionArea = 1,
        )

        assertEquals(result.indices.size, result.indices.toSet().size)
        assertEquals(listOf(6, 7, 8), result.indices.sorted())
        assertFalse(maskPixels.any { it == maskColor.inv() })
    }
}
