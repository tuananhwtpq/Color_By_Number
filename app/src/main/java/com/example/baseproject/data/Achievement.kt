package com.example.baseproject.data

/**
 * Một achievement kèm tiến độ hiện tại, dùng để hiển thị.
 *
 * [unlockedAtMillis] khác null nghĩa là đã mở khoá — và **giữ vĩnh viễn**: kể cả sau này người
 * dùng reset tranh làm tiến độ tụt xuống thì achievement vẫn tính là hoàn thành, ngày mở khoá
 * cũng là ngày hiển thị trong dialog "đã hoàn thành".
 */
data class Achievement(
    val definition: AchievementDefinition,
    val currentCount: Int,
    val unlockedAtMillis: Long?,
    val isRewardClaimed: Boolean
) {
    val id: String get() = definition.id
    val targetCount: Int get() = definition.targetCount
    val isCompleted: Boolean get() = unlockedAtMillis != null
}
