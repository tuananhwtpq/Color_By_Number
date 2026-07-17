package com.example.baseproject.activities

import androidx.activity.viewModels
import androidx.viewpager2.widget.ViewPager2
import com.example.baseproject.adapters.MainVPAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityMainBinding
import com.example.baseproject.ui.main.MainViewModel
import com.example.baseproject.utils.animateBottomNavLabel
import com.example.baseproject.utils.animateBottomNavPress
import com.example.baseproject.utils.animateBottomNavSelection
import com.example.baseproject.utils.applyBottomNavRipple
import com.example.baseproject.utils.enableMarquee
import com.example.baseproject.utils.setOnUnDoubleClick
import kotlinx.coroutines.flow.collectLatest

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val viewModel: MainViewModel by viewModels {
        SimpleViewModelFactory { MainViewModel() }
    }

    private val mAdapter by lazy {
        MainVPAdapter(this)
    }

    override fun initData() {
        viewModel.onTabSelected(0)
    }

    override fun initView() {
        binding.btnLib.applyBottomNavRipple()
        binding.btnDaily.applyBottomNavRipple()
        binding.btnAlbum.applyBottomNavRipple()
        binding.btnMyWork.applyBottomNavRipple()
        binding.ivColorRealm.applyBottomNavRipple(center = true)
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
                viewModel.onTabSelected(position)
            }
        })

        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                if (binding.viewPager2.currentItem != state.selectedTab) {
                    binding.viewPager2.currentItem = state.selectedTab
                }
                updateByPosition(state.selectedTab)
            }
        }
    }

    override fun initActionView() {

        binding.btnLib.setOnUnDoubleClick {
            binding.btnLib.animateBottomNavPress()
            viewModel.onTabSelected(0)
        }
        binding.btnDaily.setOnUnDoubleClick {
            binding.btnDaily.animateBottomNavPress()
            viewModel.onTabSelected(1)
        }
        binding.ivColorRealm.setOnUnDoubleClick {
            binding.ivColorRealm.animateBottomNavPress()
            viewModel.onTabSelected(2)
        }
        binding.btnAlbum.setOnUnDoubleClick {
            binding.btnAlbum.animateBottomNavPress()
            viewModel.onTabSelected(3)
        }
        binding.btnMyWork.setOnUnDoubleClick {
            binding.btnMyWork.animateBottomNavPress()
            viewModel.onTabSelected(4)
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
