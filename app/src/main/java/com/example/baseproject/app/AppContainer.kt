package com.example.baseproject.app

import android.content.Context
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.AchievementRepository
import com.example.baseproject.data.repository.AchievementRepositoryImpl
import com.example.baseproject.data.repository.AssetCollectionRepositoryImpl
import com.example.baseproject.data.repository.AssetLevelRepositoryImpl
import com.example.baseproject.data.repository.CollectionRepository
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.data.repository.PaintingProgressRepositoryImpl
import com.example.baseproject.data.repository.PaintDropRepository
import com.example.baseproject.data.repository.PaintDropRepositoryImpl
import com.example.baseproject.data.repository.SettingsRepository
import com.example.baseproject.data.repository.SettingsRepositoryImpl
import com.example.baseproject.data.repository.ThumbnailRepository
import com.example.baseproject.data.repository.ThumbnailRepositoryImpl

interface AppContainer {
    val assetLevelRepository: AssetLevelRepository
    val collectionRepository: CollectionRepository
    val paintingProgressRepository: PaintingProgressRepository
    val thumbnailRepository: ThumbnailRepository
    val settingsRepository: SettingsRepository
    val achievementRepository: AchievementRepository
    val paintDropRepository: PaintDropRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val assetLevelRepository: AssetLevelRepository by lazy {
        AssetLevelRepositoryImpl(appContext)
    }

    override val collectionRepository: CollectionRepository by lazy {
        AssetCollectionRepositoryImpl(appContext)
    }

    override val paintingProgressRepository: PaintingProgressRepository by lazy {
        PaintingProgressRepositoryImpl(
            appContext.getSharedPreferences("PaintingProgress", Context.MODE_PRIVATE)
        )
    }

    override val thumbnailRepository: ThumbnailRepository by lazy {
        ThumbnailRepositoryImpl(appContext)
    }

    override val achievementRepository: AchievementRepository by lazy {
        AchievementRepositoryImpl(
            appContext.getSharedPreferences("Achievements", Context.MODE_PRIVATE)
        )
    }

    override val paintDropRepository: PaintDropRepository by lazy {
        PaintDropRepositoryImpl(
            appContext.getSharedPreferences("PaintDrops", Context.MODE_PRIVATE)
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            appContext.getSharedPreferences("MyPreferences", Context.MODE_PRIVATE)
        )
    }
}
