package com.example.baseproject.data.remote

import android.util.Log
import com.example.baseproject.data.LevelConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class RemoteLevelMetadataLoader(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader,
    private val detailRequestLimit: Int = DEFAULT_DETAIL_REQUEST_LIMIT
) {

    suspend fun loadGroupLevelConfigs(
        groupType: String,
        groupId: String,
        groupName: String? = null
    ): List<LevelConfig> {
        val summaries = loadGroupLevelSummaries(groupType, groupId)
        val requestLimiter = Semaphore(detailRequestLimit.coerceAtLeast(1))
        return coroutineScope {
            summaries.map { summary ->
                async {
                    val summaryConfig = RemoteLevelMapper.levelSummaryToConfig(
                        dto = summary,
                        assetLoader = assetLoader,
                        groupName = groupName
                    )
                    requestLimiter.withPermit {
                        runCatching { loadConfig(summaryConfig) }
                            .getOrElse { error ->
                                Log.w(TAG, "Failed to enrich level ${summaryConfig.id}; using summary", error)
                                summaryConfig
                            }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun loadGroupLevelSummaries(
        groupType: String,
        groupId: String
    ): List<RemoteLevelSummaryDto> {
        val response = api.groupLevels(groupType, groupId)
            .requireSuccessfulBody("/api/v1/groups/$groupType/$groupId/levels")
        if (!response.success) {
            throw RemoteApiException("Group levels for $groupType/$groupId returned success=false")
        }

        return response.data?.levels.orEmpty()
            .filter { it.groupType.equals(groupType, ignoreCase = true) && it.groupId == groupId }
            .sortedBy { it.sortOrder ?: Int.MAX_VALUE }
    }

    suspend fun loadConfig(levelId: String): LevelConfig {
        return loadConfig(
            LevelConfig(
                id = levelId,
                name = levelId,
                category = "",
                width = 0,
                height = 0,
                palette = emptyList()
            )
        )
    }

    private suspend fun loadConfig(summaryConfig: LevelConfig): LevelConfig {
        val detail = api.levelDetail(summaryConfig.id)
            .requireSuccessfulBody("/api/v1/levels/${summaryConfig.id}")
            .takeIf { it.success }
            ?.data
            ?.level
            ?: throw RemoteApiException("Level detail for ${summaryConfig.id} is missing")

        val configPath = detail.configPath
            ?: throw RemoteApiException("Level ${summaryConfig.id} does not include configPath")

        val downloadedConfig = assetLoader.downloadLevelConfig(configPath)
        return RemoteLevelMapper.enrichConfig(
            config = downloadedConfig.copy(
                categoryName = summaryConfig.categoryName ?: downloadedConfig.categoryName,
                thumbnailUrl = downloadedConfig.thumbnailUrl ?: summaryConfig.thumbnailUrl,
                sortOrder = downloadedConfig.sortOrder ?: summaryConfig.sortOrder,
                isPremium = downloadedConfig.isPremium ?: summaryConfig.isPremium
            ),
            detail = detail,
            assetLoader = assetLoader
        )
    }

    private companion object {
        const val TAG = "RemoteLevelMetadata"
        const val DEFAULT_DETAIL_REQUEST_LIMIT = 3
    }
}
