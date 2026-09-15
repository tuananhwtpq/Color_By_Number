package com.pixlory.color.by.number.activities

import android.content.Intent
import android.util.Log
import androidx.activity.OnBackPressedCallback
import com.pixlory.color.by.number.BuildConfig
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.repository.AchievementEvent
import com.pixlory.color.by.number.databinding.ActivitySplashBinding
import com.pixlory.color.by.number.utils.Constants
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.SoundScene
import com.pixlory.color.by.number.utils.ads.AdsManager
import com.pixlory.color.by.number.utils.ads.RemoteConfig
import com.pixlory.color.by.number.utils.gone
import com.pixlory.color.by.number.utils.invisible
import com.pixlory.color.by.number.utils.visible
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.aoa.AppOnResumeAdsManager
import com.snake.squad.adslib.aoa.AppOpenAdsManager
import com.snake.squad.adslib.cmp.GoogleMobileAdsConsentManager
import com.snake.squad.adslib.utils.AdsHelper
import com.snake.squad.adslib.utils.GoogleENative
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class SplashActivity : BaseActivity<ActivitySplashBinding>(ActivitySplashBinding::inflate) {

    override val soundScene = SoundScene.SILENT

    private val remoteConfigResolved = AtomicBoolean(false)
    private val mobileAdsInitialized = AtomicBoolean(false)
    private val splashFinished = AtomicBoolean(false)

    override fun initData() {
        (application as MyApplication).appContainer.achievementRepository
            .track(AchievementEvent.AppOpened)

        if (!isTaskRoot
            && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            && intent.action != null
            && intent.action == Intent.ACTION_MAIN
        ) {
            finish()
            return
        }

        AdsManager.reset()
    }

    override fun initView() {
        if (isFinishing || isDestroyed) return
        hideSplashNative()

        if (AdsHelper.isNetworkConnected(this)) {
            binding.tvLoadingAds.visible()
            initRemoteConfig()
        } else {
            binding.tvLoadingAds.invisible()
            RemoteConfig.getDefaultRemoteValue()
            SharedPrefManager.initializeHintBalanceIfNeeded(RemoteConfig.remoteLimitHint.toInt())
            completeSplash()
        }
    }

    override fun initActionView() {
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    private fun initRemoteConfig() {
        RemoteConfig.initRemoteConfig(this, initListener = object : RemoteConfig.InitListener {
            override fun onComplete() {
                RemoteConfig.getAllRemoteValueToLocal()
                onRemoteConfigResolved()
            }

            override fun onFailure() {
                RemoteConfig.getDefaultRemoteValue()
                onRemoteConfigResolved()
            }
        })
    }

    private fun onRemoteConfigResolved() {
        if (!remoteConfigResolved.compareAndSet(false, true) || !canContinueFlow()) return
        SharedPrefManager.initializeHintBalanceIfNeeded(RemoteConfig.remoteLimitHint.toInt())
        setupConsent()
    }

    private fun setupConsent() {
        val consentManager = GoogleMobileAdsConsentManager(this)
        consentManager.gatherConsent { error ->
            if (!canContinueFlow()) return@gatherConsent

            if (error != null || consentManager.canRequestAds) {
                initializeMobileAdsSdk()
            } else {
                completeSplash()
            }
        }
    }

    private fun initializeMobileAdsSdk() {
        if (!mobileAdsInitialized.compareAndSet(false, true) || !canContinueFlow()) return

        AdmobLib.setEnabledCheckTestDevice(false)
        AdmobLib.initialize(
            this,
            isDebug = true,
            isShowAds = false,
            onInitializedAds = { initialized ->
                if (!canContinueFlow()) return@initialize

                if (!initialized || !AdmobLib.getShowAds()) {
                    hideSplashNative()
                    completeSplash()
                    return@initialize
                }

                preloadAds()
                setupOnResume()
                showSplashNativeThenAd()
            }
        )
    }

    private fun preloadAds() {
        AdsManager.preloadInterHome(this)
        AdsManager.preloadInterBack(this)
        AdsManager.preloadInterDone(this)
        AdsManager.preloadInterDraw(this)
    }

    private fun setupOnResume() {
        when (RemoteConfig.remoteOnResume) {
            1L -> AppOnResumeAdsManager.initialize(
                application = application,
                adsId = AdsManager.ON_RESUME,
                type = AppOnResumeAdsManager.AOA
            )

            2L -> AppOnResumeAdsManager.initialize(
                application = application,
                adsId = AdsManager.INTER_ON_RESUME,
                type = AppOnResumeAdsManager.INTER
            )
        }
        AppOnResumeAdsManager.disableForActivity(SplashActivity::class.java)
    }

    private fun showSplashNativeThenAd() {
        if (!canContinueFlow() || !AdmobLib.getShowAds()) {
            hideSplashNative()
            completeSplash()
            return
        }

        when (RemoteConfig.remoteNativeCollapsibleSplash) {
            1L -> {
                binding.frNativeSmall.visible()
                binding.frNativeExpand.gone()
                AdmobLib.loadAndShowNative(
                    activity = this,
                    admobNativeModel = AdsManager.NATIVE_COLLAPSIBLE_SPLASH,
                    viewGroup = binding.frNativeSmall,
                    size = GoogleENative.UNIFIED_SMALL_LIKE_BANNER,
                    layout = R.layout.native_ads_custom_small_like_banner,
                    onAdsLoaded = {
                        if (!canContinueFlow()) return@loadAndShowNative null
                        binding.whiteLine.visible()
                        showSplashAd()
                    },
                    onAdsLoadFail = {
                        if (!canContinueFlow()) return@loadAndShowNative null
                        hideSplashNative()
                        showSplashAd()
                    }
                )
            }

            2L -> {
                binding.frNativeSmall.visible()
                binding.frNativeExpand.visible()
                AdmobLib.loadAndShowNativeCollapsibleSingle(
                    activity = this,
                    admobNativeModel = AdsManager.NATIVE_COLLAPSIBLE_SPLASH,
                    viewGroupCollapsed = binding.frNativeSmall,
                    viewGroupExpanded = binding.frNativeExpand,
                    layoutCollapsed = R.layout.native_ads_custom_small_like_banner,
                    layoutExpanded = R.layout.native_ads_custom_medium_bottom,
                    onAdsLoaded = {
                        if (!canContinueFlow()) return@loadAndShowNativeCollapsibleSingle null
                        binding.whiteLine.visible()
                        showSplashAd()
                    },
                    onAdsLoadFail = {
                        if (!canContinueFlow()) return@loadAndShowNativeCollapsibleSingle null
                        hideSplashNative()
                        showSplashAd()
                    }
                )
            }

            else -> {
                hideSplashNative()
                showSplashAd()
            }
        }
    }

    private fun showSplashAd() {
        if (!canContinueFlow()) return

        Log.d("SplashAc", "Remote splash ads: ${RemoteConfig.remoteSplashAds}")

        when (RemoteConfig.remoteSplashAds) {
            1L -> showSplashInterstitial()
            2L -> if (shouldSkipFirstInterSplash()) completeSplash() else showSplashInterstitial()
            3L -> showSplashAppOpen()
            4L -> if (shouldSkipFirstAoaSplash()) completeSplash() else showSplashAppOpen()
            else -> completeSplash()
        }
    }

    private fun showSplashInterstitial() {
        AdmobLib.loadAndShowInterSplashWithNativeAfter(
            mActivity = this,
            interModel = AdsManager.INTER_SPLASH,
            nativeModel = AdsManager.NATIVE_FULL_SCREEN_AFTER_INTER,
            isShowNativeAfter = AdsManager.isShowNativeFullScreenAfterSplash(),
            nativeLayout = R.layout.native_ads_full_screen,
            navAction = ::completeSplash
        )
    }

    private fun showSplashAppOpen() {
        AppOpenAdsManager(
            activity = this,
            adsID = AdsManager.AOA_SPLASH,
            timeOut = 15_000,
            onAdsCloseOrFailed = { completeSplash() }
        ).loadAndShowAoA()
    }

    private fun shouldSkipFirstInterSplash(): Boolean {
        if (SharedPrefManager.firstInterCount > 0) return false
        SharedPrefManager.firstInterCount = 1
        return true
    }

    private fun shouldSkipFirstAoaSplash(): Boolean {
        if (SharedPrefManager.firstAoaCount > 0) return false
        SharedPrefManager.firstAoaCount = 1
        return true
    }

    private fun completeSplash() {
        if (!splashFinished.compareAndSet(false, true)) return

        hideSplashNative()
        binding.tvLoadingAds.invisible()
        if (!isFinishing && !isDestroyed) {
            replaceActivity()
        }
    }

    private fun hideSplashNative() {
        binding.frNativeSmall.gone()
        binding.frNativeExpand.gone()
        binding.whiteLine.gone()
    }

    private fun canContinueFlow(): Boolean {
        return !splashFinished.get() && !isFinishing && !isDestroyed
    }

    private fun replaceActivity() {
        val isFirstOpen = (application as MyApplication).appContainer.settingsRepository
            .getIsFirstTimeOpenApp()
        val intent = when (RemoteConfig.remoteLanguageIntroFirstOpen) {
            1L -> if (isFirstOpen) languageIntent() else Intent(this, MainActivity::class.java)
            2L -> languageIntent()
            else -> Intent(this, MainActivity::class.java)
        }
        startActivity(intent)
        finish()
    }

    private fun languageIntent(): Intent {
        return Intent(this, LanguageActivity::class.java).apply {
            putExtra(Constants.LANGUAGE_EXTRA, false)
            putExtra(LanguageActivity.EXTRA_FROM_SPLASH, true)
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            exitProcess(0)
        }
    }
}
