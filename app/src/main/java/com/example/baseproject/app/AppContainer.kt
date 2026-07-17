package com.example.baseproject.app

import android.content.Context
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.AssetLevelRepositoryImpl
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.data.repository.PaintingProgressRepositoryImpl
import com.example.baseproject.data.repository.SettingsRepository
import com.example.baseproject.data.repository.SettingsRepositoryImpl
import com.example.baseproject.data.repository.ThumbnailRepository
import com.example.baseproject.data.repository.ThumbnailRepositoryImpl

interface AppContainer {
    val assetLevelRepository: AssetLevelRepository
    val paintingProgressRepository: PaintingProgressRepository
    val thumbnailRepository: ThumbnailRepository
    val settingsRepository: SettingsRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val assetLevelRepository: AssetLevelRepository by lazy {
        AssetLevelRepositoryImpl(appContext)
    }

    override val paintingProgressRepository: PaintingProgressRepository by lazy {
        PaintingProgressRepositoryImpl(
            appContext.getSharedPreferences("PaintingProgress", Context.MODE_PRIVATE)
        )
    }

    override val thumbnailRepository: ThumbnailRepository by lazy {
        ThumbnailRepositoryImpl(appContext)
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            appContext.getSharedPreferences("MyPreferences", Context.MODE_PRIVATE)
        )
    }
}
