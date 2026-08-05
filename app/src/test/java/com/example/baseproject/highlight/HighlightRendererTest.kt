package com.example.baseproject.highlight

import android.view.animation.Interpolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRendererTest {
    private val linearInterpolator = Interpolator { input -> input }

    private fun testTheme(
        tinyAreaThreshold: Int = 4,
        tinySideThreshold: Int = 2,
        haloRadius: Int = 3,
    ) = HighlightTheme(
        id = "test",
        style = HighlightStyle.CHECKER,
        primaryColor = 0x00F5F2F8,
        secondaryColor = 0x00CFC7D8,
        cellSizePx = 2,
        baseAlpha = 220,
        fadeInDurationMs = 0L,
        interpolator = linearInterpolator,
        tinyTargetAreaThresholdPx = tinyAreaThreshold,
        tinyTargetMinSideThresholdPx = tinySideThreshold,
        tinyTargetHaloRadiusPx = haloRadius,
        tinyTargetHaloAlpha = 180,
        tinyTargetHaloColor = 0x00FFFFFF,
    )

    @Test
    fun tinyTargetGetsHaloOutsideItsExactMaskPixels() {
        val width = 9
        val maskPixels = IntArray(width * width)
        val centerIndex = 4 * width + 4
        maskPixels[centerIndex] = 0x000002
        val outputPixels = IntArray(maskPixels.size)

        HighlightRenderer.render(
            maskPixels = maskPixels,
            outputPixels = outputPixels,
            width = width,
            activeTargets = intArrayOf(0x000002),
            theme = testTheme(),
            alphaFraction = 1f,
        )

        assertEquals(220, alpha(outputPixels[centerIndex]))
        assertTrue(alpha(outputPixels[4 * width + 6]) > 0)
        assertEquals(0, outputPixels[0])
    }

    @Test
    fun largeTargetDoesNotAddHaloOutsideMaskPixels() {
        val width = 10
        val maskPixels = IntArray(width * width)
        for (y in 3..6) {
            for (x in 3..6) {
                maskPixels[y * width + x] = 0x000009
            }
        }
        val outputPixels = IntArray(maskPixels.size)

        HighlightRenderer.render(
            maskPixels = maskPixels,
            outputPixels = outputPixels,
            width = width,
            activeTargets = intArrayOf(0x000009),
            theme = testTheme(tinyAreaThreshold = 4, tinySideThreshold = 2),
            alphaFraction = 1f,
        )

        assertEquals(220, alpha(outputPixels[4 * width + 4]))
        assertEquals(0, outputPixels[2 * width + 4])
    }

    private fun alpha(color: Int): Int = (color ushr 24) and 0xFF
}
