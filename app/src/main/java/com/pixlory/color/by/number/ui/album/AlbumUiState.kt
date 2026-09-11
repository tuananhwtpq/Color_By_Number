package com.pixlory.color.by.number.ui.album

import com.pixlory.color.by.number.data.AlbumCollection

data class AlbumUiState(
    val isLoading: Boolean = true,
    val collections: List<AlbumCollection> = emptyList(),
    val errorMessage: String? = null
)
