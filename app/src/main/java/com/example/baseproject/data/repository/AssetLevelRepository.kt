package com.example.baseproject.data.repository

import android.graphics.Bitmap
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.RegionData

interface AssetLevelRepository {
    suspend fun loadAllLevels(): List<LevelConfig>
    suspend fun loadLevelBundle(category: String, levelId: String): LevelBundle
}

data class LevelBundle(
    val config: LevelConfig,
    val lineBitmap: Bitmap,
    val maskBitmap: Bitmap,
    val detailBitmap: Bitmap?,
    val regions: List<RegionData>
)
