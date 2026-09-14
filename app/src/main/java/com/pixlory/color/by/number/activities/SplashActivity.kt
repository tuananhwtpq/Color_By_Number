package com.pixlory.color.by.number.activities

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.data.repository.AchievementEvent
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivitySplashBinding
import com.pixlory.color.by.number.ui.splash.SplashUiEvent
import com.pixlory.color.by.number.ui.splash.SplashViewModel
import com.pixlory.color.by.number.utils.Constants
import com.pixlory.color.by.number.utils.invisible
import com.pixlory.color.by.number.utils.visible
import com.pixlory.color.by.number.utils.SoundScene
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.ads.AdsManager
import com.pixlory.color.by.number.utils.ads.RemoteConfig
import com.pixlory.color.by.number.utils.gone
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.aoa.AppOnResumeAdsManager
import com.snake.squad.adslib.aoa.AppOpenAdsManager
import com.snake.squad.adslib.cmp.GoogleMobileAdsConsentManager
import com.snake.squad.adslib.utils.AdsHelper
import kotlinx.coroutines.flow.collectLatest
import kotlin.system.exitProcess

class SplashActivity : BaseActivity<ActivitySplashBinding>(ActivitySplashBinding::inflate) {

    override val soundScene = SoundScene.SILENT

    private val viewModel: SplashViewModel by viewModels {
        SimpleViewModelFactory { SplashViewModel() }
    }
    private var remoteConfigHandled = false

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
    }

    override fun initView() {
        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                if (state.showAdsLoading) binding.tvLoadingAds.visible()
                else binding.tvLoadingAds.invisible()
            }
        }
        collectWithLifecycle {
            viewModel.events.collectLatest { event ->
                when (event) {
                    SplashUiEvent.RequestConsent -> setupCMP()
                    SplashUiEvent.FetchRemoteConfig -> initRemoteConfig()
                    SplashUiEvent.InitializeAds -> initializeMobileAdsSdk()
                    SplashUiEvent.NavigateToMain -> replaceActivity()
                }
            }
        }

        val hasNetwork = AdsHelper.isNetworkConnected(this)
        if (!hasNetwork) {
            RemoteConfig.getDefaultRemoteValue()
            SharedPrefManager.initializeHintBalanceIfNeeded(RemoteConfig.remoteLimitHint.toInt())
        }
        viewModel.startSplash(hasNetwork = hasNetwork)
    }

    override fun initActionView() {
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    private fun setupCMP() {
        val googleMobileAdsConsentManager = GoogleMobileAdsConsentManager(this)
        googleMobileAdsConsentManager.gatherConsent { error ->
            if (error != null) {
                viewModel.onConsentResolved(canRequestAds = true)
                return@gatherConsent
            }

            viewModel.onConsentResolved(
                canRequestAds = googleMobileAdsConsentManager.canRequestAds
            )
        }
    }

    private fun initializeMobileAdsSdk() {
        initAds()
    }

    private fun initRemoteConfig() {
        RemoteConfig.initRemoteConfig(this, initListener = object : RemoteConfig.InitListener {
            override fun onComplete() {
                RemoteConfig.getAllRemoteValueToLocal()
                onRemoteConfigReady()
            }

            override fun onFailure() {
                RemoteConfig.getDefaultRemoteValue()
                onRemoteConfigReady()
            }
        })
    }

    private fun onRemoteConfigReady() {
        if (remoteConfigHandled) return
        remoteConfigHandled = true
        SharedPrefManager.initializeHintBalanceIfNeeded(RemoteConfig.remoteLimitHint.toInt())
        viewModel.onRemoteConfigResolved()
    }

    private fun initAds() {
        AdmobLib.setEnabledCheckTestDevice(false)
        AdmobLib.initialize(this, isDebug = true, isShowAds = false, onInitializedAds = {
            if (it) {
                preloadAds()
                setupOnResume()
                showSplashNativeThenAd()
            } else {
                viewModel.onAdsInitializationFailed()
            }
        })
    }

    private fun preloadAds() {
        AdsManager.preloadInterHome(this)
        AdsManager.preloadInterBack(this)
        AdsManager.preloadInterDone(this)
    }

    private fun setupOnResume() {
        when (RemoteConfig.remoteOnResume) {
            1L -> AppOnResumeAdsManager.initialize(application, AdsManager.ON_RESUME, type = AppOnResumeAdsManager.AOA)
            2L -> AppOnResumeAdsManager.initialize(application, AdsManager.INTER_ON_RESUME, type = AppOnResumeAdsManager.INTER)
        }
        AppOnResumeAdsManager.disableForActivity(SplashActivity::class.java)
    }

    private fun showSplashNativeThenAd() {
        val host = CollapsibleNativeHost(binding.frNativeSmall, binding.frNativeExpand, binding.whiteLine)
        when (RemoteConfig.remoteNativeCollapsibleSplash) {
            1L -> {
                binding.frNativeExpand.gone()
                AdmobLib.loadAndShowNative(this, AdsManager.NATIVE_COLLAPSIBLE_SPLASH,
                    viewGroup = binding.frNativeSmall,
                    size = com.snake.squad.adslib.utils.GoogleENative.UNIFIED_SMALL_LIKE_BANNER,
                    layout = com.pixlory.color.by.number.R.layout.native_ads_custom_small_like_banner,
                    onAdsLoaded = { binding.whiteLine.visible(); showSplashAd() },
                    onAdsLoadFail = { binding.whiteLine.gone(); showSplashAd() })
            }
            2L -> renderCollapsibleNative(true, AdsManager.NATIVE_COLLAPSIBLE_SPLASH, host) { showSplashAd() }
            else -> showSplashAd()
        }
    }

    private fun showSplashAd() {
        val continueFlow = { viewModel.onAdsInitialized() }
        when (RemoteConfig.remoteSplashAds) {
            1L -> AdmobLib.loadAndShowInterSplashWithNativeAfter(this, AdsManager.INTER_SPLASH,
                AdsManager.NATIVE_FULL_SCREEN_AFTER_INTER,
                isShowNativeAfter = AdsManager.isShowNativeFullScreen(),
                nativeLayout = com.pixlory.color.by.number.R.layout.native_ads_full_screen,
                navAction = continueFlow)
            2L -> if (SharedPrefManager.firstInterCount < 2) { SharedPrefManager.firstInterCount += 1; continueFlow() }
                else AdmobLib.loadAndShowInterSplashWithNativeAfter(this, AdsManager.INTER_SPLASH, AdsManager.NATIVE_FULL_SCREEN_AFTER_INTER,
                    isShowNativeAfter = AdsManager.isShowNativeFullScreen(), nativeLayout = com.pixlory.color.by.number.R.layout.native_ads_full_screen, navAction = continueFlow)
            3L -> AppOpenAdsManager(this, AdsManager.AOA_SPLASH, 15_000) { continueFlow() }.loadAndShowAoA()
            4L -> if (SharedPrefManager.isFirstAoaCount < 2) { SharedPrefManager.isFirstAoaCount += 1; continueFlow() }
                else AppOpenAdsManager(this, AdsManager.AOA_SPLASH, 15_000) { continueFlow() }.loadAndShowAoA()
            else -> continueFlow()
        }
    }

    private fun replaceActivity() {
        val intent = when (RemoteConfig.remoteLanguageIntroFirstOpen) {
            0L -> Intent(this@SplashActivity, MainActivity::class.java)
            1L -> if ((application as MyApplication).appContainer.settingsRepository.getIsFirstTimeOpenApp()) {
                Intent(this@SplashActivity, LanguageActivity::class.java).apply {
                    putExtra(Constants.LANGUAGE_EXTRA, false)
                    putExtra(LanguageActivity.EXTRA_FROM_SPLASH, true)
                    putExtra(LanguageActivity.EXTRA_SHOW_INTRO_AFTER_LANGUAGE, true)
                }
            } else Intent(this@SplashActivity, MainActivity::class.java)
            else -> Intent(this@SplashActivity, LanguageActivity::class.java).apply {
                putExtra(Constants.LANGUAGE_EXTRA, false)
                putExtra(LanguageActivity.EXTRA_FROM_SPLASH, true)
                putExtra(LanguageActivity.EXTRA_SHOW_INTRO_AFTER_LANGUAGE, true)
            }
        }
        startActivity(intent)
        finish()
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            exitProcess(0)
        }
    }

}
