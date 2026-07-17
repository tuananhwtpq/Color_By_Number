package com.example.baseproject

import com.example.baseproject.app.AppContainer
import com.example.baseproject.app.DefaultAppContainer
import com.example.baseproject.utils.SharedPrefManager
import com.snake.squad.adslib.AdsApplication

class MyApplication : AdsApplication() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SharedPrefManager.init(this)
        appContainer = DefaultAppContainer(this)
    }

}
