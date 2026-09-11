package com.pixlory.color.by.number.data.repository

import android.graphics.Bitmap
import com.caverock.androidsvg.SVG
import com.pixlory.color.by.number.data.LevelConfig
import com.pixlory.color.by.number.data.RegionData

interface AssetLevelRepository {
    suspend fun loadAllLevels(): List<LevelConfig>
    suspend fun refreshAllLevels(): List<LevelConfig> = loadAllLevels()
    /** Resolves the region count needed to calculate saved painting progress. */
    suspend fun resolveProgressMetadata(level: LevelConfig): LevelConfig = level
    suspend fun loadLevelBundle(category: String, levelId: String): LevelBundle
}

data class LevelBundle(
    val config: LevelConfig,
    val lineBitmap: Bitmap,
    val displayLineBitmap: Bitmap,
    val displayLineSvg: SVG? = null,
    val maskBitmap: Bitmap,
    val detailBitmap: Bitmap?,
    val fillCoverageBitmap: Bitmap? = null,
    val regions: List<RegionData>
)
