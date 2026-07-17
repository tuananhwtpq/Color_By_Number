package com.example.baseproject.data.repository

import com.example.baseproject.models.LanguageModel

interface SettingsRepository {
    fun getSelectedLanguage(): LanguageModel
    fun setSelectedLanguage(language: LanguageModel)
    fun getLanguageList(): List<LanguageModel>
    fun isIntroShown(): Boolean
    fun setIntroShown(shown: Boolean)
    fun getWantShowRate(): Boolean
    fun setWantShowRate(value: Boolean)
    fun getIsFirstTimeOpenApp(): Boolean
    fun setIsFirstTimeOpenApp(value: Boolean)
    fun getRequireShowRate(): Boolean
    fun setRequireShowRate(value: Boolean)
}
