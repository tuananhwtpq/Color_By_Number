package com.pixlory.color.by.number.ui.intro

import androidx.lifecycle.ViewModel
import com.pixlory.color.by.number.data.repository.SettingsRepository

class IntroViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    fun onIntroOpened() {
        settingsRepository.setWantShowRate(false)
    }

    fun onIntroCompleted() {
        settingsRepository.setIntroShown(true)
    }
}
