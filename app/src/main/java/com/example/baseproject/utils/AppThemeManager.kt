package com.example.baseproject.utils

import android.view.View
import android.widget.ImageView
import com.example.baseproject.R

object AppThemeManager {
    const val THEME_MIDNIGHT = "midnight"
    const val THEME_SUNSET = "sunset"
    const val THEME_SUNRISE = "sunrise"

    fun applyFullBackground(view: View) {
        view.setBackgroundResource(currentTheme().fullBackgroundRes)
    }

    fun applyTopImage(imageView: ImageView) {
        imageView.setImageResource(currentTheme().topImageRes)
    }

    fun applyCompleteBackground(view: View) {
        view.setBackgroundResource(currentTheme().completeBackgroundRes)
    }

    fun fullBackgroundRes(): Int = currentTheme().fullBackgroundRes

    fun topImageRes(): Int = currentTheme().topImageRes

    fun completeBackgroundRes(): Int = currentTheme().completeBackgroundRes

    private fun currentTheme(): AppTheme =
        when (SharedPrefManager.selectedAppThemeId) {
            THEME_SUNSET -> AppTheme.SUNSET
            THEME_SUNRISE -> AppTheme.SUNRISE
            else -> AppTheme.MIDNIGHT
        }

    private enum class AppTheme(
        val fullBackgroundRes: Int,
        val topImageRes: Int,
        val completeBackgroundRes: Int
    ) {
        MIDNIGHT(
            R.drawable.bg_full_main_theme_01,
            R.drawable.bg_top_main_theme_01,
            R.drawable.bg_complete_theme_01
        ),
        SUNSET(
            R.drawable.bg_full_main_theme_02,
            R.drawable.bg_top_main_theme_02,
            R.drawable.bg_complete_theme_02
        ),
        SUNRISE(
            R.drawable.bg_full_main_theme_03,
            R.drawable.bg_top_main_theme_03,
            R.drawable.bg_complete_theme_03
        )
    }
}
