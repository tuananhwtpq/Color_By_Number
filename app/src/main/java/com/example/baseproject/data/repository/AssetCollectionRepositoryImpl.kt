package com.example.baseproject.data.repository

import android.content.Context
import android.content.res.AssetManager
import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.utils.AssetImageResolver
import com.example.baseproject.utils.Constants
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class AssetCollectionRepositoryImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CollectionRepository {

    private val gson = Gson()

    override suspend fun loadCollections(): List<AlbumCollection> = withContext(ioDispatcher) {
        val assetManager = context.assets
        listCollectionNames(assetManager)
            .mapNotNull { name -> readCollection(assetManager, name) }
            .sortedBy { it.title }
    }

    override suspend fun loadCollectionDetail(collectionId: String): CollectionDetail? =
        withContext(ioDispatcher) {
            val assetManager = context.assets
            val collection = readCollection(assetManager, collectionId) ?: return@withContext null
            CollectionDetail(
                collection = collection,
                levels = readLevels(assetManager, collectionId)
            )
        }

    override suspend fun loadAllCollectionLevels(): List<LevelConfig> = withContext(ioDispatcher) {
        val assetManager = context.assets
        listCollectionNames(assetManager).flatMap { name -> readLevels(assetManager, name) }
    }

    private fun listCollectionNames(assetManager: AssetManager): List<String> = try {
        assetManager.list(Constants.ASSET_COLLECTION_ROOT)?.toList().orEmpty()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    private fun readCollection(assetManager: AssetManager, name: String): AlbumCollection? {
        val collectionPath = collectionPath(name)
        // Thumbnail là thứ bắt buộc để hiển thị item: không có thì bỏ qua collection đó
        // thay vì để RecyclerView hiện ô trống.
        val thumbnailUrl = try {
            AssetImageResolver.toAndroidAssetUri(assetManager, "$collectionPath/thumbnail")
        } catch (_: Exception) {
            return null
        }

        return AlbumCollection(
            id = name,
            title = name,
            thumbnailUrl = thumbnailUrl,
            imageCount = countLevels(assetManager, collectionPath)
        )
    }

    // Đếm bằng cách thử mở config.json thay vì tin vào tên folder, nên các file nằm cạnh
    // (thumbnail.png, file tạm...) không bị tính nhầm thành một bức tranh.
    private fun countLevels(assetManager: AssetManager, collectionPath: String): Int =
        listLevelIds(assetManager, collectionPath).count { levelId ->
            try {
                assetManager.open("$collectionPath/$levelId/config.json").close()
                true
            } catch (_: Exception) {
                false
            }
        }

    private fun readLevels(assetManager: AssetManager, name: String): List<LevelConfig> {
        val collectionPath = collectionPath(name)
        return listLevelIds(assetManager, collectionPath).mapNotNull { levelId ->
            try {
                assetManager.open("$collectionPath/$levelId/config.json").use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        // config.json ghi category = tên collection ("Cat moments") trong khi
                        // asset thật nằm ở "Collection/Cat moments". Toàn bộ code phía sau
                        // (LevelAdapter, PaintActivity, key lưu tiến trình) dùng category như
                        // đường dẫn asset, nên ghi đè lại thành path đầy đủ ngay tại đây.
                        gson.fromJson(reader, LevelConfig::class.java)
                            .copy(category = collectionPath)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.id }
    }

    private fun listLevelIds(assetManager: AssetManager, collectionPath: String): List<String> =
        try {
            assetManager.list(collectionPath)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

    private fun collectionPath(name: String) = "${Constants.ASSET_COLLECTION_ROOT}/$name"
}
