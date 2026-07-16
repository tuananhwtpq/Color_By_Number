package com.example.baseproject.highlight

import android.graphics.Color

object HighlightRenderer {

    fun render(
        maskPixels: IntArray,
        outputPixels: IntArray,
        width: Int,
        activeTargets: IntArray,
        theme: HighlightTheme,
        alphaFraction: Float
    ) {
        java.util.Arrays.fill(outputPixels, 0)
        if (activeTargets.isEmpty()) return

        activeTargets.sort()
        val effectiveAlpha = (theme.baseAlpha * alphaFraction)
            .toInt()
            .coerceIn(0, 255)
        if (effectiveAlpha == 0) return

        when (theme.style) {
            HighlightStyle.CHECKER -> renderChecker(
                maskPixels = maskPixels,
                outputPixels = outputPixels,
                width = width,
                activeTargets = activeTargets,
                theme = theme,
                effectiveAlpha = effectiveAlpha
            )
        }
    }

    private fun renderChecker(
        maskPixels: IntArray,
        outputPixels: IntArray,
        width: Int,
        activeTargets: IntArray,
        theme: HighlightTheme,
        effectiveAlpha: Int
    ) {
        val cellSize = theme.cellSizePx.coerceAtLeast(2)
        val total = outputPixels.size

        for (i in 0 until total) {
            val maskColor = maskPixels[i]
            if (maskColor == 0 || java.util.Arrays.binarySearch(activeTargets, maskColor) < 0) {
                continue
            }

            val x = i % width
            val y = i / width
            val checkerX = x / cellSize
            val checkerY = y / cellSize
            val baseColor = if ((checkerX + checkerY) % 2 == 0) {
                theme.primaryColor
            } else {
                theme.secondaryColor
            }
            outputPixels[i] = Color.argb(
                effectiveAlpha,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
        }
    }
}
