package com.pixlory.color.by.number.data.repository

import com.pixlory.color.by.number.models.LanguageModel

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
