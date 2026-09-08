package com.example.baseproject.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeUnderpaintEngineTest {
    @Test
    fun underpaintsTransparentLineBackgroundPixelNearFilledRegionAndInk() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val gapIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        coloredPixels[filledIndex] = targetColor
        lineLumaPixels[gapIndex] = 20

        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(targetColor, coloredPixels[gapIndex])
    }

    @Test
    fun doesNotUnderpaintTransparentBackgroundOnlyNearLine() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val outsideIndex = 1 * width + 2
        val inkIndex = 0 * width + 2

        maskPixels[filledIndex] = maskColor
        coloredPixels[filledIndex] = targetColor
        lineLumaPixels[inkIndex] = 20

        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(0, coloredPixels[outsideIndex])
    }

    @Test
    fun underpaintsDifferentMaskPixelOnlyWhenItIsCoveredByLineAntialias() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val otherMaskColor = 0xFF000020.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val otherRegionIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        maskPixels[otherRegionIndex] = otherMaskColor
        coloredPixels[filledIndex] = targetColor
        lineLumaPixels[otherRegionIndex] = 20

        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(targetColor, coloredPixels[otherRegionIndex])
    }

    @Test
    fun doesNotUnderpaintDifferentUnfilledMaskRegionAwayFromLinePixel() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val otherMaskColor = 0xFF000020.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val otherRegionIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        maskPixels[otherRegionIndex] = otherMaskColor
        coloredPixels[filledIndex] = targetColor
        lineLumaPixels[filledIndex] = 20

        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(0, coloredPixels[otherRegionIndex])
    }

    @Test
    fun suppressesBrightDetailOnUnderpaintedLinePixel() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val detailSourcePixels = IntArray(width * height)
        val revealedDetailPixels = IntArray(width * height)
        val filledIndex = 1 * width + 1
        val gapIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        coloredPixels[filledIndex] = targetColor
        lineLumaPixels[gapIndex] = 20
        detailSourcePixels[gapIndex] = 0xFFFFFFFF.toInt()

        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            targetColor = targetColor,
            detailSourcePixels = detailSourcePixels,
            revealedDetailPixels = revealedDetailPixels,
        )

        assertEquals(targetColor, coloredPixels[gapIndex])
        assertEquals(0, revealedDetailPixels[gapIndex])
    }

    @Test
    fun suppressesBrightDetailInsideFilledRegionNearInk() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val detailSourcePixels = IntArray(width * height)
        val revealedDetailPixels = IntArray(width * height)
        val filledIndex = 1 * width + 1
        val inkIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        coloredPixels[filledIndex] = targetColor
        lineLumaPixels[inkIndex] = 20
        detailSourcePixels[filledIndex] = 0xFFFFFFFF.toInt()
        revealedDetailPixels[filledIndex] = 0xFFFFFFFF.toInt()

        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            targetColor = targetColor,
            detailSourcePixels = detailSourcePixels,
            revealedDetailPixels = revealedDetailPixels,
        )

        assertEquals(0, revealedDetailPixels[filledIndex])
    }
}
