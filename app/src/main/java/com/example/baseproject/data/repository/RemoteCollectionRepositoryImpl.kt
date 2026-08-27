package com.example.baseproject.data.repository

import android.util.Log
import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.remote.PixcolorApi
import com.example.baseproject.data.remote.RemoteApiException
import com.example.baseproject.data.remote.RemoteAssetLoader
import com.example.baseproject.data.remote.RemoteLevelMapper
import com.example.baseproject.data.remote.RemoteLevelMetadataLoader
import com.example.baseproject.data.remote.requireSuccessfulBody
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RemoteCollectionRepositoryImpl(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader,
    private val fallback: CollectionRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CollectionRepository {

    private val metadataLoader = RemoteLevelMetadataLoader(api, assetLoader)
    private val collectionsMutex = Mutex()
    private val collectionLevelsMutex = Mutex()
    private var cachedCollections: List<AlbumCollection>? = null
    private var cachedAllCollectionLevels: List<LevelConfig>? = null
    private val cachedCollectionLevels = mutableMapOf<String, List<LevelConfig>>()

    override suspend fun loadCollections(): List<AlbumCollection> =
        withFallback("loadCollections", fallbackAction = { loadCollections() }) {
            remoteCollections()
        }

    override suspend fun loadCollectionDetail(collectionId: String): CollectionDetail? =
        withFallback("loadCollectionDetail($collectionId)", fallbackAction = {
            loadCollectionDetail(collectionId)
        }) {
            val collection = remoteCollections().firstOrNull { it.id == collectionId }
                ?: AlbumCollection(
                    id = collectionId,
                    title = collectionId,
                    thumbnailUrl = "",
                    imageCount = 0
                )
            val levels = loadCollectionLevels(collectionId)
            CollectionDetail(
                collection = collection.copy(imageCount = collection.imageCount.takeIf { it > 0 } ?: levels.size),
                levels = levels
            )
        }

    override suspend fun loadAllCollectionLevels(): List<LevelConfig> =
        cachedAllCollectionLevels ?: loadAllCollectionLevelsFromRemote()

    private suspend fun loadAllCollectionLevelsFromRemote(): List<LevelConfig> =
        withFallback("loadAllCollectionLevels", fallbackAction = { loadAllCollectionLevels() }) {
            collectionLevelsMutex.withLock {
                cachedAllCollectionLevels?.let { return@withLock it }

                remoteCollections().flatMap { collection ->
                    loadCollectionLevels(collection.id)
                }.also { cachedAllCollectionLevels = it }
            }
        }

    private suspend fun remoteCollections(): List<AlbumCollection> {
        cachedCollections?.let { return it }

        return collectionsMutex.withLock {
            cachedCollections?.let { return@withLock it }

            val response = api.collections()
                .requireSuccessfulBody("/api/v1/collections")
                .takeIf { it.success }
                ?: throw RemoteApiException("Collections returned success=false")

            val collections = RemoteLevelMapper.sortGroups(response.data?.collections.orEmpty())
            if (collections.isEmpty()) {
                throw RemoteApiException("Collections response did not include active collections")
            }
            collectionsWithLevelCounts(collections).also { cachedCollections = it }
        }
    }

    private suspend fun collectionsWithLevelCounts(groups: List<com.example.baseproject.data.remote.RemoteGroupDto>): List<AlbumCollection> =
        coroutineScope {
            groups.map { group ->
                async {
                    val collection = RemoteLevelMapper.collectionFromGroup(group, assetLoader)
                    val levelCount = runCatching {
                        metadataLoader.loadGroupLevelSummaries(
                            RemoteLevelMapper.GROUP_TYPE_COLLECTION,
                            collection.id
                        ).size
                    }.getOrElse { error ->
                        Log.w(TAG, "Failed to count levels for collection ${collection.id}", error)
                        collection.imageCount
                    }
                    collection.copy(imageCount = levelCount)
                }
            }.awaitAll()
        }

    private suspend fun loadCollectionLevels(collectionId: String): List<LevelConfig> {
        cachedCollectionLevels[collectionId]?.let { return it }

        return metadataLoader.loadGroupLevelConfigs(
            RemoteLevelMapper.GROUP_TYPE_COLLECTION,
            collectionId
        ).also { cachedCollectionLevels[collectionId] = it }
    }

    private suspend fun <T> withFallback(
        operation: String,
        fallbackAction: (suspend CollectionRepository.() -> T)?,
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
        const val TAG = "RemoteCollectionRepo"
    }
}
