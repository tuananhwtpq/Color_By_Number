package com.example.baseproject.data.repository

import com.example.baseproject.data.Achievement

interface AchievementRepository {
    fun loadAchievements(): List<Achievement>

    fun claimReward(achievementId: String)

    /** Ghi nhận một hành động của người dùng và mở khoá achievement tương ứng nếu vừa đủ điều kiện. */
    fun track(event: AchievementEvent)
}

/**
 * Các hành động có thể làm tăng tiến độ achievement. Mỗi hành động chỉ nên được báo từ đúng
 * một chỗ trong app để logic không bị rải rác.
 */
sealed interface AchievementEvent {
    /** Người dùng mở app — chỉ tính một lần mỗi ngày. */
    data object AppOpened : AchievementEvent

    /**
     * Tô xong một bức tranh. [category] là đường dẫn asset ("Animal" hoặc
     * "Collection/Cat moments"), khớp với category dùng ở mọi nơi khác.
     */
    data class ArtworkCompleted(
        val category: String,
        val levelId: String,
        val isDaily: Boolean = false
    ) : AchievementEvent

    /** Một gợi ý đã thực sự được dùng (có vùng để chỉ, không phải bấm hụt). */
    data object HintUsed : AchievementEvent

    data class RealmUnlocked(val realmId: String) : AchievementEvent
}
