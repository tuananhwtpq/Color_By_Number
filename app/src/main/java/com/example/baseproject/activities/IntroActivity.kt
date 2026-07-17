package com.example.baseproject.activities

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.viewpager2.widget.ViewPager2
import com.example.baseproject.MyApplication
import com.example.baseproject.adapters.IntroViewPagerAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityIntroBinding
import com.example.baseproject.ui.intro.IntroViewModel
import com.example.baseproject.utils.gone

class IntroActivity : BaseActivity<ActivityIntroBinding>(ActivityIntroBinding::inflate) {

    private val viewModel: IntroViewModel by viewModels {
        val appContainer = (application as MyApplication).appContainer
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
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
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
        startActivity(intent)
        finish()
    }

    fun nextPage() {
        if (binding.vpIntro.currentItem < mAdapter.itemCount - 1) {
            binding.vpIntro.currentItem++
        } else {
            viewModel.onIntroCompleted()
//            showInterIntro {
            goToHome()
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
