package com.example.baseproject.ui.intro

import androidx.lifecycle.ViewModel
import com.example.baseproject.data.repository.SettingsRepository

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
