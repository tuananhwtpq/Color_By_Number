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
            putLong(lastPaintedAtKey(category, levelId), System.currentTimeMillis())
        }
    }

    override fun loadPaintHistory(category: String, levelId: String): List<Int> {
        val rawHistory = preferences.getString(historyKey(category, levelId), null)
            ?: return emptyList()
        return rawHistory.split(HISTORY_SEPARATOR)
            .mapNotNull { it.toIntOrNull() }
    }

    override fun appendPaintHistory(category: String, levelId: String, maskColor: Int) {
        val currentHistory = loadPaintHistory(category, levelId)
        if (maskColor in currentHistory) return

        preferences.edit {
            putString(
                historyKey(category, levelId),
                (currentHistory + maskColor).joinToString(HISTORY_SEPARATOR)
            )
        }
    }

    override fun resetProgress(category: String, levelId: String) {
        preferences.edit {
            remove(progressKey(category, levelId))
            remove(historyKey(category, levelId))
            remove(lastPaintedAtKey(category, levelId))
        }
    }

    override fun lastPaintedAt(category: String, levelId: String): Long =
        preferences.getLong(lastPaintedAtKey(category, levelId), 0L)

    private fun progressKey(category: String, levelId: String): String =
        "PROGRESS_${category}_${levelId}"

    private fun historyKey(category: String, levelId: String): String =
        "PAINT_HISTORY_${category}_${levelId}"

    private fun lastPaintedAtKey(category: String, levelId: String): String =
        "PAINTED_AT_${category}_${levelId}"

    private companion object {
        const val HISTORY_SEPARATOR = ","
    }
}
