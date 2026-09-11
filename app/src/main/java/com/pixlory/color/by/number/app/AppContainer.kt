package com.pixlory.color.by.number.app

import android.content.Context
import com.pixlory.color.by.number.BuildConfig
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.data.TimelapseVideoCache
import com.pixlory.color.by.number.data.remote.PixcolorApiClient
import com.pixlory.color.by.number.data.remote.RemoteAssetLoader
import com.pixlory.color.by.number.data.repository.AssetLevelRepository
import com.pixlory.color.by.number.data.repository.AchievementRepository
import com.pixlory.color.by.number.data.repository.AchievementRepositoryImpl
import com.pixlory.color.by.number.data.repository.AssetCollectionRepositoryImpl
import com.pixlory.color.by.number.data.repository.AssetLevelRepositoryImpl
import com.pixlory.color.by.number.data.repository.CollectionRepository
import com.pixlory.color.by.number.data.repository.PaintingProgressRepository
import com.pixlory.color.by.number.data.repository.PaintingProgressRepositoryImpl
import com.pixlory.color.by.number.data.repository.PaintDropRepository
import com.pixlory.color.by.number.data.repository.PaintDropRepositoryImpl
import com.pixlory.color.by.number.data.repository.LocalRealmRepositoryImpl
import com.pixlory.color.by.number.data.repository.RemoteCollectionRepositoryImpl
import com.pixlory.color.by.number.data.repository.RemoteAchievementDefinitionProvider
import com.pixlory.color.by.number.data.repository.RemoteLevelRepositoryImpl
import com.pixlory.color.by.number.data.repository.RemoteRealmRepositoryImpl
import com.pixlory.color.by.number.data.repository.RealmRepository
import com.pixlory.color.by.number.data.repository.SettingsRepository
import com.pixlory.color.by.number.data.repository.SettingsRepositoryImpl
import com.pixlory.color.by.number.data.repository.ThumbnailRepository
import com.pixlory.color.by.number.data.repository.ThumbnailRepositoryImpl
import com.pixlory.color.by.number.utils.RealmAnimationCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val realmContentPreloader: RealmContentPreloader
    val timelapseVideoCache: TimelapseVideoCache
    val startupContentPreloader: StartupContentPreloader
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pixcolorApi by lazy { PixcolorApiClient.create() }
    private val remoteAssetLoader by lazy {
        RemoteAssetLoader(cacheDir = File(appContext.cacheDir, "remote_assets"))
    }
    private val remoteLevelMetadataCacheFile by lazy {
        File(appContext.cacheDir, "remote_level_metadata/levels.json")
    }
    private val localAssetLevelRepository by lazy { AssetLevelRepositoryImpl(appContext) }
    private val localCollectionRepository by lazy { AssetCollectionRepositoryImpl(appContext) }
    private val localRealmRepository by lazy { LocalRealmRepositoryImpl() }

    override val assetLevelRepository: AssetLevelRepository by lazy {
        if (BuildConfig.USE_REMOTE_CONTENT) {
            RemoteLevelRepositoryImpl(
                api = pixcolorApi,
                assetLoader = remoteAssetLoader,
                fallback = localAssetLevelRepository,
                metadataCacheFile = remoteLevelMetadataCacheFile
            )
        } else {
            localAssetLevelRepository
        }
    }

    override val collectionRepository: CollectionRepository by lazy {
        if (BuildConfig.USE_REMOTE_CONTENT) {
            RemoteCollectionRepositoryImpl(
                api = pixcolorApi,
                assetLoader = remoteAssetLoader,
                fallback = localCollectionRepository
            )
        } else {
            localCollectionRepository
        }
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

    override val realmContentPreloader: RealmContentPreloader by lazy {
        RealmContentPreloader(
            realmRepository = realmRepository,
            scope = startupScope,
            fallbackRealm = RealmCatalog::findById,
            prepareFallbackAnimation = { realm ->
                if (realm.animationRes != 0) {
                    RealmAnimationCache.loadComposition(appContext, realm.animationRes)
                }
            },
            prepareRemoteAnimation = { realm ->
                realm.animationUrl?.takeIf(String::isNotBlank)?.let { animationUrl ->
                    RealmAnimationCache.loadRemoteComposition(appContext, animationUrl)
                }
            },
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

    override val startupContentPreloader: StartupContentPreloader by lazy {
        StartupContentPreloader(
            assetLevelRepository = assetLevelRepository,
            scope = startupScope
        )
    }
}
