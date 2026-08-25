package com.example.baseproject.data.repository

import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.data.LevelConfig

interface CollectionRepository {
    suspend fun loadCollections(): List<AlbumCollection>

    /** Thông tin header + danh sách tranh của một collection. Null nếu collection không tồn tại. */
    suspend fun loadCollectionDetail(collectionId: String): CollectionDetail?

    /** Toàn bộ tranh của mọi collection — dùng cho tab My Work. */
    suspend fun loadAllCollectionLevels(): List<LevelConfig>
}

data class CollectionDetail(
    val collection: AlbumCollection,
    val levels: List<LevelConfig>
)
