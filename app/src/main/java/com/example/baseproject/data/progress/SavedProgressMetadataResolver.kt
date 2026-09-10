package com.example.baseproject.data.progress

import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progressRegionCount
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.PaintingProgressRepository

class SavedProgressMetadataResolver(
    private val assetLevelRepository: AssetLevelRepository,
    private val paintingProgressRepository: PaintingProgressRepository
) {
    suspend fun resolve(levels: List<LevelConfig>): List<LevelConfig> = levels.map { level ->
        if (level.progressRegionCount() > 0 ||
            paintingProgressRepository.loadProgress(level.category, level.id).isEmpty()
        ) {
            level
        } else {
            assetLevelRepository.resolveProgressMetadata(level)
        }
    }
}
