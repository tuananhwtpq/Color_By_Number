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
    val interpolator: Interpolator
)

object HighlightThemes {
    fun defaultChecker(): HighlightTheme {
        return HighlightTheme(
            id = "checker_default",
            style = HighlightStyle.CHECKER,
            primaryColor = Color.parseColor("#F5F2F8"),
            secondaryColor = Color.parseColor("#CFC7D8"),
            cellSizePx = 18,
            baseAlpha = 255,
            fadeInDurationMs = 200L,
            interpolator = AccelerateInterpolator()
        )
    }
}
