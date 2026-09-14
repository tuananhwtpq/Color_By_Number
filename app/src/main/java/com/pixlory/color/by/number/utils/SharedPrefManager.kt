package com.pixlory.color.by.number.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pixlory.color.by.number.models.LanguageModel
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
    private const val HAS_SEEN_LIBRARY_PREPARING = "has_seen_library_preparing"
    private const val IS_BACKGROUND_MUSIC_ENABLED = "is_background_music_enabled"
    private const val IS_SOUND_EFFECTS_ENABLED = "is_sound_effects_enabled"
    private const val HINT_BALANCE = "hint_balance"
    private const val CLAIMED_HINT_ACHIEVEMENT_REWARDS = "claimed_hint_achievement_rewards"
    private lateinit var preferences: SharedPreferences

    const val DEFAULT_HINT_BALANCE = 2
    const val REWARDED_AD_HINT_AMOUNT = 2
    const val ACHIEVEMENT_HINT_REWARD_AMOUNT = 1

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

    var hasSeenLibraryPreparing: Boolean
        get() = preferences.getBoolean(HAS_SEEN_LIBRARY_PREPARING, false)
        set(value) {
            preferences.edit { putBoolean(HAS_SEEN_LIBRARY_PREPARING, value) }
        }

    var isBackgroundMusicEnabled: Boolean
        get() = preferences.getBoolean(IS_BACKGROUND_MUSIC_ENABLED, true)
        set(value) {
            preferences.edit { putBoolean(IS_BACKGROUND_MUSIC_ENABLED, value) }
        }

    var isSoundEffectsEnabled: Boolean
        get() = preferences.getBoolean(IS_SOUND_EFFECTS_ENABLED, true)
        set(value) {
            preferences.edit { putBoolean(IS_SOUND_EFFECTS_ENABLED, value) }
        }

    /** The user starts with two hints on first install; the value persists afterwards. */
    val hintBalance: Int
        get() = preferences.getInt(HINT_BALANCE, DEFAULT_HINT_BALANCE).coerceAtLeast(0)

    /** Returns false without changing storage when there is no usable hint. */
    @Synchronized
    fun consumeHint(): Boolean {
        val currentBalance = hintBalance
        if (currentBalance <= 0) return false

        preferences.edit { putInt(HINT_BALANCE, currentBalance - 1) }
        return true
    }

    @Synchronized
    fun addHints(amount: Int): Int {
        require(amount >= 0) { "Hint amount cannot be negative" }
        val updatedBalance = hintBalance + amount
        preferences.edit { putInt(HINT_BALANCE, updatedBalance) }
        return updatedBalance
    }

    /**
     * Grants an achievement reward exactly once, even when the claim screen is recreated before
     * its achievement state has finished persisting.
     */
    @Synchronized
    fun grantAchievementHintOnce(achievementId: String): Int {
        if (achievementId.isBlank()) return hintBalance

        val grantedAchievementIds = preferences
            .getStringSet(CLAIMED_HINT_ACHIEVEMENT_REWARDS, emptySet())
            .orEmpty()
        if (achievementId in grantedAchievementIds) return hintBalance

        val updatedBalance = hintBalance + ACHIEVEMENT_HINT_REWARD_AMOUNT
        preferences.edit {
            putInt(HINT_BALANCE, updatedBalance)
            putStringSet(CLAIMED_HINT_ACHIEVEMENT_REWARDS, grantedAchievementIds + achievementId)
        }
        return updatedBalance
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
