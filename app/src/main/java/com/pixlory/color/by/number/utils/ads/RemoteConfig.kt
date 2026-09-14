package com.pixlory.color.by.number.utils.ads

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.utils.SharedPrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object RemoteConfig {

    private var isInit = false
    private var isTimedOut = false

    private const val REMOTE_SPLASH_ADS = "remote_splash_ads"
    private const val REMOTE_NATIVE_COLLAPSIBLE_SPLASH = "remote_native_collapsible_splash"
    private const val REMOTE_INTER_HOME = "remote_inter_home"
    private const val REMOTE_INTER_BACK_TO_HOME = "remote_inter_back_to_home"
    private const val REMOTE_NATIVE_COLLAPSIBLE_HOME = "remote_native_collapsible_home"
    private const val REMOTE_NATIVE_COLLAPSIBLE_DRAW = "remote_native_collapsible_draw"
    private const val REMOTE_NATIVE_OTHER = "remote_native_other"
    private const val REMOTE_INTER_DRAW = "remote_inter_draw"
    private const val REMOTE_INTER_DONE = "remote_inter_done"
    private const val REMOTE_REWARD_UNLOCK = "remote_reward_unlock"
    private const val REMOTE_NATIVE_FULL_SCREEN_AFTER_INTER =
        "remote_native_full_screen_after_inter"
    private const val REMOTE_NATIVE_SETTING = "remote_native_setting"
    private const val REMOTE_ON_RESUME = "remote_on_resume"
    private const val REMOTE_TIME_SHOW_INTER_DRAW = "remote_time_show_inter_draw"
    private const val REMOTE_TIME_SHOW_INTER = "remote_time_show_inter"
    private const val REMOTE_TIME_LOAD_NATIVE = "remote_time_load_native"
    private const val REMOTE_LIMIT_HINT = "remote_limit_hint"
    private const val REMOTE_LANGUAGE_INTRO_FIRST_OPEN = "remote_language_intro_first_open"

    var remoteSplashAds: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_SPLASH_ADS, 3L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_SPLASH_ADS, value)
        }
    var remoteNativeCollapsibleSplash: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_NATIVE_COLLAPSIBLE_SPLASH, 2L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_NATIVE_COLLAPSIBLE_SPLASH, value)
        }
    var remoteInterHome: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_INTER_HOME, 2L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_INTER_HOME, value)
        }
    var remoteInterBackToHome: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_INTER_BACK_TO_HOME, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_INTER_BACK_TO_HOME, value)
        }
    var remoteNativeCollapsibleHome: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_NATIVE_COLLAPSIBLE_HOME, 2L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_NATIVE_COLLAPSIBLE_HOME, value)
        }
    var remoteNativeCollapsibleDraw: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_NATIVE_COLLAPSIBLE_DRAW, 2L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_NATIVE_COLLAPSIBLE_DRAW, value)
        }
    var remoteNativeOther: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_NATIVE_OTHER, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_NATIVE_OTHER, value)
        }
    var remoteInterDraw: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_INTER_DRAW, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_INTER_DRAW, value)
        }
    var remoteInterDone: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_INTER_DONE, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_INTER_DONE, value)
        }
    var remoteRewardUnlock: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_REWARD_UNLOCK, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_REWARD_UNLOCK, value)
        }
    var remoteNativeFullScreenAfterInter: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_NATIVE_FULL_SCREEN_AFTER_INTER, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_NATIVE_FULL_SCREEN_AFTER_INTER, value)
        }
    var remoteNativeSetting: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_NATIVE_SETTING, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_NATIVE_SETTING, value)
        }
    var remoteOnResume: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_ON_RESUME, 2L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_ON_RESUME, value)
        }
    var remoteTimeShowInterDraw: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_TIME_SHOW_INTER_DRAW, 30000L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_TIME_SHOW_INTER_DRAW, value)
        }
    var remoteTimeShowInter: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_TIME_SHOW_INTER, 15000L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_TIME_SHOW_INTER, value)
        }
    var remoteTimeLoadNative: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_TIME_LOAD_NATIVE, 15000L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_TIME_LOAD_NATIVE, value)
        }
    var remoteLimitHint: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_LIMIT_HINT, 3L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_LIMIT_HINT, value)
        }
    var remoteLanguageIntroFirstOpen: Long
        get() {
            return SharedPrefManager.getLong(REMOTE_LANGUAGE_INTRO_FIRST_OPEN, 1L)
        }
        set(value) {
            SharedPrefManager.putLong(REMOTE_LANGUAGE_INTRO_FIRST_OPEN, value)
        }


    fun initRemoteConfig(
        activity: AppCompatActivity,
        timeOut: Long = 8000,
        initListener: InitListener
    ) {
        val mFirebaseRemoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings: FirebaseRemoteConfigSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(0)
            .build()
        mFirebaseRemoteConfig.setConfigSettingsAsync(configSettings)
        mFirebaseRemoteConfig.setDefaultsAsync(R.xml.remote_config_default)
        mFirebaseRemoteConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
            override fun onUpdate(configUpdate: ConfigUpdate) {
                mFirebaseRemoteConfig.activate().addOnCompleteListener {
                    isInit = true
                    if (!isTimedOut) initListener.onComplete()
                }
            }

            override fun onError(error: FirebaseRemoteConfigException) {
                isInit = false
                if (!isTimedOut) initListener.onFailure()
            }
        })
        mFirebaseRemoteConfig.fetchAndActivate().addOnCompleteListener {
            if (it.isSuccessful) {
                isInit = true
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isTimedOut) initListener.onComplete()
                }, 2000)
            }
        }
        activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(timeOut)
            if (!isInit) {
                isTimedOut = true
                initListener.onFailure()
            }
        }
    }

    private fun getRemoteLongValue(key: String): Long {
        val mFirebaseRemoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
        return mFirebaseRemoteConfig.getLong(key)
    }

    fun getAllRemoteValueToLocal() {
        remoteSplashAds = getRemoteLongValue(REMOTE_SPLASH_ADS)
        remoteNativeCollapsibleSplash = getRemoteLongValue(REMOTE_NATIVE_COLLAPSIBLE_SPLASH)
        remoteInterHome = getRemoteLongValue(REMOTE_INTER_HOME)
        remoteInterBackToHome = getRemoteLongValue(REMOTE_INTER_BACK_TO_HOME)
        remoteNativeCollapsibleHome = getRemoteLongValue(REMOTE_NATIVE_COLLAPSIBLE_HOME)
        remoteNativeCollapsibleDraw = getRemoteLongValue(REMOTE_NATIVE_COLLAPSIBLE_DRAW)
        remoteNativeOther = getRemoteLongValue(REMOTE_NATIVE_OTHER)
        remoteInterDraw = getRemoteLongValue(REMOTE_INTER_DRAW)
        remoteInterDone = getRemoteLongValue(REMOTE_INTER_DONE)
        remoteRewardUnlock = getRemoteLongValue(REMOTE_REWARD_UNLOCK)
        remoteNativeFullScreenAfterInter = getRemoteLongValue(REMOTE_NATIVE_FULL_SCREEN_AFTER_INTER)
        remoteNativeSetting = getRemoteLongValue(REMOTE_NATIVE_SETTING)
        remoteOnResume = getRemoteLongValue(REMOTE_ON_RESUME)
        remoteTimeShowInterDraw = getRemoteLongValue(REMOTE_TIME_SHOW_INTER_DRAW)
        remoteTimeShowInter = getRemoteLongValue(REMOTE_TIME_SHOW_INTER)
        remoteTimeLoadNative = getRemoteLongValue(REMOTE_TIME_LOAD_NATIVE)
        remoteLimitHint = getRemoteLongValue(REMOTE_LIMIT_HINT)
        remoteLanguageIntroFirstOpen = getRemoteLongValue(REMOTE_LANGUAGE_INTRO_FIRST_OPEN)
    }

    fun getDefaultRemoteValue() {
        remoteSplashAds = 3L
        remoteNativeCollapsibleSplash = 2L
        remoteInterHome = 2L
        remoteInterBackToHome = 1L
        remoteNativeCollapsibleHome = 2L
        remoteNativeCollapsibleDraw = 2L
        remoteNativeOther = 1L
        remoteInterDraw = 1L
        remoteInterDone = 1L
        remoteRewardUnlock = 1L
        remoteNativeFullScreenAfterInter = 1L
        remoteNativeSetting = 1L
        remoteOnResume = 2L
        remoteTimeShowInterDraw = 30000L
        remoteTimeShowInter = 15000L
        remoteTimeLoadNative = 15000L
        remoteLimitHint = 3L
        remoteLanguageIntroFirstOpen = 1L
    }


    interface InitListener {
        fun onComplete()
        fun onFailure()
    }
}