package com.pixlory.color.by.number.highlight

import android.content.Context
import android.graphics.Color
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.pixlory.color.by.number.R

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

    fun fromId(context: Context, id: String): HighlightTheme {
        return when (id) {
            ID_ORANGE_CHECKER -> orangeChecker(context)
            ID_BLUE_CHECKER -> blueChecker(context)
            ID_SOLID_GRAY -> solidGray(context)
            else -> defaultChecker(context)
        }
    }

    fun defaultChecker(context: Context): HighlightTheme {
        return HighlightTheme(
            id = ID_GRAY_CHECKER,
            style = HighlightStyle.CHECKER,
            primaryColor = color(context, R.color.grey_100),
            secondaryColor = color(context, R.color.grey_600),
            cellSizePx = 10,
            baseAlpha = 215,
            fadeInDurationMs = 140L,
            interpolator = DecelerateInterpolator(),
            tinyTargetHaloAlpha = 0
        )
    }

    private fun orangeChecker(context: Context): HighlightTheme {
        return checkerTheme(
            id = ID_ORANGE_CHECKER,
            primaryColor = color(context, R.color.grey_200),
            secondaryColor = color(context, R.color.orange450),
        )
    }

    private fun blueChecker(context: Context): HighlightTheme {
        return checkerTheme(
            id = ID_BLUE_CHECKER,
            primaryColor = color(context, R.color.grey_200),
            secondaryColor = color(context, R.color.baby_blue_500),
        )
    }

    private fun solidGray(context: Context): HighlightTheme {
        return HighlightTheme(
            id = ID_SOLID_GRAY,
            style = HighlightStyle.SOLID,
            primaryColor = color(context, R.color.grey_400),
            secondaryColor = color(context, R.color.grey_400),
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

    private fun color(context: Context, @ColorRes colorRes: Int): Int {
        return ContextCompat.getColor(context, colorRes)
    }
}
