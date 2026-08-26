package com.example.baseproject.data.remote

import com.google.gson.annotations.SerializedName

data class RemoteGroupDto(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val sortOrder: Int? = null,
    val thumbnailPath: String? = null,
    val previewImagePath: String? = null,
    val imageCount: Int? = null,
    val levelCount: Int? = null,
    val levelsCount: Int? = null,
    val description: String? = null,
    val isActive: Boolean? = null
) {
    val displayName: String get() = title ?: name ?: id
    val stableId: String get() = slug ?: id
    val resolvedImageCount: Int get() = imageCount ?: levelCount ?: levelsCount ?: 0
}

data class RemoteLevelSummaryDto(
    val id: String,
    val groupType: String,
    val groupId: String,
    val sortOrder: Int? = null,
    val thumbnailPath: String? = null,
    val isPremium: Boolean? = null,
    val updatedAt: String? = null
)

data class RemoteLevelDetailDto(
    val id: String,
    val groupType: String,
    val groupId: String,
    val sortOrder: Int? = null,
    val thumbnailPath: String? = null,
    val configPath: String? = null,
    val assets: List<RemoteLevelAssetDto> = emptyList(),
    val isActive: Boolean? = null,
    val isPremium: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class RemoteLevelAssetDto(
    val role: String,
    val path: String,
    @SerializedName("mimeType") val mimeType: String? = null
)

data class RemoteRealmDto(
    val id: String,
    val name: String,
    val animationPath: String? = null,
    val previewImagePath: String? = null,
    val unlockCost: Int = 0,
    val sortOrder: Int? = null,
    val isPremium: Boolean? = null,
    val updatedAt: String? = null
)

data class RemoteAchievementDto(
    val id: String,
    val title: String,
    val description: String,
    val ruleType: String,
    val ruleRefId: String? = null,
    val targetCount: Int = 0,
    val iconLockedPath: String? = null,
    val iconUnlockedPath: String? = null,
    val sortOrder: Int? = null,
    val isPremium: Boolean? = null,
    val updatedAt: String? = null
)
