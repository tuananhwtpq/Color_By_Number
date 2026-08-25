package com.example.baseproject.activities

import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityThemeBinding
import com.example.baseproject.utils.AppThemeManager
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.setOnUnDoubleClick
import com.example.baseproject.views.ThemeOptionView

class ThemeActivity : BaseActivity<ActivityThemeBinding>(ActivityThemeBinding::inflate) {

    private lateinit var themeOptions: List<Pair<ThemeOptionView, String>>
    private var selectedThemeId = AppThemeManager.THEME_MIDNIGHT

    override fun initData() {
        selectedThemeId = SharedPrefManager.selectedAppThemeId
    }

    override fun initView() {
        themeOptions = listOf(
            binding.viewThemeMidnight to AppThemeManager.THEME_MIDNIGHT,
            binding.viewThemeSunset to AppThemeManager.THEME_SUNSET,
            binding.viewThemeSunrise to AppThemeManager.THEME_SUNRISE
        )
        applyAppTheme()
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick { finish() }
        themeOptions.forEach { (view, themeId) ->
            view.setOnUnDoubleClick {
                selectedThemeId = themeId
                SharedPrefManager.selectedAppThemeId = themeId
                applyAppTheme()
            }
        }
    }

    private fun applyAppTheme() {
        AppThemeManager.applyFullBackground(binding.main)
        AppThemeManager.applyTopImage(binding.ivTopImage)
        themeOptions.forEach { (view, themeId) ->
            view.isSelected = themeId == selectedThemeId
        }
    }
}
