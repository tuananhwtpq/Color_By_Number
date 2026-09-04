package com.example.baseproject

import com.example.baseproject.app.AppContainer
import com.example.baseproject.app.DefaultAppContainer
import com.example.baseproject.utils.SharedPrefManager
import com.snake.squad.adslib.AdsApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MyApplication : AdsApplication() {

    lateinit var appContainer: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        SharedPrefManager.init(this)
        appContainer = DefaultAppContainer(this)
        appContainer.paintDropRepository.trackAppOpened()
        preloadLibraryLevels()
    }

    private fun preloadLibraryLevels() {
        applicationScope.launch {
            runCatching {
                appContainer.assetLevelRepository.refreshAllLevels()
            }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }

}
