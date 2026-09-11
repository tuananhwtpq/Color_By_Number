package com.pixlory.color.by.number

import com.pixlory.color.by.number.app.AppContainer
import com.pixlory.color.by.number.app.DefaultAppContainer
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.SoundManager
import com.snake.squad.adslib.AdsApplication

class MyApplication : AdsApplication() {

    lateinit var appContainer: AppContainer
        private set
    lateinit var soundManager: SoundManager
        private set
    override fun onCreate() {
        super.onCreate()
        SharedPrefManager.init(this)
        soundManager = SoundManager(this)
        appContainer = DefaultAppContainer(this)
        appContainer.paintDropRepository.trackAppOpened()
        // Start metadata/category loading while Splash and Language are visible. Every later
        // caller awaits this same job, so it cannot create a competing server request.
        appContainer.startupContentPreloader.start()
    }

    override fun onTerminate() {
        soundManager.release()
        super.onTerminate()
    }

}
