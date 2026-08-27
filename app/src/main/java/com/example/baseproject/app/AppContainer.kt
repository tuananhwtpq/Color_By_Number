package com.example.baseproject.app

import android.content.Context
import com.example.baseproject.data.TimelapseVideoCache
import com.example.baseproject.data.remote.PixcolorApiClient
import com.example.baseproject.data.remote.RemoteAssetLoader
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
import com.example.baseproject.data.repository.LocalRealmRepositoryImpl
import com.example.baseproject.data.repository.RemoteCollectionRepositoryImpl
import com.example.baseproject.data.repository.RemoteAchievementDefinitionProvider
import com.example.baseproject.data.repository.RemoteLevelRepositoryImpl
import com.example.baseproject.data.repository.RemoteRealmRepositoryImpl
import com.example.baseproject.data.repository.RealmRepository
import com.example.baseproject.data.repository.SettingsRepository
import com.example.baseproject.data.repository.SettingsRepositoryImpl
import com.example.baseproject.data.repository.ThumbnailRepository
import com.example.baseproject.data.repository.ThumbnailRepositoryImpl
import java.io.File

interface AppContainer {
    val assetLevelRepository: AssetLevelRepository
    val collectionRepository: CollectionRepository
    val paintingProgressRepository: PaintingProgressRepository
    val thumbnailRepository: ThumbnailRepository
    val settingsRepository: SettingsRepository
    val achievementRepository: AchievementRepository
    val paintDropRepository: PaintDropRepository
    val realmRepository: RealmRepository
    val timelapseVideoCache: TimelapseVideoCache
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val pixcolorApi by lazy { PixcolorApiClient.create() }
    private val remoteAssetLoader by lazy {
        RemoteAssetLoader(cacheDir = File(appContext.cacheDir, "remote_assets"))
    }
    private val localAssetLevelRepository by lazy { AssetLevelRepositoryImpl(appContext) }
    private val localCollectionRepository by lazy { AssetCollectionRepositoryImpl(appContext) }
    private val localRealmRepository by lazy { LocalRealmRepositoryImpl() }

    override val assetLevelRepository: AssetLevelRepository by lazy {
        RemoteLevelRepositoryImpl(
            api = pixcolorApi,
            assetLoader = remoteAssetLoader,
            fallback = localAssetLevelRepository
        )
    }

    override val collectionRepository: CollectionRepository by lazy {
        RemoteCollectionRepositoryImpl(
            api = pixcolorApi,
            assetLoader = remoteAssetLoader,
            fallback = localCollectionRepository
        )
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
        val remoteDefinitions = RemoteAchievementDefinitionProvider(
            api = pixcolorApi,
            assetLoader = remoteAssetLoader
        )
        AchievementRepositoryImpl(
            preferences = appContext.getSharedPreferences("Achievements", Context.MODE_PRIVATE),
            definitionsProvider = remoteDefinitions::loadDefinitions
        )
    }

    override val paintDropRepository: PaintDropRepository by lazy {
        PaintDropRepositoryImpl(
            appContext.getSharedPreferences("PaintDrops", Context.MODE_PRIVATE)
        )
    }

    override val realmRepository: RealmRepository by lazy {
        RemoteRealmRepositoryImpl(
            api = pixcolorApi,
            assetLoader = remoteAssetLoader,
            fallback = localRealmRepository
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            appContext.getSharedPreferences("MyPreferences", Context.MODE_PRIVATE)
        )
    }

    override val timelapseVideoCache: TimelapseVideoCache by lazy {
        TimelapseVideoCache(
            cacheDir = appContext.cacheDir,
            assetLevelRepository = assetLevelRepository,
            paintingProgressRepository = paintingProgressRepository,
        )
    }
}
