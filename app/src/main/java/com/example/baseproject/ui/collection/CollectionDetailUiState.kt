package com.example.baseproject.ui.collection

import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.data.LevelConfig

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    val collection: AlbumCollection? = null,
    val levels: List<LevelConfig> = emptyList(),
    /** Số tranh đã tô xong trong collection — hiển thị ở tvNumberCountDone dạng "2/10". */
    val completedCount: Int = 0,
    val errorMessage: String? = null
)
