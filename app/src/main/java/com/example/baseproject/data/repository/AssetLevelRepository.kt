package com.example.baseproject.data.repository

import android.graphics.Bitmap
import com.caverock.androidsvg.SVG
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.RegionData

interface AssetLevelRepository {
    suspend fun loadAllLevels(): List<LevelConfig>
    suspend fun refreshAllLevels(): List<LevelConfig> = loadAllLevels()
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
