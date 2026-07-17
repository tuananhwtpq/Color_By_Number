package com.example.baseproject.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit

class PaintingProgressRepositoryImpl(
    private val preferences: SharedPreferences
) : PaintingProgressRepository {

    override fun loadProgress(category: String, levelId: String): Set<Int> {
        val key = progressKey(category, levelId)
        return preferences.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    override fun saveProgress(category: String, levelId: String, completedMaskColors: Set<Int>) {
        preferences.edit {
            putStringSet(
                progressKey(category, levelId),
                completedMaskColors.map { it.toString() }.toSet()
            )
        }
    }

    override fun resetProgress(category: String, levelId: String) {
        preferences.edit { remove(progressKey(category, levelId)) }
    }

    private fun progressKey(category: String, levelId: String): String =
        "PROGRESS_${category}_${levelId}"
}
