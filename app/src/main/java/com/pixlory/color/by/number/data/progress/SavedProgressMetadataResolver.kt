package com.pixlory.color.by.number.data.progress

import com.pixlory.color.by.number.data.LevelConfig
import com.pixlory.color.by.number.data.progressRegionCount
import com.pixlory.color.by.number.data.repository.AssetLevelRepository
import com.pixlory.color.by.number.data.repository.PaintingProgressRepository

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
