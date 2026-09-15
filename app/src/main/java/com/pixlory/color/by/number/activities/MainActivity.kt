package com.pixlory.color.by.number.activities

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.adapters.MainVPAdapter
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.databinding.ActivityMainBinding
import com.pixlory.color.by.number.ui.main.MainViewModel
import com.pixlory.color.by.number.ui.main.MainRevealCoordinator
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.ads.AdsManager
import com.pixlory.color.by.number.utils.ads.RemoteConfig
import com.pixlory.color.by.number.utils.animateBottomNavPress
import com.pixlory.color.by.number.utils.animateBottomNavSelection
import com.pixlory.color.by.number.utils.enableMarquee
import com.pixlory.color.by.number.utils.gone
import com.pixlory.color.by.number.utils.setBottomNavLabelSelected
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.utils.visible
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.utils.GoogleENative
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    override val shouldMonitorNetwork = true

    companion object {
        const val EXTRA_SELECTED_TAB = "EXTRA_SELECTED_TAB"
        const val EXTRA_LIBRARY_CATEGORY = "EXTRA_LIBRARY_CATEGORY"
        const val EXTRA_REALM_ID = "EXTRA_REALM_ID"
        const val EXTRA_SKIP_PREPARING_OVERLAY = "EXTRA_SKIP_PREPARING_OVERLAY"
        const val EXTRA_SHOW_LIBRARY_PREPARING = "EXTRA_SHOW_LIBRARY_PREPARING"
        const val TAB_LIBRARY = 0
        const val TAB_COLOR_REALM = 2
        private const val BOTTOM_NAV_ICON_SELECTED_SCALE = 1.22f
        private const val BOTTOM_NAV_ICON_SELECTED_LIFT_DP = 2f
        private const val PREPARING_MIN_DURATION_MS = 900L
        private const val PREPARING_MAX_DURATION_MS = 5_500L
        private const val PREPARING_FADE_DURATION_MS = 260L
    }

    private val viewModel: MainViewModel by viewModels {
        SimpleViewModelFactory { MainViewModel() }
    }

    private val mAdapter by lazy {
        MainVPAdapter(this)
    }
    private var shouldShowPreparingOverlay = false
    private var preparingTimeoutJob: Job? = null
    private var preparingRevealJob: Job? = null
    private var mainContentInitialized = false
    private var pendingLibraryCategory: String? = null
    private val revealCoordinator = MainRevealCoordinator(PREPARING_MIN_DURATION_MS)
    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }
    private val nativeHomeHandler = Handler(Looper.getMainLooper())
    private var isLoadingCollapsibleHome = false
    private val nativeHomeReload = object : Runnable {
        override fun run() {
            if (!isLoadingCollapsibleHome && AdsManager.isReloadingCollapsibleHome()) {
                isLoadingCollapsibleHome = true
                loadAndShowNativeCollapsibleHome {
                    AdsManager.updateCollapsibleHome()
                    isLoadingCollapsibleHome = false
                }
            }
            nativeHomeHandler.postDelayed(this, 5_000L)
        }
    }

    override fun initData() {
        val initialTab = intent.getIntExtra(EXTRA_SELECTED_TAB, 0)
        viewModel.onTabSelected(initialTab)
        pendingLibraryCategory = intent.getStringExtra(EXTRA_LIBRARY_CATEGORY)
        preloadRequestedRealm()
    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)
        shouldShowPreparingOverlay = shouldShowPreparingOverlay()
        if (shouldShowPreparingOverlay) {
            binding.contentPreparingOverlay.apply {
                alpha = 1f
                visibility = View.VISIBLE
                bringToFront()
                doOnPreDraw {
                    onPreparingOverlayDrawn()
                }
            }
        } else {
            binding.contentPreparingOverlay.gone()
            initializeMainContent()
        }
    }

    private fun onPreparingOverlayDrawn() {
        revealCoordinator.onPreparingDrawn(SystemClock.elapsedRealtime())
            ?.let(::schedulePreparingReveal)

        preparingTimeoutJob?.cancel()
        preparingTimeoutJob = lifecycleScope.launch {
            delay(PREPARING_MAX_DURATION_MS)
            revealCoordinator.onTimeout()?.let(::schedulePreparingReveal)
        }

        // Let the preparing surface complete this frame before ViewPager creates its fragments.
        binding.root.post { initializeMainContent() }
    }

    private fun initializeMainContent() {
        if (mainContentInitialized) return
        mainContentInitialized = true

        binding.tvLib.enableMarquee()
        binding.tvDaily.enableMarquee()
        binding.tvColorRealm.enableMarquee()
        binding.tvAlbum.enableMarquee()
        binding.tvMyWork.enableMarquee()

        binding.viewPager2.apply {
            adapter = mAdapter
            isUserInputEnabled = false
            offscreenPageLimit = 1
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

    fun notifyInitialLibraryContentDrawn() {
        SharedPrefManager.hasSeenLibraryPreparing = true
        if (!shouldShowPreparingOverlay) return

        revealCoordinator.onContentReady(SystemClock.elapsedRealtime())
            ?.let(::schedulePreparingReveal)
    }

    private fun shouldShowPreparingOverlay(): Boolean =
        intent.getBooleanExtra(EXTRA_SHOW_LIBRARY_PREPARING, false) ||
            (!intent.getBooleanExtra(EXTRA_SKIP_PREPARING_OVERLAY, false) &&
                !SharedPrefManager.hasSeenLibraryPreparing)

    private fun hidePreparingOverlay() {
        if (binding.contentPreparingOverlay.visibility != View.VISIBLE) return

        revealCoordinator.onRevealed()
        binding.contentPreparingOverlay.animate().cancel()
        binding.contentPreparingOverlay.animate()
            .alpha(0f)
            .setDuration(PREPARING_FADE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.contentPreparingOverlay.gone()
                binding.contentPreparingOverlay.alpha = 1f
            }
            .start()
    }

    private fun schedulePreparingReveal(plan: MainRevealCoordinator.RevealPlan) {
        preparingTimeoutJob?.cancel()
        preparingRevealJob?.cancel()
        preparingRevealJob = lifecycleScope.launch {
            delay(plan.delayMillis)
            hidePreparingOverlay()
        }
    }

    override fun onDestroy() {
        preparingTimeoutJob?.cancel()
        preparingRevealJob?.cancel()
        super.onDestroy()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onTabSelected(intent.getIntExtra(EXTRA_SELECTED_TAB, 0))
        pendingLibraryCategory = intent.getStringExtra(EXTRA_LIBRARY_CATEGORY)
        preloadRequestedRealm()
    }

    fun consumeLibraryCategoryRequest(): String? {
        val category = pendingLibraryCategory
        pendingLibraryCategory = null
        intent.removeExtra(EXTRA_LIBRARY_CATEGORY)
        return category
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.main)
        nativeHomeHandler.removeCallbacks(nativeHomeReload)
        if (RemoteConfig.remoteNativeCollapsibleHome in 1L..2L && AdmobLib.getShowAds()) {
            nativeHomeHandler.post(nativeHomeReload)
        } else {
            loadAndShowNativeCollapsibleHome {}
        }
    }

    override fun onPause() {
        nativeHomeHandler.removeCallbacks(nativeHomeReload)
        super.onPause()
    }

    private fun loadAndShowNativeCollapsibleHome(onShowOrFailed: () -> Unit) {
        if (!AdmobLib.getShowAds()) {
            binding.frNativeSmall.gone()
            binding.frNativeExpand.gone()
            binding.whiteLine.gone()
            onShowOrFailed()
            return
        }

        when (RemoteConfig.remoteNativeCollapsibleHome) {
            1L -> {
                binding.frNativeSmall.visible()
                binding.frNativeExpand.gone()
                AdmobLib.loadAndShowNative(
                    activity = this,
                    admobNativeModel = AdsManager.NATIVE_COLLAPSIBLE_HOME,
                    viewGroup = binding.frNativeSmall,
                    size = GoogleENative.UNIFIED_SMALL_LIKE_BANNER,
                    layout = R.layout.native_ads_custom_small_like_banner,
                    onAdsLoaded = {
                        binding.whiteLine.visible()
                        onShowOrFailed()
                    },
                    onAdsLoadFail = {
                        binding.whiteLine.gone()
                        onShowOrFailed()
                    }
                )
            }

            2L -> {
                binding.frNativeSmall.visible()
                binding.frNativeExpand.visible()
                AdmobLib.loadAndShowNativeCollapsibleSingle(
                    activity = this,
                    admobNativeModel = AdsManager.NATIVE_COLLAPSIBLE_HOME,
                    viewGroupExpanded = binding.frNativeExpand,
                    viewGroupCollapsed = binding.frNativeSmall,
                    layoutExpanded = R.layout.native_ads_custom_medium_bottom,
                    layoutCollapsed = R.layout.native_ads_custom_small_like_banner,
                    onAdsLoaded = {
                        binding.whiteLine.visible()
                        onShowOrFailed()
                    },
                    onAdsLoadFail = {
                        binding.whiteLine.gone()
                        onShowOrFailed()
                    }
                )
            }

            else -> {
                binding.frNativeSmall.gone()
                binding.frNativeExpand.gone()
                binding.whiteLine.gone()
                onShowOrFailed()
            }
        }
    }

    private fun preloadRequestedRealm() {
        val realmId = SharedPrefManager.selectedRealmId
            ?: intent.getStringExtra(EXTRA_REALM_ID)
            ?: RealmCatalog.default.id
        appContainer.realmContentPreloader.preload(realmId)
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
