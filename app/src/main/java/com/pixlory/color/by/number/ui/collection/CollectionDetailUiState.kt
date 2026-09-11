package com.pixlory.color.by.number.ui.collection

import com.pixlory.color.by.number.data.AlbumCollection
import com.pixlory.color.by.number.data.LevelConfig

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    val collection: AlbumCollection? = null,
    val levels: List<LevelConfig> = emptyList(),
    /** Số tranh đã tô xong trong collection — hiển thị ở tvNumberCountDone dạng "2/10". */
    val completedCount: Int = 0,
    val errorMessage: String? = null
)
