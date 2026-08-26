package com.example.baseproject.data.repository

import android.util.Log
import com.example.baseproject.data.CentroidCalculator
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.remote.PixcolorApi
import com.example.baseproject.data.remote.RemoteApiException
import com.example.baseproject.data.remote.RemoteAssetLoader
import com.example.baseproject.data.remote.RemoteLevelDetailDto
import com.example.baseproject.data.remote.RemoteLevelMapper
import com.example.baseproject.data.remote.RemoteLevelMetadataLoader
import com.example.baseproject.data.remote.requireSuccessfulBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RemoteLevelRepositoryImpl(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader,
    private val fallback: AssetLevelRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AssetLevelRepository {

    private val metadataLoader = RemoteLevelMetadataLoader(api, assetLoader)

    override suspend fun loadAllLevels(): List<LevelConfig> =
        withFallback("loadAllLevels", fallbackAction = { loadAllLevels() }) {
            val response = api.categories()
                .requireSuccessfulBody("/api/v1/categories")
                .takeIf { it.success }
                ?: throw RemoteApiException("Categories returned success=false")

            val categories = RemoteLevelMapper.sortGroups(response.data?.categories.orEmpty())
            if (categories.isEmpty()) {
                throw RemoteApiException("Categories response did not include active categories")
            }

            categories.flatMap { category ->
                metadataLoader.loadGroupLevelConfigs(
                    RemoteLevelMapper.GROUP_TYPE_CATEGORY,
                    category.stableId,
                    category.displayName
                )
            }
        }

    override suspend fun loadLevelBundle(category: String, levelId: String): LevelBundle =
        withFallback("loadLevelBundle($category/$levelId)", fallbackAction = {
            loadLevelBundle(category, levelId)
        }) {
            val detail = api.levelDetail(levelId)
                .requireSuccessfulBody("/api/v1/levels/$levelId")
                .takeIf { it.success }
                ?.data
                ?.level
                ?: throw RemoteApiException("Level detail for $levelId is missing")

            val configPath = detail.configPath
                ?: throw RemoteApiException("Level $levelId does not include configPath")
            val config = RemoteLevelMapper.enrichConfig(
                config = assetLoader.downloadLevelConfig(configPath),
                detail = detail,
                assetLoader = assetLoader
            )

            val lineUrl = requireAsset(detail, "LINE")
            val maskUrl = requireAsset(detail, "MASK")
            val displayLineUrl = assetPath(detail, "DISPLAY_LINE") ?: lineUrl
            val detailUrl = assetPath(detail, "DETAIL")

            val lineBitmap = assetLoader.downloadBitmap(lineUrl, "LINE")
            val displayLineBitmap = assetLoader.downloadBitmap(displayLineUrl, "DISPLAY_LINE")
            val maskBitmap = assetLoader.downloadBitmap(maskUrl, "MASK")
            val detailBitmap = detailUrl?.let { assetLoader.downloadBitmap(it, "DETAIL") }

            val regions = if (config.hasRegionMetadata()) {
                config.toRegionDataList()
            } else {
                withContext(defaultDispatcher) {
                    CentroidCalculator.calculateCentroids(maskBitmap, config.palette)
                }
            }

            LevelBundle(
                config = config,
                lineBitmap = lineBitmap,
                displayLineBitmap = displayLineBitmap,
                maskBitmap = maskBitmap,
                detailBitmap = detailBitmap,
                regions = regions
            )
        }

    private fun requireAsset(detail: RemoteLevelDetailDto, role: String): String =
        assetPath(detail, role) ?: throw RemoteApiException("Level ${detail.id} is missing $role asset")

    private fun assetPath(
        detail: RemoteLevelDetailDto,
        role: String
    ): String? =
        detail.assets.firstOrNull { it.role.equals(role, ignoreCase = true) }?.path

    private suspend fun <T> withFallback(
        operation: String,
        fallbackAction: (suspend AssetLevelRepository.() -> T)?,
        remoteAction: suspend () -> T
    ): T = withContext(ioDispatcher) {
        runCatching { remoteAction() }
            .getOrElse { error ->
                Log.w(TAG, "$operation failed; falling back to local data", error)
                val fallbackRepository = fallback
                if (fallbackRepository != null && fallbackAction != null) {
                    fallbackRepository.fallbackAction()
                } else {
                    throw error
                }
            }
    }

    private companion object {
        const val TAG = "RemoteLevelRepository"
    }
}
