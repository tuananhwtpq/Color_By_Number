package com.example.baseproject.ui.library

import com.example.baseproject.data.LevelConfig

data class LibraryUiState(
    val isLoading: Boolean = true,
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val visibleLevels: List<LevelConfig> = emptyList(),
    val errorMessage: String? = null
)
