package com.pixlory.color.by.number.utils.ads

import android.util.Log
import com.google.android.gms.ads.interstitial.InterstitialAdPreloader
import com.pixlory.color.by.number.bases.BaseActivity
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.models.AdmobInterModel
import com.snake.squad.adslib.models.AdmobNativeModel
import com.snake.squad.adslib.models.AdmobRewardedModel

object AdsManager {

    val INTER_SPLASH = AdmobInterModel("ca-app-pub-3271384969316133/8927152220")
    val AOA_SPLASH = "ca-app-pub-3271384969316133/6861542888"
    val NATIVE_COLLAPSIBLE_SPLASH = AdmobNativeModel("ca-app-pub-3271384969316133/8230464870")
    val INTER_HOME = AdmobInterModel("ca-app-pub-3271384969316133/3674825548")
    val INTER_BACK_TO_HOME = AdmobInterModel("ca-app-pub-3271384969316133/2988429833")
    val NATIVE_COLLAPSIBLE_HOME = AdmobNativeModel("ca-app-pub-3271384969316133/9010912707")
    val NATIVE_COLLAPSIBLE_DRAW = AdmobNativeModel("ca-app-pub-3271384969316133/6907493544")
    val NATIVE_OTHER = AdmobNativeModel("ca-app-pub-3271384969316133/6725811515")
    val INTER_DRAW = AdmobInterModel("ca-app-pub-3271384969316133/9805119623")
    val INTER_DONE = AdmobInterModel("ca-app-pub-3271384969316133/1609216204")
    val REWARD_UNLOCK = AdmobRewardedModel("ca-app-pub-3271384969316133/3058029242")
    val NATIVE_FULL_SCREEN_AFTER_INTER = AdmobNativeModel("ca-app-pub-3271384969316133/6544531467")
    val NATIVE_SETTING = AdmobNativeModel("ca-app-pub-3271384969316133/1417644518")
    const val ON_RESUME = "ca-app-pub-3271384969316133/7474469754"
    const val INTER_ON_RESUME = "ca-app-pub-3271384969316133/7147166698"

    const val admobSqueezeBackModelTest = ""


    private var lastInterShown = 0L

    fun updateTime() {
        lastInterShown = System.currentTimeMillis()
    }

    var countInterHome = 0
    var countInterBackHome = 0
    var countInterIdentify = 0
    var countInterDone = 0
    var countInterDraw = 0
    var isShowedRate = false
    var lastCollapsibleHomeShow = 0L
    var lastCollapsibleDetectorShow = 0L
    var lastCollapsiblePlayShow = 0L


    fun reset() {
        countInterHome = 0
        countInterBackHome = 0
        countInterDraw = 0
        countInterDone = 0
        isShowedRate = false
    }

    fun isShowNativeFullScreen(): Boolean {
        return RemoteConfig.remoteNativeFullScreenAfterInter != 0L && !AdmobLib.getCheckTestDevice()
    }

    fun isShowNativeFullScreenAfterSplash(): Boolean {
        return RemoteConfig.remoteNativeFullScreenAfterInter == 1L && !AdmobLib.getCheckTestDevice()
    }

    private fun isShowInter(): Boolean {
        return (System.currentTimeMillis() - lastInterShown) > RemoteConfig.remoteTimeShowInter
    }

    fun isShowInterHome(): Boolean {
        if (RemoteConfig.remoteInterHome == 0L) return false
        countInterHome++
        Log.d(
            "TAGisShowInterHome",
            "Count Inter Home: ${countInterHome} - Remote Inter Home: ${RemoteConfig.remoteInterHome} - ${isShowInter()} - Remote Time Show Inter: ${RemoteConfig.remoteTimeShowInter}"
        )
        return isShowInter() && (countInterHome % RemoteConfig.remoteInterHome == 0L)
    }

    fun isShowInterBackHome(): Boolean {
        if (RemoteConfig.remoteInterBackToHome == 0L) return false
        countInterBackHome++
        Log.d(
            "TAGisShowInterBackHome",
            "Count Inter Back Home: ${countInterBackHome} - Remote Inter Back Home: ${RemoteConfig.remoteInterBackToHome} - ${isShowInter()}"
        )
        return isShowInter() && (countInterBackHome % RemoteConfig.remoteInterBackToHome == 0L)
    }

    fun isShowInterDraw(): Boolean {
        if (RemoteConfig.remoteInterDraw == 0L) return false
        countInterDraw++
        Log.d(
            "TAGisShowInterOther",
            "Count Inter Other: ${countInterDraw} - Remote Inter Other: ${RemoteConfig.remoteInterDraw} - ${isShowInter()} - Remote Time Show Inter: ${RemoteConfig.remoteTimeShowInter}"
        )
        return isShowInter() && (countInterDraw % RemoteConfig.remoteInterDraw == 0L)
    }

    fun isShowInterDone(): Boolean {
        if (RemoteConfig.remoteInterDone == 0L) return false
        countInterDone++
        Log.d(
            "TAGisShowInterPlay",
            "Count Inter Play: ${countInterDone} - Remote Inter Play: ${RemoteConfig.remoteInterDone} - ${isShowInter()} - Remote Time Show Inter: ${RemoteConfig.remoteTimeShowInter}"
        )

        return isShowInter() && (countInterDone % RemoteConfig.remoteInterDone == 0L)
    }

    fun isReloadingCollapsibleHome(): Boolean {
        return (System.currentTimeMillis() - lastCollapsibleHomeShow) >= RemoteConfig.remoteTimeLoadNative
    }

    fun updateCollapsibleHome() {
        lastCollapsibleHomeShow = System.currentTimeMillis()
    }

    fun isReloadingCollapsiblePlay(): Boolean {
        return (System.currentTimeMillis() - lastCollapsiblePlayShow) >= RemoteConfig.remoteTimeLoadNative
    }

    fun updateCollapsiblePlay() {
        lastCollapsiblePlayShow = System.currentTimeMillis()
    }

    fun isReloadingCollapsibleDetector(): Boolean {
        return (System.currentTimeMillis() - lastCollapsibleDetectorShow) >= RemoteConfig.remoteTimeLoadNative
    }

    fun updateCollapsibleDetector() {
        lastCollapsibleDetectorShow = System.currentTimeMillis()
    }

    fun preloadInterHome(activity: BaseActivity<*>) {
        if (RemoteConfig.remoteInterHome != 0L) {
            AdmobLib.loadInterstitial(
                activity = activity,
                admobInterModel = INTER_HOME
            )
        }
    }

    fun preloadInterBack(activity: BaseActivity<*>) {
        if (RemoteConfig.remoteInterBackToHome != 0L) {
            AdmobLib.loadInterstitial(
                activity = activity,
                admobInterModel = INTER_BACK_TO_HOME,
                isShowOnTestDevice = true
            )
        }
    }

    fun preloadInterDraw(activity: BaseActivity<*>) {
        if (RemoteConfig.remoteInterDraw != 0L) {
            AdmobLib.loadInterstitial(
                activity = activity,
                admobInterModel = INTER_DRAW
            )
        }
    }

    fun preloadInterDone(activity: BaseActivity<*>) {
        if (RemoteConfig.remoteInterDone != 0L) {
            AdmobLib.loadInterstitial(
                activity = activity,
                admobInterModel = INTER_DONE
            )
        }
    }


//    fun preloadInterIntro(activity: BaseActivity<*>) {
//        if (RemoteConfig.remoteInterIntro != 0L) {
//            AdmobLib.loadInterstitial(
//                activity = activity,
//                admobInterModel = INTER_INTRO
//            )
//        }
//    }
//
//    fun preloadInterLanguage(activity: BaseActivity<*>) {
//        if (RemoteConfig.remoteInterLanguage != 0L) {
//            AdmobLib.loadInterstitial(
//                activity = activity, admobInterModel = INTER_LANGUAGE,
//                onAdsLoaded = {
//                    Log.d(
//                        "TAG_loadInterLanguageFirst", "onAdsLoaded: ${
//                            InterstitialAdPreloader.isAdAvailable(
//                                INTER_LANGUAGE.adsID
//                            )
//                        }"
//                    )
//                },
//                onAdsFail = {
//                    Log.d(
//                        "TAG_loadInterLanguageFirst", "onAdsFailed: ${
//                            InterstitialAdPreloader.isAdAvailable(
//                                INTER_LANGUAGE.adsID
//                            )
//                        }"
//                    )
//                }
//            )
//        }
//    }


}
