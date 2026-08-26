package com.example.baseproject.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.baseproject.data.Achievement
import com.example.baseproject.data.AchievementCatalog
import com.example.baseproject.data.AchievementDefinition
import com.example.baseproject.data.AchievementRule
import java.time.LocalDate

/**
 * Lưu tiến độ achievement trong SharedPreferences.
 *
 * Điểm mấu chốt: mọi achievement liên quan tới "tô xong tranh" đều tính từ **một** tập
 * [KEY_COMPLETED_LEVELS] chứa khoá "category/levelId" của các bức đã hoàn thành. Nhờ vậy không
 * phải đọc và parse lại config trong assets (~50MB) chỉ để đếm, và "5 bức khác nhau" cũng đúng
 * nghĩa vì tập không chứa phần tử trùng.
 */
class AchievementRepositoryImpl(
    private val preferences: SharedPreferences,
    private val definitionsProvider: suspend () -> List<AchievementDefinition> = {
        AchievementCatalog.definitions
    }
) : AchievementRepository {

    private companion object {
        const val KEY_COMPLETED_LEVELS = "ACHIEVEMENT_COMPLETED_LEVELS"
        const val KEY_COMPLETED_DAILY_LEVELS = "ACHIEVEMENT_COMPLETED_DAILY_LEVELS"
        const val KEY_UNLOCKED_REALMS = "ACHIEVEMENT_UNLOCKED_REALMS"
        const val KEY_HINTS_USED = "ACHIEVEMENT_HINTS_USED"
        const val KEY_STREAK_DAYS = "ACHIEVEMENT_STREAK_DAYS"
        const val KEY_LAST_OPEN_DATE = "ACHIEVEMENT_LAST_OPEN_DATE"
        const val KEY_UNLOCKED_AT_PREFIX = "ACHIEVEMENT_UNLOCKED_AT_"
        const val KEY_REWARD_CLAIMED_PREFIX = "ACHIEVEMENT_REWARD_CLAIMED_"
    }

    private var cachedDefinitions: List<AchievementDefinition> = AchievementCatalog.definitions

    override suspend fun loadAchievements(): List<Achievement> {
        cachedDefinitions = definitionsProvider()
            .ifEmpty { AchievementCatalog.definitions }
            .sortedBy { it.sortOrder }
        // Chạy trước để mốc mở khoá luôn được ghi, kể cả với dữ liệu có sẵn từ trước khi
        // tính năng này tồn tại.
        syncUnlocks(cachedDefinitions)
        return cachedDefinitions.map { definition ->
            val unlockedAt = unlockedAt(definition.id)
            Achievement(
                definition = definition,
                // Đã mở khoá thì luôn hiển thị đầy, không tụt kể cả khi người dùng reset tranh.
                currentCount = if (unlockedAt != null) definition.targetCount
                else currentCount(definition).coerceAtMost(definition.targetCount),
                unlockedAtMillis = unlockedAt,
                isRewardClaimed = isRewardClaimed(definition.id)
            )
        }
    }

    override fun claimReward(achievementId: String) {
        preferences.edit { putBoolean(KEY_REWARD_CLAIMED_PREFIX + achievementId, true) }
    }

    override fun track(event: AchievementEvent) {
        when (event) {
            AchievementEvent.AppOpened -> trackAppOpened()

            is AchievementEvent.ArtworkCompleted -> {
                addToSet(KEY_COMPLETED_LEVELS, "${event.category}/${event.levelId}")
                if (event.isDaily) {
                    addToSet(KEY_COMPLETED_DAILY_LEVELS, "${event.category}/${event.levelId}")
                }
            }

            AchievementEvent.HintUsed ->
                preferences.edit { putInt(KEY_HINTS_USED, hintsUsed() + 1) }

            is AchievementEvent.RealmUnlocked -> addToSet(KEY_UNLOCKED_REALMS, event.realmId)
        }
        syncUnlocks()
    }

    /**
     * Cập nhật số ngày khác nhau mà người dùng mở app. Cùng một ngày chỉ tính một lần; qua ngày
     * mới thì tăng tiếp từ mốc hiện tại, không reset khi người dùng bỏ lỡ vài ngày.
     */
    private fun trackAppOpened() {
        val today = LocalDate.now()
        val lastOpenDate = preferences.getString(KEY_LAST_OPEN_DATE, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        if (lastOpenDate == today) return

        val openedDays = preferences.getInt(KEY_STREAK_DAYS, 0) + 1

        preferences.edit {
            putString(KEY_LAST_OPEN_DATE, today.toString())
            putInt(KEY_STREAK_DAYS, openedDays)
        }
    }

    /** Ghi lại thời điểm mở khoá cho những achievement vừa đạt đủ mốc. */
    private fun syncUnlocks(definitions: List<AchievementDefinition> = cachedDefinitions) {
        val now = System.currentTimeMillis()
        val newlyUnlocked = definitions.filter { definition ->
            unlockedAt(definition.id) == null &&
                    definition.targetCount > 0 &&
                    currentCount(definition) >= definition.targetCount
        }
        if (newlyUnlocked.isEmpty()) return

        preferences.edit {
            newlyUnlocked.forEach { putLong(KEY_UNLOCKED_AT_PREFIX + it.id, now) }
        }
    }

    private fun currentCount(definition: AchievementDefinition): Int =
        when (val rule = definition.rule) {
            AchievementRule.ConsecutiveDaysOpened -> preferences.getInt(KEY_STREAK_DAYS, 0)

            AchievementRule.ArtworksCompleted -> readSet(KEY_COMPLETED_LEVELS).size

            is AchievementRule.ArtworkInCategory ->
                readSet(KEY_COMPLETED_LEVELS).count { categoryOf(it) == rule.assetCategory }

            is AchievementRule.CollectionCompleted ->
                readSet(KEY_COMPLETED_LEVELS).count { categoryOf(it) == rule.assetPath }

            AchievementRule.RealmsUnlocked -> readSet(KEY_UNLOCKED_REALMS).size

            AchievementRule.HintsUsed -> hintsUsed()

            AchievementRule.DailyArtworksCompleted -> readSet(KEY_COMPLETED_DAILY_LEVELS).size
        }

    /** Khoá có dạng "category/levelId", mà category của collection lại chứa sẵn dấu "/". */
    private fun categoryOf(levelKey: String): String = levelKey.substringBeforeLast('/')

    private fun hintsUsed(): Int = preferences.getInt(KEY_HINTS_USED, 0)

    private fun unlockedAt(achievementId: String): Long? =
        achievementStorageIds(achievementId)
            .firstNotNullOfOrNull { id ->
                preferences.getLong(KEY_UNLOCKED_AT_PREFIX + id, 0L).takeIf { it > 0L }
            }

    private fun isRewardClaimed(achievementId: String): Boolean =
        achievementStorageIds(achievementId).any { id ->
            preferences.getBoolean(KEY_REWARD_CLAIMED_PREFIX + id, false)
        }

    private fun achievementStorageIds(achievementId: String): List<String> {
        val legacyId = achievementId.replace('-', '_')
        return if (legacyId == achievementId) listOf(achievementId) else listOf(achievementId, legacyId)
    }

    private fun readSet(key: String): Set<String> =
        preferences.getStringSet(key, emptySet()).orEmpty()

    private fun addToSet(key: String, value: String) {
        val current = readSet(key)
        if (value in current) return
        // Bắt buộc tạo set mới: set do getStringSet trả về không được phép sửa trực tiếp.
        preferences.edit { putStringSet(key, current + value) }
    }
}
