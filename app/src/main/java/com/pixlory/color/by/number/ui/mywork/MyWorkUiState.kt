package com.pixlory.color.by.number.ui.mywork

import com.pixlory.color.by.number.data.LevelConfig

data class MyWorkUiState(
    val isLoading: Boolean = true,
    val inProgressLevels: List<LevelConfig> = emptyList(),
    val completedLevels: List<LevelConfig> = emptyList()
)
