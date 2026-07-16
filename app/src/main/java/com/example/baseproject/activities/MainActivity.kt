package com.example.baseproject.activities

import androidx.viewpager2.widget.ViewPager2
import com.example.baseproject.adapters.MainVPAdapter
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityMainBinding
import com.example.baseproject.utils.animateBottomNavLabel
import com.example.baseproject.utils.animateBottomNavPress
import com.example.baseproject.utils.animateBottomNavSelection
import com.example.baseproject.utils.enableMarquee
import com.example.baseproject.utils.setOnUnDoubleClick

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val mAdapter by lazy {
        MainVPAdapter(this)
    }

    override fun initData() {
        updateByPosition(0)
    }

    override fun initView() {
        binding.btnLib.enableMarquee()
        binding.btnDaily.enableMarquee()
        binding.tvColorRealm.enableMarquee()
        binding.btnAlbum.enableMarquee()
        binding.btnMyWork.enableMarquee()

        binding.viewPager2.apply {
            adapter = mAdapter
            isUserInputEnabled = false
        }

        binding.viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateByPosition(position)
            }
        })


    }

    override fun initActionView() {

        binding.btnLib.setOnUnDoubleClick {
            binding.btnLib.animateBottomNavPress()
            binding.viewPager2.currentItem = 0
        }
        binding.btnDaily.setOnUnDoubleClick {
            binding.btnDaily.animateBottomNavPress()
            binding.viewPager2.currentItem = 1
        }
        binding.ivColorRealm.setOnUnDoubleClick {
            binding.ivColorRealm.animateBottomNavPress()
            binding.viewPager2.currentItem = 2
        }
        binding.btnAlbum.setOnUnDoubleClick {
            binding.btnAlbum.animateBottomNavPress()
            binding.viewPager2.currentItem = 3
        }
        binding.btnMyWork.setOnUnDoubleClick {
            binding.btnMyWork.animateBottomNavPress()
            binding.viewPager2.currentItem = 4
        }
    }

    private fun resetItemSelector() {
        binding.btnLib.animateBottomNavLabel(false)
        binding.btnDaily.animateBottomNavLabel(false)
        binding.btnAlbum.animateBottomNavLabel(false)
        binding.btnMyWork.animateBottomNavLabel(false)

        binding.ivColorRealm.isActivated = false
        binding.ivColorRealm.jumpDrawablesToCurrentState()
        binding.ivColorRealm.refreshDrawableState()
        binding.ivColorRealm.animateBottomNavSelection(false, selectedScale = 1f, liftDp = 0f)

        binding.tvColorRealm.animateBottomNavLabel(false)
    }

    private fun updateByPosition(position: Int) {
        resetItemSelector()
        when (position) {
            0 -> {
                binding.btnLib.animateBottomNavLabel(true)
            }

            1 -> {
                binding.btnDaily.animateBottomNavLabel(true)
            }

            2 -> {
                binding.tvColorRealm.animateBottomNavLabel(true)
                binding.ivColorRealm.isActivated = true
                binding.ivColorRealm.jumpDrawablesToCurrentState()
                binding.ivColorRealm.refreshDrawableState()
                binding.ivColorRealm.animateBottomNavSelection(
                    true,
                    selectedScale = 1.12f,
                    liftDp = 8f
                )
            }

            3 -> {
                binding.btnAlbum.animateBottomNavLabel(true)
            }

            else -> {
                binding.btnMyWork.animateBottomNavLabel(true)
            }
        }
    }

}
