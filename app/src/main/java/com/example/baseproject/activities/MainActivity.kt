package com.example.baseproject.activities

import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.viewpager2.widget.ViewPager2
import com.example.baseproject.adapters.MainVPAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityMainBinding
import com.example.baseproject.ui.main.MainViewModel
import com.example.baseproject.utils.animateBottomNavPress
import com.example.baseproject.utils.animateBottomNavSelection
import com.example.baseproject.utils.enableMarquee
import com.example.baseproject.utils.setBottomNavLabelSelected
import com.example.baseproject.utils.setOnUnDoubleClick
import kotlinx.coroutines.flow.collectLatest

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    companion object {
        private const val BOTTOM_NAV_ICON_SELECTED_SCALE = 1.22f
        private const val BOTTOM_NAV_ICON_SELECTED_LIFT_DP = 2f
    }

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
        binding.tvLib.enableMarquee()
        binding.tvDaily.enableMarquee()
        binding.tvColorRealm.enableMarquee()
        binding.tvAlbum.enableMarquee()
        binding.tvMyWork.enableMarquee()

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
                    binding.viewPager2.setCurrentItem(state.selectedTab, false)
                }
                updateByPosition(state.selectedTab)
            }
        }
    }

    override fun initActionView() {

        binding.btnLib.setOnUnDoubleClick {
            binding.ivLib.animateBottomNavPress()
            viewModel.onTabSelected(0)
        }
        binding.btnDaily.setOnUnDoubleClick {
            binding.ivDaily.animateBottomNavPress()
            viewModel.onTabSelected(1)
        }
        binding.ivColorRealm.setOnUnDoubleClick {
            binding.ivColorRealm.animateBottomNavPress()
            viewModel.onTabSelected(2)
        }
        binding.btnAlbum.setOnUnDoubleClick {
            binding.ivAlbum.animateBottomNavPress()
            viewModel.onTabSelected(3)
        }
        binding.btnMyWork.setOnUnDoubleClick {
            binding.ivMyWork.animateBottomNavPress()
            viewModel.onTabSelected(4)
        }
    }

    private fun resetItemSelector() {
        setBottomNavItemSelected(binding.ivLib, binding.tvLib, false)
        setBottomNavItemSelected(binding.ivDaily, binding.tvDaily, false)
        setBottomNavItemSelected(binding.ivAlbum, binding.tvAlbum, false)
        setBottomNavItemSelected(binding.ivMyWork, binding.tvMyWork, false)

        binding.ivColorRealm.isActivated = false
        binding.ivColorRealm.jumpDrawablesToCurrentState()
        binding.ivColorRealm.refreshDrawableState()
        binding.ivColorRealm.animateBottomNavSelection(false, selectedScale = 1f, liftDp = 0f)

        binding.tvColorRealm.setBottomNavLabelSelected(false)
    }

    private fun updateByPosition(position: Int) {
        resetItemSelector()
        when (position) {
            0 -> {
                setBottomNavItemSelected(binding.ivLib, binding.tvLib, true)
            }

            1 -> {
                setBottomNavItemSelected(binding.ivDaily, binding.tvDaily, true)
            }

            2 -> {
                binding.tvColorRealm.setBottomNavLabelSelected(true)
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
                setBottomNavItemSelected(binding.ivAlbum, binding.tvAlbum, true)
            }

            else -> {
                setBottomNavItemSelected(binding.ivMyWork, binding.tvMyWork, true)
            }
        }
    }

    private fun setBottomNavItemSelected(
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        icon.isActivated = selected
        icon.jumpDrawablesToCurrentState()
        icon.refreshDrawableState()
        icon.animateBottomNavSelection(
            selected = selected,
            selectedScale = BOTTOM_NAV_ICON_SELECTED_SCALE,
            liftDp = BOTTOM_NAV_ICON_SELECTED_LIFT_DP
        )
        label.setBottomNavLabelSelected(selected)
    }

}
