package com.example.baseproject.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.LocalDate

class PaintDropRepositoryImpl(
    private val preferences: SharedPreferences,
) : PaintDropRepository {

    override fun loadStats(): PaintDropStats {
        val paintDrops = preferences.getInt(KEY_PAINT_DROPS, 0)
        return PaintDropStats(
            paintDrops = paintDrops,
            daysExplored = readSet(KEY_EXPLORED_DATES).size,
            worksCompleted = readSet(KEY_COMPLETED_LEVELS).size,
            areasUnlocked = readSet(KEY_UNLOCKED_REALMS).size,
        )
    }

    override fun loadUnlockedRealmIds(): Set<String> = readSet(KEY_UNLOCKED_REALMS)

    override fun trackAppOpened() {
        addToSet(KEY_EXPLORED_DATES, LocalDate.now().toString())
    }

    override fun trackArtworkCompleted(category: String, levelId: String): Int {
        val key = "$category/$levelId"
        val completedLevels = readSet(KEY_COMPLETED_LEVELS)
        if (key in completedLevels) return 0

        preferences.edit {
            putStringSet(KEY_COMPLETED_LEVELS, completedLevels + key)
            putInt(KEY_PAINT_DROPS, preferences.getInt(KEY_PAINT_DROPS, 0) + PAINT_DROPS_PER_WORK)
        }
        return PAINT_DROPS_PER_WORK
    }

    override fun unlockRealm(realmId: String) {
        addToSet(KEY_UNLOCKED_REALMS, realmId)
    }

    private fun readSet(key: String): Set<String> =
        preferences.getStringSet(key, emptySet()).orEmpty()

    private fun addToSet(key: String, value: String) {
        val current = readSet(key)
        if (value in current) return
        preferences.edit { putStringSet(key, current + value) }
    }

    private companion object {
        const val KEY_PAINT_DROPS = "PAINT_DROPS_BALANCE"
        const val KEY_EXPLORED_DATES = "PAINT_DROPS_EXPLORED_DATES"
        const val KEY_COMPLETED_LEVELS = "PAINT_DROPS_COMPLETED_LEVELS"
        const val KEY_UNLOCKED_REALMS = "PAINT_DROPS_UNLOCKED_REALMS"
        const val PAINT_DROPS_PER_WORK = 10
    }
}
