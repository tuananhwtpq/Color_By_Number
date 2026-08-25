package com.example.baseproject.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.baseproject.models.LanguageModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SharedPrefManager {
    private const val PREF_NAME = "MyPreferences"
    private const val IS_SHOW_GUIDE = "is_show_guide"
    private const val IS_AUTO_SWITCH_COLOR = "is_auto_switch_color"
    private const val IS_FILL_IN_ANIMATION = "is_fill_in_animation"
    private const val HIGHLIGHT_THEME_ID = "highlight_theme_id"
    private const val SELECTED_APP_THEME_ID = "selected_app_theme_id"
    private const val SELECTED_REALM_ID = "selected_realm_id"
    private lateinit var preferences: SharedPreferences

    var isShowGuide: Boolean
        get() {
            return preferences.getBoolean(IS_SHOW_GUIDE, true)
        }
        set(value) {
            preferences.edit { putBoolean(IS_SHOW_GUIDE, value) }
        }

    var isAutoSwitchColor: Boolean
        get() = preferences.getBoolean(IS_AUTO_SWITCH_COLOR, true)
        set(value) {
            preferences.edit { putBoolean(IS_AUTO_SWITCH_COLOR, value) }
        }

    var isFillInAnimation: Boolean
        get() = preferences.getBoolean(IS_FILL_IN_ANIMATION, true)
        set(value) {
            preferences.edit { putBoolean(IS_FILL_IN_ANIMATION, value) }
        }

    var highlightThemeId: String
        get() = preferences.getString(HIGHLIGHT_THEME_ID, DEFAULT_HIGHLIGHT_THEME_ID)
            ?: DEFAULT_HIGHLIGHT_THEME_ID
        set(value) {
            preferences.edit { putString(HIGHLIGHT_THEME_ID, value) }
        }

    var selectedAppThemeId: String
        get() = preferences.getString(SELECTED_APP_THEME_ID, DEFAULT_APP_THEME_ID)
            ?: DEFAULT_APP_THEME_ID
        set(value) {
            preferences.edit { putString(SELECTED_APP_THEME_ID, value) }
        }

    var selectedRealmId: String
        get() = preferences.getString(SELECTED_REALM_ID, DEFAULT_SELECTED_REALM_ID)
            ?: DEFAULT_SELECTED_REALM_ID
        set(value) {
            preferences.edit { putString(SELECTED_REALM_ID, value) }
        }

    fun init(context: Context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    fun putInt(key: String, value: Int) {
        preferences.edit { putInt(key, value) }
    }

    fun putBoolean(key: String, value: Boolean) {
        preferences.edit { putBoolean(key, value) }
    }

    fun putLong(key: String, value: Long) {
        preferences.edit { putLong(key, value) }
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return preferences.getLong(key, defaultValue)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return preferences.getString(key, defaultValue) ?: defaultValue
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return preferences.getInt(key, defaultValue)
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    fun remove(key: String) {
        preferences.edit { remove(key) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    fun <T> putObject(key: String, obj: T) {
        val jsonString = Gson().toJson(obj)
        preferences.edit { putString(key, jsonString) }
    }

    fun <T> getObject(key: String, defaultObj: T): T {
        return preferences.getString(key, null)?.let {
            Gson().fromJson(it, object : TypeToken<T>() {}.type)
        } ?: defaultObj
    }

    fun getLanguage(key: String): LanguageModel? {
        val gson = Gson()

        val json = preferences.getString(key, null)
        val type = object : TypeToken<LanguageModel>() {}.type
        return gson.fromJson(json, type)
    }

    private const val DEFAULT_HIGHLIGHT_THEME_ID = "gray_checker"
    private const val DEFAULT_APP_THEME_ID = "midnight"
    private const val DEFAULT_SELECTED_REALM_ID = "sakura_haven"
}
