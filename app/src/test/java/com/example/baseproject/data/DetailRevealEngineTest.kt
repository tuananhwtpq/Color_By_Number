package com.example.baseproject.data

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DetailRevealEngineTest {
    @Test
    fun revealCopiesOnlyDetailPixelsForFilledMaskColor() {
        val maskPixels = intArrayOf(
            0x000001, 0x000002,
            0x000001, 0x000000
        )
        val detailSource = intArrayOf(
            0x11223344, 0x55667788,
            0x99aabbcc.toInt(), 0xddeeff00.toInt()
        )
        val detailOut = IntArray(4)

        val coloredPixels = IntArray(4)
        val targetColor = 0xFFAA5500.toInt()

        DetailRevealEngine.completeRegionForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            detailSourcePixels = detailSource,
            revealedDetailPixels = detailOut,
            maskColor = 0x000001,
            targetColor = targetColor,
        )

        assertArrayEquals(
            intArrayOf(0x11223344, 0, 0x99aabbcc.toInt(), 0),
            detailOut
        )
        assertArrayEquals(
            intArrayOf(targetColor, 0, targetColor, 0),
            coloredPixels
        )
    }

    @Test
    fun completeRegionColorsCoveragePixelsWithoutRevealingDetailOutsideMask() {
        val maskColor = 0xFF000001.toInt()
        val otherMaskColor = 0xFF000002.toInt()
        val maskPixels = intArrayOf(
            maskColor, otherMaskColor,
            0xFF000000.toInt(), 0xFF000000.toInt()
        )
        val fillCoveragePixels = intArrayOf(
            maskColor, otherMaskColor,
            maskColor, 0xFF000000.toInt()
        )
        val detailSource = intArrayOf(
            0x11223344, 0x55667788,
            0x99aabbcc.toInt(), 0xddeeff00.toInt()
        )
        val detailOut = IntArray(4)
        val coloredPixels = IntArray(4)
        val targetColor = 0xFFAA5500.toInt()

        DetailRevealEngine.completeRegionForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            detailSourcePixels = detailSource,
            revealedDetailPixels = detailOut,
            maskColor = maskColor,
            targetColor = targetColor,
            fillCoveragePixels = fillCoveragePixels,
        )

        assertArrayEquals(
            intArrayOf(0x11223344, 0, 0, 0),
            detailOut
        )
        assertArrayEquals(
            intArrayOf(targetColor, 0, targetColor, 0),
            coloredPixels
        )
    }
}
