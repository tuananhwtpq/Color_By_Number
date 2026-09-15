package com.pixlory.color.by.number.activities

import androidx.activity.OnBackPressedCallback
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivityThemeBinding
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.views.ThemeOptionView

class ThemeActivity : BaseActivity<ActivityThemeBinding>(ActivityThemeBinding::inflate) {

    override val shouldMonitorNetwork = true

    private lateinit var themeOptions: List<Pair<ThemeOptionView, String>>
    private var selectedThemeId = AppThemeManager.THEME_MIDNIGHT

    override fun initData() {
        selectedThemeId = SharedPrefManager.selectedAppThemeId
    }

    override fun initView() {
        loadAndShowNativeCollapsibleOther(
            binding.frNativeSmall,
            binding.frNativeExpand,
            binding.whiteLine
        )
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                loadAndShowInterBackToHome(
                    navAction = { finish() },
                    viewBlock = interAdBlockView()
                )
            }
        })
        themeOptions = listOf(
            binding.viewThemeMidnight to AppThemeManager.THEME_MIDNIGHT,
            binding.viewThemeSunset to AppThemeManager.THEME_SUNSET,
            binding.viewThemeSunrise to AppThemeManager.THEME_SUNRISE
        )
        applyAppTheme()
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick { onBackPressedDispatcher.onBackPressed() }
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
