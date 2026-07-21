package com.example.baseproject.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import com.example.baseproject.data.CentroidCalculator
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.utils.AssetImageResolver
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class AssetLevelRepositoryImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AssetLevelRepository {
    private val gson = Gson()

    override suspend fun loadAllLevels(): List<LevelConfig> = withContext(ioDispatcher) {
        val levels = mutableListOf<LevelConfig>()
        val assetManager = context.assets

        try {
            val categories = assetManager.list("") ?: return@withContext emptyList()
            for (category in categories) {
                if (category == "images" || category == "webkit" || category.contains(".")) continue

                val levelIds = assetManager.list(category) ?: continue
                for (levelId in levelIds) {
                    val configPath = "$category/$levelId/config.json"
                    try {
                        assetManager.open(configPath).use { inputStream ->
                            InputStreamReader(inputStream).use { reader ->
                                levels.add(gson.fromJson(reader, LevelConfig::class.java))
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        levels
    }

    override suspend fun loadLevelBundle(category: String, levelId: String): LevelBundle =
        withContext(ioDispatcher) {
            val assetManager = context.assets
            val config = assetManager.open("$category/$levelId/config.json").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    gson.fromJson(reader, LevelConfig::class.java)
                }
            }

            val lineBitmap = AssetImageResolver.openResolvedAsset(
                assetManager,
                "$category/$levelId/line"
            ).use { BitmapFactory.decodeStream(it) }
                ?: error("Failed to decode line bitmap for $category/$levelId")

            val maskBitmap = AssetImageResolver.openResolvedAsset(
                assetManager,
                "$category/$levelId/mask"
            ).use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                )
            } ?: error("Failed to decode mask bitmap for $category/$levelId")

            val detailBitmap = AssetImageResolver.openResolvedAssetOrNull(
                assetManager,
                "$category/$levelId/detail"
            )?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                )
            }

            val regions = if (config.hasRegionMetadata()) {
                config.toRegionDataList()
            } else {
                withContext(defaultDispatcher) {
                    CentroidCalculator.calculateCentroids(maskBitmap, config.palette)
                }
            }

            LevelBundle(
                config = config,
                lineBitmap = lineBitmap,
                maskBitmap = maskBitmap,
                detailBitmap = detailBitmap,
                regions = regions
            )
        }
}
