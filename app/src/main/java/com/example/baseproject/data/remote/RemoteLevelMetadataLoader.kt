package com.example.baseproject.data.remote

import android.util.Log
import com.example.baseproject.data.LevelConfig

class RemoteLevelMetadataLoader(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader
) {

    suspend fun loadGroupLevelConfigs(groupType: String, groupId: String): List<LevelConfig> {
        val response = api.groupLevels(groupType, groupId)
            .requireSuccessfulBody("/api/v1/groups/$groupType/$groupId/levels")
        if (!response.success) {
            throw RemoteApiException("Group levels for $groupType/$groupId returned success=false")
        }

        return response.data?.levels.orEmpty()
            .filter { it.groupType.equals(groupType, ignoreCase = true) && it.groupId == groupId }
            .sortedBy { it.sortOrder ?: Int.MAX_VALUE }
            .map { summary ->
                val summaryConfig = RemoteLevelMapper.levelSummaryToConfig(summary, assetLoader)
                runCatching { loadConfig(summaryConfig.id) }
                    .getOrElse { error ->
                        Log.w(TAG, "Failed to enrich level ${summaryConfig.id}; using summary", error)
                        summaryConfig
                    }
            }
    }

    suspend fun loadConfig(levelId: String): LevelConfig {
        val detail = api.levelDetail(levelId)
            .requireSuccessfulBody("/api/v1/levels/$levelId")
            .takeIf { it.success }
            ?.data
            ?.level
            ?: throw RemoteApiException("Level detail for $levelId is missing")

        val configPath = detail.configPath
            ?: throw RemoteApiException("Level $levelId does not include configPath")

        return RemoteLevelMapper.enrichConfig(
            config = assetLoader.downloadLevelConfig(configPath),
            detail = detail,
            assetLoader = assetLoader
        )
    }

    private companion object {
        const val TAG = "RemoteLevelMetadata"
    }
}

