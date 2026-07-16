package com.example.baseproject.fragments

import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.activities.IntroActivity
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.databinding.FragmentIntroBinding
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.utils.GoogleENative

class IntroFragment : BaseFragment<FragmentIntroBinding>(FragmentIntroBinding::inflate) {

    private val ARG_OBJECT = "position"

    private val position by lazy {
        requireArguments().getInt(ARG_OBJECT)
    }

    override fun initData() {}

    override fun initView() {
        if (arguments != null) {
            fragmentPosition(requireArguments().getInt(ARG_OBJECT))
        }
    }

    override fun initActionView() {
        binding.btnNext2.setOnClickListener {
            (activity as? IntroActivity)?.nextPage()
        }

        binding.btnNext1.setOnClickListener {
            (activity as? IntroActivity)?.nextPage()
        }
    }

    private fun fragmentPosition(position: Int) {
        when (position) {
            0 -> {
//                Glide.with(this).load(R.drawable.bg_intro_1).into(binding.ivIntro)
                binding.tvBig.text = getString(R.string.next)
                binding.tvNameSmall.text =
                    getString(R.string.next)
                binding.btnNext2.text = getString(R.string.next)
                binding.tvNext1.text = getString(R.string.next)
                binding.dotIndicator1.setImageResource(R.drawable.ic_dot_1)
                binding.dotIndicator2.setImageResource(R.drawable.ic_dot_1)
            }

            1 -> {
//                Glide.with(this).load(R.drawable.bg_intro_2).into(binding.ivIntro)
                binding.tvBig.text = getString(R.string.next)
                binding.tvNameSmall.text =
                    getString(R.string.next)
                binding.btnNext2.text = getString(R.string.next)
                binding.tvNext1.text = getString(R.string.next)
                binding.dotIndicator1.setImageResource(R.drawable.ic_dot_2)
                binding.dotIndicator2.setImageResource(R.drawable.ic_dot_2)
            }

            else -> {
//                Glide.with(this).load(R.drawable.bg_intro_3).into(binding.ivIntro)
                binding.tvBig.text = getString(R.string.next)
                binding.tvNameSmall.text =
                    getString(R.string.next)
                binding.btnNext2.text = getString(R.string.get_started)
                binding.tvNext1.text = getString(R.string.get_started)
                binding.dotIndicator1.setImageResource(R.drawable.ic_dot_3)
                binding.dotIndicator2.setImageResource(R.drawable.ic_dot_3)
            }
        }
    }

//    private fun showNativeIntro2(updateView: (Boolean) -> Unit) {
//        if (RemoteConfig.remoteNativeIntro == 0L || position != 1) return
//        binding.frNative.visible()
//        AdmobLib.showNative(
//            requireActivity(),
//            AdsManager.NATIVE_INTRO,
//            binding.frNative,
//            GoogleENative.UNIFIED_MEDIUM,
//            R.layout.native_ads_custom_medium_bottom,
//            onAdsShowFail = {
//                updateView(false)
//            },
//            onAdsShowed = {
//                updateView(true)
//            }
//        )
//    }
//
//    override fun onResume() {
//        super.onResume()
//        showNativeIntro2 { isShowAds ->
//            if (isShowAds) {
//                binding.dotIndicator1.invisible()
//                binding.btnNext1.gone()
//                binding.dotIndicator2.visible()
//                binding.btnNext2.visible()
//
//            } else {
//                binding.dotIndicator1.visible()
//                binding.btnNext1.visible()
//                binding.dotIndicator2.gone()
//                binding.btnNext2.gone()
//            }
//        }
//    }

}