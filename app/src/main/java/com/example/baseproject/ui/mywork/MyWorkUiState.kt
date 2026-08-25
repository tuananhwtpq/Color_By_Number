package com.example.baseproject.ui.mywork

import com.example.baseproject.data.LevelConfig

data class MyWorkUiState(
    val isLoading: Boolean = true,
    val inProgressLevels: List<LevelConfig> = emptyList(),
    val completedLevels: List<LevelConfig> = emptyList()
)
