package com.pixlory.color.by.number.activities

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ViewportTransform(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Defines the fit-center viewport used when an artwork first opens and when it is reset.
 */
internal object ViewportTransformPolicy {
    private const val MIN_SCALE_TOLERANCE = 0.001f
    private const val SCALE_RELATIVE_TOLERANCE = 0.001f
    private const val TRANSLATION_TOLERANCE_PX = 0.5f

    fun fitCenter(
        viewportWidth: Float,
        viewportHeight: Float,
        artworkWidth: Int,
        artworkHeight: Int,
    ): ViewportTransform? {
        if (viewportWidth <= 0f || viewportHeight <= 0f || artworkWidth <= 0 || artworkHeight <= 0) {
            return null
        }

        val scale = min(viewportWidth / artworkWidth, viewportHeight / artworkHeight)
        return ViewportTransform(
            scale = scale,
            translationX = (viewportWidth - artworkWidth * scale) / 2f,
            translationY = (viewportHeight - artworkHeight * scale) / 2f,
        )
    }

    fun isAtFitCenter(
        scale: Float,
        translationX: Float,
        translationY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        artworkWidth: Int,
        artworkHeight: Int,
    ): Boolean {
        val target = fitCenter(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            artworkWidth = artworkWidth,
            artworkHeight = artworkHeight,
        ) ?: return true

        val scaleTolerance = max(MIN_SCALE_TOLERANCE, target.scale * SCALE_RELATIVE_TOLERANCE)
        return abs(scale - target.scale) <= scaleTolerance &&
            abs(translationX - target.translationX) <= TRANSLATION_TOLERANCE_PX &&
            abs(translationY - target.translationY) <= TRANSLATION_TOLERANCE_PX
    }
}
