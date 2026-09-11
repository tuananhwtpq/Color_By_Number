package com.pixlory.color.by.number.data

import com.pixlory.color.by.number.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationFillFrameComposerTest {
    @Test
    fun productionUnderpaintCoversTheSameAntialiasedEdgeDuringAndAfterFill() {
        assertTrue(BuildConfig.USE_EDGE_UNDERPAINT)

        val width = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val filledIndex = 1
        val edgeIndex = 2
        val maskPixels = intArrayOf(0, maskColor, 0)
        val lineLumaPixels = intArrayOf(255, 255, 20)

        val frame = AnimationFillFrameComposer.compose(
            region = FillRegionPixels(intArrayOf(filledIndex), 1, 1, 0, 0),
            maskPixels = maskPixels,
            coloredPixels = IntArray(width),
            detailPixels = null,
            fillCoveragePixels = null,
            lineLumaPixels = lineLumaPixels,
            imageWidth = width,
            imageHeight = 1,
            maskColor = maskColor,
            targetColor = targetColor,
        )
        assertEquals(targetColor, frame.pixels[edgeIndex - frame.left])

        val finalPixels = IntArray(width)
        EdgeUnderpaintEngine.applyForMaskColor(
            maskPixels = maskPixels,
            coloredPixels = finalPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = 1,
            maskColor = maskColor,
            targetColor = targetColor,
        )
        assertEquals(targetColor, finalPixels[edgeIndex])
    }

    @Test
    fun indexedLogicalRegionAnimatesDisconnectedMaskAndCoveragePixelsTogether() {
        val width = 5
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = intArrayOf(maskColor, 0, 0, 0, maskColor)
        val coveragePixels = intArrayOf(0, maskColor, 0, maskColor, 0)
        val indexedRegion = requireNotNull(
            MaskColorPixelIndex.build(
                maskPixels = maskPixels,
                fillCoveragePixels = coveragePixels,
                width = width,
                height = 1,
                targetMaskColors = setOf(maskColor),
            )[maskColor]
        )

        val frame = AnimationFillFrameComposer.compose(
            region = indexedRegion,
            maskPixels = maskPixels,
            coloredPixels = IntArray(width),
            detailPixels = null,
            fillCoveragePixels = coveragePixels,
            lineLumaPixels = null,
            imageWidth = width,
            imageHeight = 1,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(listOf(targetColor, targetColor, 0, targetColor, targetColor), frame.pixels.toList())
    }

    @Test
    fun includesLineCoveredUnderpaintPixelInAnimationFrame() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val lineIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        lineLumaPixels[lineIndex] = 20

        val frame = AnimationFillFrameComposer.compose(
            region = FillRegionPixels(
                indices = intArrayOf(filledIndex),
                minX = 1,
                maxX = 1,
                minY = 1,
                maxY = 1
            ),
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            detailPixels = null,
            fillCoveragePixels = null,
            lineLumaPixels = lineLumaPixels,
            imageWidth = width,
            imageHeight = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(targetColor, frame.pixels[(1 - frame.top) * frame.width + (2 - frame.left)])
    }

    @Test
    fun doesNotUnderpaintTransparentBackgroundOnlyNearLineInAnimationFrame() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val outsideIndex = 1 * width + 2
        val nearbyInkIndex = 0 * width + 2

        maskPixels[filledIndex] = maskColor
        lineLumaPixels[nearbyInkIndex] = 20

        val frame = AnimationFillFrameComposer.compose(
            region = FillRegionPixels(
                indices = intArrayOf(filledIndex),
                minX = 1,
                maxX = 1,
                minY = 1,
                maxY = 1
            ),
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            detailPixels = null,
            fillCoveragePixels = null,
            lineLumaPixels = lineLumaPixels,
            imageWidth = width,
            imageHeight = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(0, frame.pixels[(1 - frame.top) * frame.width + (2 - frame.left)])
        assertEquals(outsideIndex, 1 * width + 2)
    }

    @Test
    fun suppressesBrightDetailNearInkInAnimationFrame() {
        val width = 5
        val height = 3
        val maskColor = 0xFF000003.toInt()
        val targetColor = 0xFF6E5362.toInt()
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        val detailPixels = IntArray(width * height)
        val lineLumaPixels = IntArray(width * height) { 255 }
        val filledIndex = 1 * width + 1
        val nearbyInkIndex = 1 * width + 2

        maskPixels[filledIndex] = maskColor
        detailPixels[filledIndex] = 0xFFFFFFFF.toInt()
        lineLumaPixels[nearbyInkIndex] = 20

        val frame = AnimationFillFrameComposer.compose(
            region = FillRegionPixels(
                indices = intArrayOf(filledIndex),
                minX = 1,
                maxX = 1,
                minY = 1,
                maxY = 1
            ),
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            detailPixels = detailPixels,
            fillCoveragePixels = null,
            lineLumaPixels = lineLumaPixels,
            imageWidth = width,
            imageHeight = height,
            maskColor = maskColor,
            targetColor = targetColor,
        )

        assertEquals(targetColor, frame.pixels[(1 - frame.top) * frame.width + (1 - frame.left)])
    }
}
