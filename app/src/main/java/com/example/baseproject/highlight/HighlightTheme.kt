package com.example.baseproject.highlight

import android.graphics.Color
import android.view.animation.AccelerateInterpolator
import android.view.animation.Interpolator

enum class HighlightStyle {
    CHECKER,
    SOLID
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
    const val ID_GRAY_CHECKER = "gray_checker"
    const val ID_ORANGE_CHECKER = "orange_checker"
    const val ID_BLUE_CHECKER = "blue_checker"
    const val ID_SOLID_GRAY = "solid_gray"

    fun fromId(id: String): HighlightTheme {
        return when (id) {
            ID_ORANGE_CHECKER -> orangeChecker()
            ID_BLUE_CHECKER -> blueChecker()
            ID_SOLID_GRAY -> solidGray()
            else -> defaultChecker()
        }
    }

    fun defaultChecker(): HighlightTheme {
        return HighlightTheme(
            id = ID_GRAY_CHECKER,
            style = HighlightStyle.CHECKER,
            primaryColor = Color.parseColor("#E7E4E7"),
            secondaryColor = Color.parseColor("#9E92A0"),
            cellSizePx = 10,
            baseAlpha = 255,
            fadeInDurationMs = 200L,
            interpolator = AccelerateInterpolator(),
            tinyTargetHaloAlpha = 0
        )
    }

    private fun orangeChecker(): HighlightTheme {
        return checkerTheme(
            id = ID_ORANGE_CHECKER,
            primaryColor = Color.parseColor("#CFC9CF"),
            secondaryColor = Color.parseColor("#E9752F"),
        )
    }

    private fun blueChecker(): HighlightTheme {
        return checkerTheme(
            id = ID_BLUE_CHECKER,
            primaryColor = Color.parseColor("#CFC9CF"),
            secondaryColor = Color.parseColor("#336BCC"),
        )
    }

    private fun solidGray(): HighlightTheme {
        return HighlightTheme(
            id = ID_SOLID_GRAY,
            style = HighlightStyle.SOLID,
            primaryColor = Color.parseColor("#9E92A0"),
            secondaryColor = Color.parseColor("#9E92A0"),
            cellSizePx = 10,
            baseAlpha = 255,
            fadeInDurationMs = 200L,
            interpolator = AccelerateInterpolator(),
            tinyTargetHaloAlpha = 0
        )
    }

    private fun checkerTheme(
        id: String,
        primaryColor: Int,
        secondaryColor: Int,
    ): HighlightTheme {
        return HighlightTheme(
            id = id,
            style = HighlightStyle.CHECKER,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            cellSizePx = 10,
            baseAlpha = 255,
            fadeInDurationMs = 200L,
            interpolator = AccelerateInterpolator(),
            tinyTargetHaloAlpha = 0
        )
    }
}
