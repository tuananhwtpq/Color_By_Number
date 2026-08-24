package com.example.baseproject.highlight

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
            HighlightStyle.SOLID -> renderSolid(
                maskPixels = maskPixels,
                outputPixels = outputPixels,
                activeTargets = activeTargets,
                theme = theme,
                effectiveAlpha = effectiveAlpha
            )
        }
    }

    private fun renderSolid(
        maskPixels: IntArray,
        outputPixels: IntArray,
        activeTargets: IntArray,
        theme: HighlightTheme,
        effectiveAlpha: Int
    ) {
        val total = outputPixels.size
        for (i in 0 until total) {
            val maskColor = maskPixels[i]
            if (maskColor == 0 || java.util.Arrays.binarySearch(activeTargets, maskColor) < 0) {
                continue
            }

            outputPixels[i] = argb(
                effectiveAlpha,
                red(theme.primaryColor),
                green(theme.primaryColor),
                blue(theme.primaryColor)
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
        val height = if (width > 0) outputPixels.size / width else 0
        val bounds = collectTargetBounds(
            maskPixels = maskPixels,
            width = width,
            activeTargets = activeTargets,
        )

        renderTinyTargetHalos(
            outputPixels = outputPixels,
            width = width,
            height = height,
            bounds = bounds,
            theme = theme,
        )

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
            outputPixels[i] = argb(
                effectiveAlpha,
                red(baseColor),
                green(baseColor),
                blue(baseColor)
            )
        }
    }

    private data class TargetBounds(
        var pixelCount: Int = 0,
        var minX: Int = Int.MAX_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var maxY: Int = Int.MIN_VALUE,
        var sumX: Long = 0L,
        var sumY: Long = 0L,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val minSide: Int get() = minOf(width, height)
        val centerX: Int get() = (sumX / pixelCount.coerceAtLeast(1)).toInt()
        val centerY: Int get() = (sumY / pixelCount.coerceAtLeast(1)).toInt()
    }

    private fun collectTargetBounds(
        maskPixels: IntArray,
        width: Int,
        activeTargets: IntArray,
    ): Array<TargetBounds> {
        val bounds = Array(activeTargets.size) { TargetBounds() }
        if (width <= 0) return bounds

        for (index in maskPixels.indices) {
            val targetIndex = java.util.Arrays.binarySearch(activeTargets, maskPixels[index])
            if (targetIndex < 0) continue

            val x = index % width
            val y = index / width
            val item = bounds[targetIndex]
            item.pixelCount += 1
            item.minX = minOf(item.minX, x)
            item.minY = minOf(item.minY, y)
            item.maxX = maxOf(item.maxX, x)
            item.maxY = maxOf(item.maxY, y)
            item.sumX += x.toLong()
            item.sumY += y.toLong()
        }
        return bounds
    }

    private fun renderTinyTargetHalos(
        outputPixels: IntArray,
        width: Int,
        height: Int,
        bounds: Array<TargetBounds>,
        theme: HighlightTheme,
    ) {
        val radius = theme.tinyTargetHaloRadiusPx.coerceAtLeast(0)
        val haloAlpha = theme.tinyTargetHaloAlpha.coerceIn(0, 255)
        if (width <= 0 || height <= 0 || radius <= 0 || haloAlpha == 0) return

        val radiusSquared = radius * radius
        for (target in bounds) {
            if (target.pixelCount <= 0) continue
            val isTiny = target.pixelCount <= theme.tinyTargetAreaThresholdPx ||
                    target.minSide <= theme.tinyTargetMinSideThresholdPx
            if (!isTiny) continue

            val centerX = target.centerX
            val centerY = target.centerY
            val startX = (centerX - radius).coerceAtLeast(0)
            val endX = (centerX + radius).coerceAtMost(width - 1)
            val startY = (centerY - radius).coerceAtLeast(0)
            val endY = (centerY + radius).coerceAtMost(height - 1)

            for (y in startY..endY) {
                val dy = y - centerY
                for (x in startX..endX) {
                    val dx = x - centerX
                    val distanceSquared = dx * dx + dy * dy
                    if (distanceSquared > radiusSquared) continue

                    val alphaScale = 1f - (distanceSquared.toFloat() / radiusSquared.toFloat())
                    val alpha = (haloAlpha * alphaScale).toInt().coerceIn(0, haloAlpha)
                    if (alpha <= 0) continue

                    val outputIndex = y * width + x
                    if (alpha(outputPixels[outputIndex]) >= alpha) continue
                    outputPixels[outputIndex] = argb(
                        alpha,
                        red(theme.tinyTargetHaloColor),
                        green(theme.tinyTargetHaloColor),
                        blue(theme.tinyTargetHaloColor),
                    )
                }
            }
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha and 0xFF) shl 24) or
                ((red and 0xFF) shl 16) or
                ((green and 0xFF) shl 8) or
                (blue and 0xFF)
    }

    private fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    private fun red(color: Int): Int = (color ushr 16) and 0xFF
    private fun green(color: Int): Int = (color ushr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF
}
