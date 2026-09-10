package com.example.baseproject.activities

import android.content.Intent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.baseproject.MyApplication
import com.example.baseproject.adapters.IntroViewPagerAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityIntroBinding
import com.example.baseproject.ui.intro.IntroViewModel
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.gone
import com.example.baseproject.utils.SoundScene
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class IntroActivity : BaseActivity<ActivityIntroBinding>(ActivityIntroBinding::inflate) {
    override val soundScene = SoundScene.SILENT
    companion object {
        private const val PREPARING_MIN_DURATION_MS = 900L
        private const val PREPARING_MAX_DURATION_MS = 5_500L
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private val viewModel: IntroViewModel by viewModels {
        SimpleViewModelFactory {
            IntroViewModel(appContainer.settingsRepository)
        }
    }

    private val mAdapter: IntroViewPagerAdapter by lazy {
        IntroViewPagerAdapter(
            fragmentActivity = this,
            isShowNativeFull1 = false,
            isShowNativeFull2 = false
        )
    }

    //    private val isShowNativeFull1 by lazy {
//        RemoteConfig.remoteNativeFullScreenIntro != 0L &&
//                AdmobLib.getShowAds() && !AdmobLib.getCheckTestDevice() &&
//                AdsHelper.isNetworkConnected(this)
//    }
//    private val isShowNativeFull2 by lazy {
//        RemoteConfig.remoteNativeFullScreenIntro2 != 0L &&
//                AdmobLib.getShowAds() && !AdmobLib.getCheckTestDevice() &&
//                AdsHelper.isNetworkConnected(this)
//    }
    private var isNext = false
    private var isPreparingHome = false
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isPreparingHome) return
            isNext = false
            if (binding.vpIntro.currentItem in 1..mAdapter.itemCount) {
                binding.vpIntro.currentItem -= 1
            } else {
                finish()
            }
        }
    }

    override fun initData() {
        viewModel.onIntroOpened()
    }

    override fun initView() {
        binding.vpIntro.adapter = mAdapter

        binding.vpIntro.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var oldOffsetPixels: Int = 0
            private var oldPosition: Int = 0
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int,
            ) {
                if (oldPosition != position) {
                    oldPosition = position
                    return
                }

                isNext = positionOffsetPixels >= oldOffsetPixels
                oldOffsetPixels = positionOffsetPixels
            }
        })
    }

    override fun initActionView() {
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun goToHome() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(MainActivity.EXTRA_SKIP_PREPARING_OVERLAY, true)
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun prepareThenGoToHome() {
        if (isPreparingHome) return
        if (SharedPrefManager.hasSeenLibraryPreparing) {
            goToHome()
            return
        }

        isPreparingHome = true
        binding.contentPreparingOverlay.apply {
            alpha = 1f
            visibility = View.VISIBLE
            bringToFront()
        }
        lifecycleScope.launch {
            val preload = async {
                runCatching { appContainer.assetLevelRepository.loadAllLevels() }
            }
            delay(PREPARING_MIN_DURATION_MS)
            withTimeoutOrNull(PREPARING_MAX_DURATION_MS - PREPARING_MIN_DURATION_MS) {
                preload.await()
            }
            SharedPrefManager.hasSeenLibraryPreparing = true
            goToHome()
        }
    }

    fun nextPage() {
        if (binding.vpIntro.currentItem < mAdapter.itemCount - 1) {
            binding.vpIntro.currentItem++
        } else {
            viewModel.onIntroCompleted()
//            showInterIntro {
            prepareThenGoToHome()
//            }
        }
    }

    override fun onStop() {
        super.onStop()
        binding.vShowInterAds.gone()
    }

//    fun showNativeFullScreen(frNative: ViewGroup) {
//
//        if (!AdsHelper.isNetworkConnected(this) || !AdmobLib.getShowAds() || AdmobLib.getCheckTestDevice()) {
//            binding.vpIntro.currentItem++
//            return
//        }
//
//        if (isShowNativeFull1 && binding.vpIntro.currentItem == 1) {
//            AdmobLib.showNative(
//                this@IntroActivity,
//                AdsManager.NATIVE_FULL_SCREEN_INTRO,
//                frNative,
//                layout = R.layout.native_ads_full_screen,
//                size = GoogleENative.UNIFIED_FULL_SCREEN,
//                onAdsShowed = {
//                    Unit
//                },
//                onAdsShowFail = {
//                    if (isNext) binding.vpIntro.currentItem++ else binding.vpIntro.currentItem--
//                    Unit
//                })
//            return
//        }
//
//        if (isShowNativeFull2 && (binding.vpIntro.currentItem == 2 || binding.vpIntro.currentItem == 3)) {
//
//            AdmobLib.showNative(
//                this@IntroActivity,
//                AdsManager.NATIVE_FULL_SCREEN_INTRO_2,
//                frNative,
//                layout = R.layout.native_ads_full_screen,
//                size = GoogleENative.UNIFIED_FULL_SCREEN,
//                onAdsShowed = {
//                    Unit
//                },
//                onAdsShowFail = {
//                    if (isNext) binding.vpIntro.currentItem++ else binding.vpIntro.currentItem--
//                    Unit
//                })
//        }
//    }

//    private fun showInterIntro(navAction: () -> Unit) {
//        if (RemoteConfig.remoteInterIntro == 1L) {
//            if (!InterstitialAdPreloader.isAdAvailable(AdsManager.INTER_INTRO.adsID)) {
//                AdmobLib.loadAndShowInterWithNativeAfter(
//                    this,
//                    AdsManager.INTER_INTRO,
//                    AdsManager.NATIVE_FULL_SCREEN_AFTER_INTER,
//                    binding.vShowInterAds,
//                    isShowNativeAfter = AdsManager.isShowNativeFullScreen(),
//                    nativeLayout = R.layout.native_ads_full_screen,
//                    navAction = { navAction() }
//                )
//            } else {
//                AdmobLib.showInterNewAPIWithNativeAfter(
//                    mActivity = this,
//                    interModel = AdsManager.INTER_INTRO,
//                    nativeModel = AdsManager.NATIVE_FULL_SCREEN_AFTER_INTER,
//                    vShowInterAds = binding.vShowInterAds,
//                    isShowNativeAfter = AdsManager.isShowNativeFullScreen(),
//                    nativeLayout = R.layout.native_ads_full_screen,
//                    navAction = {
//                        navAction()
//                    }
//                )
//            }
//        } else {
//            navAction()
//        }
//    }


}
