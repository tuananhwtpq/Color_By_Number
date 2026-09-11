package com.pixlory.color.by.number.activities

import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivityThemeBinding
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.views.ThemeOptionView

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
