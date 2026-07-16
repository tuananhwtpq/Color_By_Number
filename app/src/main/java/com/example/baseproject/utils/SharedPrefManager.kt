package com.example.baseproject.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.baseproject.models.LanguageModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

object SharedPrefManager {
    private const val PREF_NAME = "MyPreferences"
    private lateinit var preferences: SharedPreferences

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
}