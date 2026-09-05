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

    @Test
    fun coverageExpansionAddsOnlyConnectedCoverageBand() {
        val width = 5
        val height = 5
        val maskColor = 0x000001
        val otherColor = 0x000002

        val maskPixels = intArrayOf(
            0, 0, 0, 0, 0,
            0, maskColor, maskColor, 0, 0,
            0, maskColor, maskColor, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
        )
        val coveragePixels = intArrayOf(
            0, 0, 0, 0, 0,
            0, 0, 0, maskColor, 0,
            0, 0, 0, maskColor, 0,
            0, maskColor, maskColor, maskColor, 0,
            otherColor, 0, 0, 0, 0,
        )

        val region = FillRegionCollector.collect(
            maskPixels = maskPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            startX = 1,
            startY = 1,
            expectedRegionArea = 4,
        )

        val result = FillCoverageCollector.includeConnectedCoverage(
            region = region,
            maskPixels = maskPixels,
            fillCoveragePixels = coveragePixels,
            width = width,
            height = height,
            maskColor = maskColor,
        )

        assertEquals(listOf(6, 7, 8, 11, 12, 13, 16, 17, 18), result.indices.sorted())
        assertEquals(1, result.minX)
        assertEquals(3, result.maxX)
        assertEquals(1, result.minY)
        assertEquals(3, result.maxY)
    }
}
