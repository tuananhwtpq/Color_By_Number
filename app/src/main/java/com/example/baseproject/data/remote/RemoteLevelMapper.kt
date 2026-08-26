package com.example.baseproject.data.remote

import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.data.LevelAssets
import com.example.baseproject.data.LevelConfig

object RemoteLevelMapper {
    const val GROUP_TYPE_CATEGORY = "CATEGORY"
    const val GROUP_TYPE_COLLECTION = "COLLECTION"

    fun levelSummaryToConfig(
        dto: RemoteLevelSummaryDto,
        assetLoader: RemoteAssetLoader,
        groupName: String? = null
    ): LevelConfig =
        LevelConfig(
            id = dto.id,
            name = dto.id,
            category = categoryKey(dto.groupType, dto.groupId),
            groupType = dto.groupType,
            groupId = dto.groupId,
            categoryName = groupName,
            thumbnailUrl = assetLoader.resolveUrl(dto.thumbnailPath),
            sortOrder = dto.sortOrder,
            isPremium = dto.isPremium,
            width = 0,
            height = 0,
            palette = emptyList()
        )

    fun enrichConfig(
        config: LevelConfig,
        detail: RemoteLevelDetailDto,
        assetLoader: RemoteAssetLoader
    ): LevelConfig =
        config.copy(
            id = detail.id,
            name = config.name.takeIf { it.isNotBlank() } ?: detail.id,
            category = categoryKey(detail.groupType, detail.groupId),
            groupType = detail.groupType,
            groupId = detail.groupId,
            categoryName = config.categoryName,
            thumbnailUrl = assetLoader.resolveUrl(detail.thumbnailPath)
                ?: config.thumbnailUrl
                ?: assetUrl(detail.assets, "THUMBNAIL", assetLoader),
            sortOrder = detail.sortOrder ?: config.sortOrder,
            isPremium = detail.isPremium ?: config.isPremium,
            assets = LevelAssets(
                sourceLine = assetUrl(detail.assets, "LINE", assetLoader),
                segmentationLine = assetUrl(detail.assets, "MASK", assetLoader),
                displayLine = assetUrl(detail.assets, "DISPLAY_LINE", assetLoader),
                line = assetUrl(detail.assets, "LINE", assetLoader),
                lineRender = assetUrl(detail.assets, "DISPLAY_LINE", assetLoader),
                mask = assetUrl(detail.assets, "MASK", assetLoader),
                preview = assetUrl(detail.assets, "THUMBNAIL", assetLoader),
                detail = assetUrl(detail.assets, "DETAIL", assetLoader)
            )
        )

    fun collectionFromGroup(group: RemoteGroupDto, assetLoader: RemoteAssetLoader): AlbumCollection =
        AlbumCollection(
            id = group.stableId,
            title = group.displayName,
            description = group.description,
            thumbnailUrl = assetLoader.resolveUrl(group.previewImagePath ?: group.thumbnailPath).orEmpty(),
            imageCount = group.resolvedImageCount
        )

    fun sortGroups(groups: List<RemoteGroupDto>): List<RemoteGroupDto> =
        groups.filter { it.isActive != false }
            .sortedWith(compareBy<RemoteGroupDto> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.displayName })

    fun categoryKey(groupType: String?, groupId: String): String =
        if (groupType.equals(GROUP_TYPE_COLLECTION, ignoreCase = true)) {
            "Collection/$groupId"
        } else {
            groupId
        }

    private fun assetUrl(
        assets: List<RemoteLevelAssetDto>,
        role: String,
        assetLoader: RemoteAssetLoader
    ): String? =
        assets.firstOrNull { it.role.equals(role, ignoreCase = true) }
            ?.path
            ?.let(assetLoader::resolveUrl)
}
