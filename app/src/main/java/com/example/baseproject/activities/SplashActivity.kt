package com.example.baseproject.activities

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivitySplashBinding
import com.example.baseproject.utils.Constants
import com.example.baseproject.utils.invisible
import com.example.baseproject.utils.visible
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.cmp.GoogleMobileAdsConsentManager
import com.snake.squad.adslib.utils.AdsHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class SplashActivity : BaseActivity<ActivitySplashBinding>(ActivitySplashBinding::inflate) {

    private var isMobileAdsInitializeCalled = AtomicBoolean(false)
    private var isInitAds = AtomicBoolean(false)

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
//        if (AdsHelper.isNetworkConnected(this)) {
//            binding.tvLoadingAds.visible()
//            setupCMP()
////            initRemoteConfig()
//        } else {
//            binding.tvLoadingAds.invisible()
//            Handler(Looper.getMainLooper()).postDelayed({
//                replaceActivity()
//            }, 3000)
//        }

        Handler(Looper.getMainLooper()).postDelayed({
            replaceActivity()
        }, 3000L)
    }

    override fun initActionView() {
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    private fun setupCMP() {
        val googleMobileAdsConsentManager = GoogleMobileAdsConsentManager(this)
        googleMobileAdsConsentManager.gatherConsent { error ->
            error?.let {
                initializeMobileAdsSdk()
            }

            if (googleMobileAdsConsentManager.canRequestAds) {
                initializeMobileAdsSdk()
            }
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.get()) {
            //start action
            return
        }
        isMobileAdsInitializeCalled.set(true)
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
                // todo: fix here
                binding.tvLoadingAds.invisible()
                Handler(Looper.getMainLooper()).postDelayed({
                    replaceActivity()
                }, 5000)
            } else {
                binding.tvLoadingAds.invisible()
                Handler(Looper.getMainLooper()).postDelayed({
                    replaceActivity()
                }, 5000)
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