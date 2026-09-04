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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class RemoteLevelRepositoryImpl(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader,
    private val fallback: AssetLevelRepository? = null,
    private val metadataCacheFile: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val categoryRequestLimit: Int = DEFAULT_CATEGORY_REQUEST_LIMIT,
    private val gson: Gson = Gson()
) : AssetLevelRepository {

    private val metadataLoader = RemoteLevelMetadataLoader(api, assetLoader)
    private val levelsMutex = Mutex()
    private var cachedAllLevels: List<LevelConfig>? = null
    private var lastRemoteLoadedAtMillis: Long = 0L

    override suspend fun loadAllLevels(): List<LevelConfig> =
        cachedAllLevels ?: withContext(ioDispatcher) {
            readCachedLevels()?.also { cachedAllLevels = it }
        } ?: loadAllLevelsFromRemote()

    override suspend fun refreshAllLevels(): List<LevelConfig> =
        cachedAllLevels
            ?.takeIf { System.currentTimeMillis() - lastRemoteLoadedAtMillis < RECENT_REMOTE_LOAD_WINDOW_MILLIS }
            ?: loadAllLevelsFromRemote(forceRefresh = true)

    private suspend fun loadAllLevelsFromRemote(forceRefresh: Boolean = false): List<LevelConfig> =
        withFallback("loadAllLevels", fallbackAction = { loadAllLevels() }) {
            levelsMutex.withLock {
                if (!forceRefresh) {
                    cachedAllLevels?.let { return@withLock it }
                } else {
                    cachedAllLevels
                        ?.takeIf {
                            System.currentTimeMillis() - lastRemoteLoadedAtMillis <
                                RECENT_REMOTE_LOAD_WINDOW_MILLIS
                        }
                        ?.let { return@withLock it }
                }

                val response = api.categories()
                    .requireSuccessfulBody("/api/v1/categories")
                    .takeIf { it.success }
                    ?: throw RemoteApiException("Categories returned success=false")

                val categories = RemoteLevelMapper.sortGroups(response.data?.categories.orEmpty())
                if (categories.isEmpty()) {
                    throw RemoteApiException("Categories response did not include active categories")
                }

                val requestLimiter = Semaphore(categoryRequestLimit.coerceAtLeast(1))
                coroutineScope {
                    categories.map { category ->
                        async {
                            requestLimiter.withPermit {
                                metadataLoader.loadGroupLevelConfigs(
                                    RemoteLevelMapper.GROUP_TYPE_CATEGORY,
                                    category.stableId,
                                    category.displayName
                                )
                            }
                        }
                    }.awaitAll().flatten()
                }.also { levels ->
                    cachedAllLevels = levels
                    lastRemoteLoadedAtMillis = System.currentTimeMillis()
                    writeCachedLevels(levels)
                }
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

            val bitmaps = coroutineScope {
                val lineDeferred = async { assetLoader.downloadBitmap(lineUrl, "LINE") }
                val displayLineDeferred = async {
                    if (displayLineUrl == lineUrl) {
                        lineDeferred.await()
                    } else {
                        assetLoader.downloadBitmap(displayLineUrl, "DISPLAY_LINE")
                    }
                }
                val maskDeferred = async { assetLoader.downloadBitmap(maskUrl, "MASK") }
                val detailDeferred = detailUrl?.let { async { assetLoader.downloadBitmap(it, "DETAIL") } }

                LevelBitmaps(
                    line = lineDeferred.await(),
                    displayLine = displayLineDeferred.await(),
                    mask = maskDeferred.await(),
                    detail = detailDeferred?.await()
                )
            }

            val regions = if (config.hasRegionMetadata()) {
                config.toRegionDataList()
            } else {
                withContext(defaultDispatcher) {
                    CentroidCalculator.calculateCentroids(bitmaps.mask, config.palette)
                }
            }

            LevelBundle(
                config = config,
                lineBitmap = bitmaps.line,
                displayLineBitmap = bitmaps.displayLine,
                maskBitmap = bitmaps.mask,
                detailBitmap = bitmaps.detail,
                regions = regions
            )
        }

    private data class LevelBitmaps(
        val line: android.graphics.Bitmap,
        val displayLine: android.graphics.Bitmap,
        val mask: android.graphics.Bitmap,
        val detail: android.graphics.Bitmap?
    )

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
        const val DEFAULT_CATEGORY_REQUEST_LIMIT = 2
        const val RECENT_REMOTE_LOAD_WINDOW_MILLIS = 30_000L
        val LEVEL_LIST_TYPE = object : TypeToken<List<LevelConfig>>() {}.type
    }

    private fun readCachedLevels(): List<LevelConfig>? {
        val file = metadataCacheFile ?: return null
        if (!file.isFile || file.length() <= 0L) return null

        return runCatching {
            file.reader().use { reader ->
                gson.fromJson<List<LevelConfig>>(reader, LEVEL_LIST_TYPE)
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeCachedLevels(levels: List<LevelConfig>) {
        val file = metadataCacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(gson.toJson(levels))
            if (file.exists() && !file.delete()) {
                tmpFile.delete()
                return@runCatching
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        }
    }
}
