package com.example.baseproject.activities

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivitySplashBinding
import com.example.baseproject.ui.splash.SplashUiEvent
import com.example.baseproject.ui.splash.SplashViewModel
import com.example.baseproject.utils.Constants
import com.example.baseproject.utils.invisible
import com.example.baseproject.utils.visible
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.cmp.GoogleMobileAdsConsentManager
import com.snake.squad.adslib.utils.AdsHelper
import kotlinx.coroutines.flow.collectLatest
import kotlin.system.exitProcess

class SplashActivity : BaseActivity<ActivitySplashBinding>(ActivitySplashBinding::inflate) {

    private val viewModel: SplashViewModel by viewModels {
        SimpleViewModelFactory { SplashViewModel() }
    }

    override fun initData() {
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
                    SplashUiEvent.InitializeAds -> initializeMobileAdsSdk()
                    SplashUiEvent.NavigateToMain -> replaceActivity()
                }
            }
        }

        viewModel.startSplash(hasNetwork = AdsHelper.isNetworkConnected(this))
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

//    private fun initRemoteConfig() {
//        RemoteConfig.initRemoteConfig(this, initListener = object : RemoteConfig.InitListener {
//            override fun onComplete() {
//                RemoteConfig.getAllRemoteValueToLocal()
//                if (isInitAds.get()) {
//                    return
//                }
//                isInitAds.set(true)
//                setupCMP()
//            }
//
//            override fun onFailure() {
//                RemoteConfig.getDefaultRemoteValue()
//                setupCMP()
//            }
//        })
//    }

    private fun initAds() {
        AdmobLib.initialize(this, isDebug = true, isShowAds = false, onInitializedAds = {
            if (it) {
                viewModel.onAdsInitialized()
            } else {
                viewModel.onAdsInitializationFailed()
            }
        })
    }

    private fun replaceActivity() {
        val intent = Intent(this@SplashActivity, MainActivity::class.java)
        intent.putExtra(Constants.LANGUAGE_EXTRA, false)
        startActivity(intent)
        finish()
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            exitProcess(0)
        }
    }

}
