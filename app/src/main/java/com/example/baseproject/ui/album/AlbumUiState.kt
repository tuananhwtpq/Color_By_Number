package com.example.baseproject.ui.album

import com.example.baseproject.data.AlbumCollection

data class AlbumUiState(
    val isLoading: Boolean = true,
    val collections: List<AlbumCollection> = emptyList(),
    val errorMessage: String? = null
)
