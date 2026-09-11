package com.pixlory.color.by.number.data.remote

import com.pixlory.color.by.number.data.AchievementDefinition
import com.pixlory.color.by.number.data.AchievementRule
import com.pixlory.color.by.number.utils.Constants

object RemoteAchievementMapper {
    fun toDefinition(
        dto: RemoteAchievementDto,
        assetLoader: RemoteAssetLoader
    ): AchievementDefinition? {
        val rule = ruleFrom(dto.ruleType, dto.ruleRefId) ?: return null
        return AchievementDefinition(
            id = dto.id,
            title = dto.title,
            description = dto.description,
            targetCount = dto.targetCount,
            rule = rule,
            iconUrl = assetLoader.resolveUrl(dto.iconLockedPath),
            iconCompletedUrl = assetLoader.resolveUrl(dto.iconUnlockedPath),
            sortOrder = dto.sortOrder ?: Int.MAX_VALUE,
            isPremium = dto.isPremium == true
        )
    }

    private fun ruleFrom(ruleType: String, ruleRefId: String?): AchievementRule? =
        when (ruleType.uppercase()) {
            "CONSECUTIVE_DAYS_OPENED" -> AchievementRule.ConsecutiveDaysOpened
            "ARTWORKS_COMPLETED" -> AchievementRule.ArtworksCompleted
            "ARTWORK_IN_CATEGORY", "ARTWORKS_IN_CATEGORY" ->
                ruleRefId?.let(AchievementRule::ArtworkInCategory)
            "COLLECTION_COMPLETED" ->
                ruleRefId?.let { AchievementRule.CollectionCompleted(it.removePrefix("${Constants.ASSET_COLLECTION_ROOT}/")) }
            "REALMS_UNLOCKED" -> AchievementRule.RealmsUnlocked
            "HINTS_USED" -> AchievementRule.HintsUsed
            else -> null
        }
}
