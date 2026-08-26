package com.example.baseproject.data.repository

import android.util.Log
import com.example.baseproject.data.AchievementCatalog
import com.example.baseproject.data.AchievementDefinition
import com.example.baseproject.data.remote.PixcolorApi
import com.example.baseproject.data.remote.RemoteAchievementMapper
import com.example.baseproject.data.remote.RemoteApiException
import com.example.baseproject.data.remote.RemoteAssetLoader
import com.example.baseproject.data.remote.requireSuccessfulBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RemoteAchievementDefinitionProvider(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun loadDefinitions(): List<AchievementDefinition> = withContext(ioDispatcher) {
        runCatching {
            val response = api.achievements()
                .requireSuccessfulBody("/api/v1/achievements")
                .takeIf { it.success }
                ?: throw RemoteApiException("Achievements returned success=false")

            val definitions = response.data?.achievements.orEmpty()
                .mapNotNull { RemoteAchievementMapper.toDefinition(it, assetLoader) }
                .sortedBy { it.sortOrder }
            if (definitions.isEmpty()) {
                throw RemoteApiException("Achievements response did not include usable definitions")
            }
            definitions
        }.getOrElse { error ->
            Log.w(TAG, "loadDefinitions failed; falling back to local catalog", error)
            AchievementCatalog.definitions
        }
    }

    private companion object {
        const val TAG = "RemoteAchievementDefs"
    }
}
