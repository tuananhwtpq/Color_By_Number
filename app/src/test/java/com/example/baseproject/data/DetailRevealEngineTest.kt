package com.pixlory.color.by.number.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun indexedCompletionMatchesFullImageCompletion() {
        val maskColor = 0xFF000001.toInt()
        val otherMaskColor = 0xFF000002.toInt()
        val maskPixels = intArrayOf(
            maskColor, otherMaskColor, 0,
            0, maskColor, otherMaskColor,
        )
        val fillCoveragePixels = intArrayOf(
            maskColor, otherMaskColor, maskColor,
            0, maskColor, otherMaskColor,
        )
        val detailSource = intArrayOf(11, 12, 13, 14, 15, 16)
        val expectedColors = IntArray(maskPixels.size)
        val expectedDetails = IntArray(maskPixels.size)
        val indexedColors = IntArray(maskPixels.size)
        val indexedDetails = IntArray(maskPixels.size)
        val targetColor = 0xFFAA5500.toInt()

        DetailRevealEngine.completeRegionForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = expectedColors,
            detailSourcePixels = detailSource,
            revealedDetailPixels = expectedDetails,
            maskColor = maskColor,
            targetColor = targetColor,
            fillCoveragePixels = fillCoveragePixels,
        )

        val index = MaskColorPixelIndex.build(
            maskPixels = maskPixels,
            fillCoveragePixels = fillCoveragePixels,
            width = 3,
            height = 2,
            targetMaskColors = setOf(maskColor, otherMaskColor),
        )
        val region = requireNotNull(index[maskColor])
        DetailRevealEngine.completeRegionAtIndices(
            region = region,
            maskPixels = maskPixels,
            coloredPixels = indexedColors,
            detailSourcePixels = detailSource,
            revealedDetailPixels = indexedDetails,
            maskColor = maskColor,
            targetColor = targetColor,
            fillCoveragePixels = fillCoveragePixels,
        )

        assertArrayEquals(expectedColors, indexedColors)
        assertArrayEquals(expectedDetails, indexedDetails)
        assertArrayEquals(intArrayOf(0, 2, 4), region.indices)
        assertEquals(0, region.minX)
        assertEquals(0, region.minY)
        assertEquals(2, region.maxX)
        assertEquals(1, region.maxY)
    }
}
