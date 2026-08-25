package com.example.baseproject.activities

import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityThemeBinding
import com.example.baseproject.utils.setOnUnDoubleClick
import com.example.baseproject.views.ThemeOptionView

class ThemeActivity : BaseActivity<ActivityThemeBinding>(ActivityThemeBinding::inflate) {

    private companion object {
        const val THEME_MIDNIGHT = "midnight"
        const val THEME_SUNSET = "sunset"
        const val THEME_SUNRISE = "sunrise"
    }

    private lateinit var themeOptions: List<Pair<ThemeOptionView, String>>
    private var selectedThemeId = THEME_MIDNIGHT

    override fun initData() {

    }

    override fun initView() {
        themeOptions = listOf(
            binding.viewThemeMidnight to THEME_MIDNIGHT,
            binding.viewThemeSunset to THEME_SUNSET,
            binding.viewThemeSunrise to THEME_SUNRISE
        )
        renderSelection()
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick { finish() }
        themeOptions.forEach { (view, themeId) ->
            view.setOnUnDoubleClick {
                selectedThemeId = themeId
                renderSelection()
            }
        }
    }

    private fun renderSelection() {
        themeOptions.forEach { (view, themeId) ->
            view.isSelected = themeId == selectedThemeId
        }
    }
}
