package com.example.baseproject.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.baseproject.R
import com.example.baseproject.models.LanguageModel
import com.example.baseproject.utils.Constants.HAWK_LANGUAGE_POSITION
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SettingsRepositoryImpl(
    private val preferences: SharedPreferences
) : SettingsRepository {

    private val gson = Gson()

    override fun getSelectedLanguage(): LanguageModel {
        val json = preferences.getString(HAWK_LANGUAGE_POSITION, null)
        val type = object : TypeToken<LanguageModel>() {}.type
        return gson.fromJson<LanguageModel?>(json, type)
            ?: LanguageModel(R.drawable.ic_english, R.string.english, "en")
    }

    override fun setSelectedLanguage(language: LanguageModel) {
        preferences.edit { putString(HAWK_LANGUAGE_POSITION, gson.toJson(language)) }
    }

    override fun getLanguageList(): List<LanguageModel> = listOf(
        LanguageModel(R.drawable.ic_english, R.string.english, "en"),
        LanguageModel(R.drawable.ic_hindi, R.string.hindi, "hi"),
        LanguageModel(R.drawable.ic_spanish, R.string.spanish, "es"),
        LanguageModel(R.drawable.ic_french, R.string.french, "fr"),
        LanguageModel(R.drawable.ic_arabic, R.string.arabic, "ar"),
        LanguageModel(R.drawable.ic_bengali, R.string.bengali, "bn"),
        LanguageModel(R.drawable.ic_russian, R.string.russian, "ru"),
        LanguageModel(R.drawable.ic_portuguese, R.string.portuguese, "pt"),
        LanguageModel(R.drawable.ic_indonesian, R.string.indonesian, "in"),
        LanguageModel(R.drawable.ic_german, R.string.german, "de"),
        LanguageModel(R.drawable.ic_italian, R.string.italian, "it"),
        LanguageModel(R.drawable.ic_korean, R.string.korean, "ko")
    )

    override fun isIntroShown(): Boolean = preferences.getBoolean("isIntroShown", false)

    override fun setIntroShown(shown: Boolean) {
        preferences.edit { putBoolean("isIntroShown", shown) }
    }

    override fun getWantShowRate(): Boolean = preferences.getBoolean("wantShowRate", false)

    override fun setWantShowRate(value: Boolean) {
        preferences.edit { putBoolean("wantShowRate", value) }
    }

    override fun getIsFirstTimeOpenApp(): Boolean =
        preferences.getBoolean("is_first_time_open_app", true)

    override fun setIsFirstTimeOpenApp(value: Boolean) {
        preferences.edit { putBoolean("is_first_time_open_app", value) }
    }

    override fun getRequireShowRate(): Boolean =
        preferences.getBoolean("is_require_show_rate", false)

    override fun setRequireShowRate(value: Boolean) {
        preferences.edit { putBoolean("is_require_show_rate", value) }
    }
}
