package com.example.baseproject.highlight

import android.graphics.Color
import android.view.animation.AccelerateInterpolator
import android.view.animation.Interpolator

enum class HighlightStyle {
    CHECKER
}

data class HighlightTheme(
    val id: String,
    val style: HighlightStyle,
    val primaryColor: Int,
    val secondaryColor: Int,
    val cellSizePx: Int,
    val baseAlpha: Int,
    val fadeInDurationMs: Long,
    val interpolator: Interpolator,
    val tinyTargetAreaThresholdPx: Int = 100,
    val tinyTargetMinSideThresholdPx: Int = 10,
    val tinyTargetHaloRadiusPx: Int = 14,
    val tinyTargetHaloAlpha: Int = 170,
    val tinyTargetHaloColor: Int = Color.parseColor("#FFFFFF")
)

object HighlightThemes {
    fun defaultChecker(): HighlightTheme {
        return HighlightTheme(
            id = "checker_default",
            style = HighlightStyle.CHECKER,
            primaryColor = Color.parseColor("#F5F2F8"),
            secondaryColor = Color.parseColor("#9A94A3"),
            cellSizePx = 10,
            baseAlpha = 255,
            fadeInDurationMs = 200L,
            interpolator = AccelerateInterpolator(),
            tinyTargetHaloAlpha = 0
        )
    }
}
