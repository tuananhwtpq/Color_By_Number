package com.example.baseproject.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillRegionCollectorTest {
    @Test
    fun collectsEveryMaskPixelAndDeduplicatesBleedPixels() {
        val width = 5
        val height = 3
        val maskColor = 0x000001
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()

        val maskPixels = intArrayOf(
            0, 0, 0, 0, 0,
            0, maskColor, maskColor, maskColor, 0,
            0, 0, 0, 0, 0,
        )
        val linePixels = IntArray(width * height) { white }.also {
            // This one line pixel touches all three mask pixels. The old collector could add
            // it repeatedly because bleed pixels were not marked visited.
            it[2] = black
        }

        val result = FillRegionCollector.collect(
            maskPixels = maskPixels,
            linePixels = linePixels,
            width = width,
            height = height,
            maskColor = maskColor,
            startX = 1,
            startY = 1,
            expectedRegionArea = 1,
        )

        assertEquals(result.indices.size, result.indices.toSet().size)
        assertEquals(4, result.indices.size)
        assertTrue(result.indices.contains(6))
        assertTrue(result.indices.contains(7))
        assertTrue(result.indices.contains(8))
        assertTrue(result.indices.contains(2))
        assertFalse(maskPixels.any { it == maskColor.inv() })
    }
}
