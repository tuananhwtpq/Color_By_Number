package com.example.baseproject.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class AssetRepository(private val context: Context) {
    private val gson = Gson()

    suspend fun loadAllLevels(): List<LevelConfig> = withContext(Dispatchers.IO) {
        val levels = mutableListOf<LevelConfig>()
        val assetManager = context.assets

        try {
            // Danh mục (Cartoons, Mandala, etc.)
            val categories = assetManager.list("") ?: return@withContext emptyList()
            for (category in categories) {
                // Bỏ qua các thư mục hệ thống hoặc file rác
                if (category == "images" || category == "webkit" || category.contains(".")) continue

                val levelIds = assetManager.list(category) ?: continue
                for (levelId in levelIds) {
                    val configPath = "$category/$levelId/config.json"
                    try {
                        val inputStream = assetManager.open(configPath)
                        val reader = InputStreamReader(inputStream)
                        val config = gson.fromJson(reader, LevelConfig::class.java)
                        levels.add(config)
                        reader.close()
                    } catch (e: Exception) {
                        // File không tồn tại hoặc lỗi parse, bỏ qua
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext levels
    }
}
