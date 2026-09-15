package com.pixlory.color.by.number.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.snackbar.Snackbar
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.data.repository.AchievementEvent
import com.pixlory.color.by.number.adapters.PaletteAdapter
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivityPaintBinding
import com.pixlory.color.by.number.dialog.WatchAdsDialog
import com.pixlory.color.by.number.highlight.HighlightThemes
import com.pixlory.color.by.number.ui.paint.PaintUiEvent
import com.pixlory.color.by.number.ui.paint.PaintUiState
import com.pixlory.color.by.number.ui.paint.PaintViewModel
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.Constants
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.SoundEffect
import com.pixlory.color.by.number.utils.SoundScene
import com.pixlory.color.by.number.utils.ads.AdsManager
import com.pixlory.color.by.number.utils.ads.RemoteConfig
import com.pixlory.color.by.number.utils.gone
import com.pixlory.color.by.number.utils.setOnSoundClickListener
import com.pixlory.color.by.number.utils.soundManagerOrNull
import com.pixlory.color.by.number.utils.visible
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.utils.GoogleENative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class PaintActivity : BaseActivity<ActivityPaintBinding>(ActivityPaintBinding::inflate) {

    override val shouldMonitorNetwork = true
    override val soundScene = SoundScene.DRAWING

    companion object {
        private const val GUIDE_STEP_01 = 0
        private const val GUIDE_STEP_02 = 1
        private const val GUIDE_STEP_03 = 2
        private const val GUIDE_PALETTE_HORIZONTAL_PADDING_DP = 8f
        private const val GUIDE_PALETTE_VERTICAL_PADDING_DP = 14f
        private const val WORK_PREVIEW_THUMBNAIL_SIZE = 900
        private const val COMPLETED_NAVIGATION_DELAY_MS = 400L
        private const val PREPARATION_MIN_DURATION_MS = 350L
        private const val PREPARATION_FADE_OUT_MS = 160L
        private const val THUMBNAIL_SAVE_DEBOUNCE_MS = 450L
        private const val DRAW_INTER_WARNING_MS = 5_000L
        private const val DRAW_INTER_NORMAL_TICK_MS = 1_000L
        private const val DRAW_INTER_WARNING_TICK_MS = 200L
        // private const val HINT_REWARDED_AD_TIMEOUT_MS = 30_000L

        const val EXTRA_CATEGORY = "CATEGORY"
        const val EXTRA_LEVEL_ID = "LEVEL_ID"
        const val EXTRA_PREPARATION_THUMBNAIL = "EXTRA_PREPARATION_THUMBNAIL"
        const val EXTRA_FROM_HOME = "EXTRA_FROM_HOME"
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private val achievementRepository by lazy {
        appContainer.achievementRepository
    }

    private val viewModel: PaintViewModel by viewModels {
        SimpleViewModelFactory {
            PaintViewModel(
                appContainer.assetLevelRepository,
                appContainer.paintingProgressRepository,
                appContainer.thumbnailRepository
            )
        }
    }

    private lateinit var adapter: PaletteAdapter
    private var currentRenderKey: String? = null
    private var lastCompletedMaskColors: Set<Int> = emptySet()
    private var category: String? = null
    private var levelId: String? = null
    private var preparationThumbnail: String? = null
    private var guideStep: Int = GUIDE_STEP_01
    private var isGuideVisible: Boolean = false
    private var isPreparationVisible: Boolean = true
    private var preparationStartedAtMillis: Long = 0L
    private var shouldStartGuideWhenContentReady: Boolean = false
    private var isLoadingVisible: Boolean = false
    private var isFullColorPreviewVisible: Boolean = false
    private var isFillAllPreviewActive: Boolean = false
    private var isNavigatingToCompleted: Boolean = false
    private var isCanvasAtInitialViewport: Boolean = true
    private var isResettingViewport: Boolean = false
    // private var isHintRewardAdInProgress: Boolean = false
    private var fullPreviewBitmap: Bitmap? = null
    private var fullPreviewRenderKey: String? = null
    private var lastRenderedSelectedPaletteIndex: Int = -1
    private var thumbnailSaveJob: Job? = null
    private var drawInterRemainingMs: Long? = null
    private var drawInterLastTickMs = 0L
    private var drawInterCountdownJob: Job? = null
    private var drawInterSnackbar: Snackbar? = null
    private var drawInterWarningSeconds: Int? = null
    private var isDrawInterstitialShowing = false
    private var isRewardedHintAdShowing = false
    // private var hintRewardedAdTimeoutJob: Job? = null
    private val guideRectBuffer = Rect()
    // private val hintRewardedAdModel by lazy {
    //     AdmobRewardedModel(BuildConfig.HINT_REWARDED_AD_UNIT_ID)
    // }

    private val previewMultiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    override fun initData() {
        category = intent.getStringExtra(EXTRA_CATEGORY)
        levelId = intent.getStringExtra(EXTRA_LEVEL_ID)
        preparationThumbnail = intent.getStringExtra(EXTRA_PREPARATION_THUMBNAIL)

        if (category == null || levelId == null) {
            finish()
            return
        }
    }

    override fun initView() {
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (intent.getBooleanExtra(EXTRA_FROM_HOME, false)) {
                    loadAndShowInterBackToHome(
                        navAction = { finish() },
                        viewBlock = interAdBlockView()
                    )
                } else {
                    finish()
                }
            }
        })
        initViews()
        setupGuideIfNeeded()
        if (!SharedPrefManager.isShowGuide) {
            loadAndShowNativeCollapsibleDraw {}
        }
        collectUi()
    }

    override fun initActionView() {
        binding.btnBack.setOnSoundClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnHint.setOnSoundClickListener { onHintButtonClicked() }
        binding.btnPreviewFull.setOnSoundClickListener { toggleFullColorPreview() }
        binding.btnFillAll.setOnSoundClickListener { toggleFillAllOnCanvas() }
        binding.btnCloseFullPreview.setOnSoundClickListener { hideFullColorPreview() }
        binding.fullPreviewOverlay.setOnSoundClickListener { hideFullColorPreview() }
        binding.ivFullPreview.setOnClickListener { }
        binding.btnZoomNormal.setOnSoundClickListener { resetCanvasViewport() }
//        binding.btnReset.setOnClickListener { viewModel.requestResetConfirmation() }

        viewModel.loadLevel(
            category = category ?: return,
            levelId = levelId ?: return
        )
    }

    private fun initViews() {
        syncPaintSettings()
        renderHintBalance()
        showPreparationOverlay()
        binding.rvPalette.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.paintCanvas.onRegionFilledListener = { maskInt ->
            viewModel.onRegionFilled(maskInt)
            scheduleThumbnailSave()
        }
        binding.paintCanvas.onViewportInitialStateChangedListener = { isAtInitialViewport ->
            isCanvasAtInitialViewport = isAtInitialViewport
            updateZoomNormalButtonVisibility()
        }
        binding.fullPreviewOverlay.visibility = View.GONE
        binding.completionAnimationOverlay.visibility = View.GONE
        binding.lavCompletionBlast.cancelAnimation()
        binding.llGuide.setOnSoundClickListener {
            when (guideStep) {
                GUIDE_STEP_01 -> showGuideStep(GUIDE_STEP_02)
                GUIDE_STEP_02 -> showGuideStep(GUIDE_STEP_03)
                else -> finishGuide()
            }
        }
    }

    private fun collectUi() {
        collectWithLifecycle {
            viewModel.uiState.collectLatest(::renderState)
        }
        collectWithLifecycle {
            viewModel.events.collectLatest(::handleEvent)
        }
    }

    private fun renderState(state: PaintUiState) {
        isLoadingVisible = state.isLoading
        binding.progressBar.visibility =
            if (state.isLoading && !isGuideVisible && !isPreparationVisible) View.VISIBLE else View.GONE
//        binding.tvTitle.text = state.title

        if (state.palette.isNotEmpty() && (!this::adapter.isInitialized || adapter.sourceItemCount != state.palette.size)) {
            adapter = PaletteAdapter(
                items = state.palette,
                removeCompletedColors = Constants.REMOVE_COMPLETED_COLORS_FROM_PALETTE
            ) { originalIndex, _ ->
                viewModel.onPaletteSelected(originalIndex)
            }
            binding.rvPalette.adapter = adapter
        }

        if (this::adapter.isInitialized) {
            adapter.setPaletteState(
                selectedIndex = state.selectedPaletteIndex,
                completedIndexes = state.completedIndexes,
                paletteProgress = state.paletteProgress
            )
            scrollPaletteToSelectedColor(state.selectedPaletteIndex)
        }

        val renderData = state.renderData
        if (renderData != null) {
            val newRenderKey = "${renderData.category}/${renderData.levelId}"
            val isInitialRenderForLevel = currentRenderKey != newRenderKey

            binding.progressBarLevel.setProgress(state.overallProgress, animate = !isInitialRenderForLevel)

            if (isInitialRenderForLevel) {
                currentRenderKey = newRenderKey
                resetFullPreviewCache(newRenderKey)
                exitFillAllPreviewState()
                lifecycleScope.launch {
                    showPreparationCanvasStage()
                    binding.paintCanvas.setBitmapsSuspend(
                        renderData.lineBitmap,
                        renderData.displayLineBitmap,
                        renderData.displayLineSvg,
                        renderData.maskBitmap,
                        renderData.detailBitmap,
                        renderData.fillCoverageBitmap,
                        renderData.regions
                    )
                    if (state.completedColorMap.isNotEmpty()) {
                        binding.paintCanvas.restoreProgressSuspend(state.completedColorMap)
                    }
                    binding.paintCanvas.setCompletedRegions(state.completedMaskColors)
                    binding.paintCanvas.setActiveColors(state.activeColors)
                    binding.paintCanvas.highlightNumber(state.highlightMaskColors)
                    showPreparationReadyStage()
                    hidePreparationOverlayWhenReady()
                    showPendingGuideIfNeeded()
                    binding.root.post { updateGuideOverlayForCurrentStep() }
                }
            } else if (!isFillAllPreviewActive) {
                // Đang xem bản tô đầy thì bỏ qua: mọi lệnh vẽ ở đây sẽ đè lên lớp preview
                // (ví dụ chọn màu khác trên palette sẽ bật lại highlight giữa ảnh đã tô).
                if (lastCompletedMaskColors.isNotEmpty() && state.completedMaskColors.isEmpty()) {
                    binding.paintCanvas.setCompletedRegions(emptySet())
                    binding.paintCanvas.resetProgress()
                } else {
                    binding.paintCanvas.setCompletedRegions(state.completedMaskColors)
                }
                binding.paintCanvas.setActiveColors(state.activeColors)
                binding.paintCanvas.highlightNumber(state.highlightMaskColors)
            }
        }

        updateFullPreviewVisibility()
        lastCompletedMaskColors = state.completedMaskColors
    }

    private fun setupGuideIfNeeded() {
        if (SharedPrefManager.isShowGuide) {
            shouldStartGuideWhenContentReady = true
            isGuideVisible = false
            binding.llGuide.visibility = View.GONE
            setMainContentVisible(true)
        } else {
            shouldStartGuideWhenContentReady = false
            isGuideVisible = false
            binding.llGuide.visibility = View.GONE
            setMainContentVisible(true)
        }
    }

    private fun showPendingGuideIfNeeded() {
        if (!shouldStartGuideWhenContentReady || isGuideVisible) return

        shouldStartGuideWhenContentReady = false
        showGuideStep(GUIDE_STEP_01)
    }

    private fun showGuideStep(step: Int) {
        guideStep = step
        isGuideVisible = true
        binding.llGuide.visibility = View.VISIBLE

        binding.tvGuide01.visibility = if (step == GUIDE_STEP_01) View.VISIBLE else View.GONE
        binding.iv01.visibility = if (step == GUIDE_STEP_01) View.VISIBLE else View.GONE
        binding.tvGuide02.visibility = if (step == GUIDE_STEP_02) View.VISIBLE else View.GONE
        binding.iv02.visibility = if (step == GUIDE_STEP_02) View.VISIBLE else View.GONE
        binding.tvGuide03.visibility = if (step == GUIDE_STEP_03) View.VISIBLE else View.GONE
        binding.iv03.visibility = if (step == GUIDE_STEP_03) View.VISIBLE else View.GONE

        updateZoomNormalButtonVisibility()
        binding.root.post { updateGuideOverlayForCurrentStep() }
    }

    private fun updateGuideOverlayForCurrentStep() {
        if (!isGuideVisible || binding.llGuide.visibility != View.VISIBLE) return
        if (binding.guideOverlay.width == 0 || binding.guideOverlay.height == 0) return

        when (guideStep) {
            GUIDE_STEP_01 -> {
                val targetRect = getVisiblePaletteItemsRectInRoot()
                    ?: getViewRectInRoot(binding.rvPalette)
                binding.guideOverlay.setSpotlight(
                    rect = targetRect,
                    shape = GuideOverlayView.SpotlightShape.LeftRoundRect,
                    radius = dp(100f)
                )
            }

            GUIDE_STEP_02 -> {
                val imageRect = getDisplayedCanvasImageRectInRoot()
                    ?: getViewRectInRoot(binding.paintCanvas)
                val diameter = (minOf(imageRect.width(), imageRect.height()) * 0.42f)
                    .coerceIn(dp(112f), dp(156f))
                val centerX = imageRect.centerX()
                val centerY = imageRect.centerY()
                val targetRect = RectF(
                    centerX - diameter / 2f,
                    centerY - diameter / 2f,
                    centerX + diameter / 2f,
                    centerY + diameter / 2f
                )
                binding.guideOverlay.setSpotlight(
                    rect = targetRect,
                    shape = GuideOverlayView.SpotlightShape.Oval
                )
            }

            GUIDE_STEP_03 -> {
                val targetRect = getViewRectInRoot(binding.btnHint).apply {
                    inset(-dp(13f), -dp(13f))
                }
                binding.guideOverlay.setSpotlight(
                    rect = targetRect,
                    shape = GuideOverlayView.SpotlightShape.Oval
                )
            }
        }
    }

    private fun getVisiblePaletteItemsRectInRoot(): RectF? {
        val childCount = binding.rvPalette.childCount
        if (childCount == 0) return null

        val result = RectF()
        var hasChild = false
        for (index in 0 until childCount) {
            val child = binding.rvPalette.getChildAt(index) ?: continue
            if (child.visibility != View.VISIBLE) continue

            val childRect = getViewRectInRoot(child)
            if (!hasChild) {
                result.set(childRect)
                hasChild = true
            } else {
                result.union(childRect)
            }
        }

        if (!hasChild) return null

        result.inset(
            -dp(GUIDE_PALETTE_HORIZONTAL_PADDING_DP),
            -dp(GUIDE_PALETTE_VERTICAL_PADDING_DP)
        )
        val paletteRect = getViewRectInRoot(binding.paletteContainer)
        result.left = result.left.coerceAtLeast(paletteRect.left)
        result.top = result.top.coerceAtLeast(paletteRect.top)
        result.right = result.right.coerceAtMost(paletteRect.right)
        result.bottom = result.bottom.coerceAtMost(paletteRect.bottom)
        return result
    }

    private fun getDisplayedCanvasImageRectInRoot(): RectF? {
        val imageRectInCanvas = binding.paintCanvas.getDisplayedBitmapRectInView() ?: return null
        val canvasRectInRoot = getViewRectInRoot(binding.paintCanvas)
        return RectF(
            canvasRectInRoot.left + imageRectInCanvas.left,
            canvasRectInRoot.top + imageRectInCanvas.top,
            canvasRectInRoot.left + imageRectInCanvas.right,
            canvasRectInRoot.top + imageRectInCanvas.bottom
        )
    }

    private fun getViewRectInRoot(view: View): RectF {
        guideRectBuffer.set(0, 0, view.width, view.height)
        binding.root.offsetDescendantRectToMyCoords(view, guideRectBuffer)
        return RectF(guideRectBuffer)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun setMainContentVisible(isVisible: Boolean) {
        val visibility = if (isVisible && !isPreparationVisible) View.VISIBLE else View.GONE
//        binding.topBar.visibility = visibility
        binding.paintCanvas.visibility = visibility
        binding.paletteContainer.visibility = visibility
        binding.progressBarLevel.visibility = visibility
//        binding.btnPreviewFull.visibility = visibility
//        binding.btnFillAll.visibility = visibility
        binding.progressBar.visibility =
            if (isVisible && isLoadingVisible && !isGuideVisible && !isPreparationVisible) View.VISIBLE else View.GONE
        updateFullPreviewVisibility()
    }

    private fun showPreparationOverlay() {
        isPreparationVisible = true
        preparationStartedAtMillis = System.currentTimeMillis()
        AppThemeManager.applyFullBackground(binding.paintPreparationOverlay)
        binding.paintPreparationOverlay.visibility = View.VISIBLE
        binding.paintPreparationOverlay.alpha = 1f
        binding.shimmerPreparationThumbnail.startShimmer()
        binding.ivPreparationThumbnail.setImageDrawable(null)
        binding.tvPreparationMessage.setText(R.string.preparing_picture)
        preparationThumbnail?.let { thumbnail ->
            Glide.with(this)
                .load(thumbnail)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.shimmerPreparationThumbnail.stopShimmer()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.shimmerPreparationThumbnail.stopShimmer()
                        return false
                    }
                })
                .into(binding.ivPreparationThumbnail)
        }
        setPaintChromeVisible(false)
        setMainContentVisible(false)
    }

    private fun showPreparationCanvasStage() {
        if (isPreparationVisible) {
            binding.tvPreparationMessage.setText(R.string.preparing_canvas)
        }
    }

    private fun showPreparationReadyStage() {
        if (isPreparationVisible) {
            binding.tvPreparationMessage.setText(R.string.picture_ready_to_color)
        }
    }

    private suspend fun hidePreparationOverlayWhenReady() {
        if (!isPreparationVisible) return

        val elapsed = System.currentTimeMillis() - preparationStartedAtMillis
        val remaining = PREPARATION_MIN_DURATION_MS - elapsed
        if (remaining > 0L) {
            delay(remaining)
        }

        if (!isPreparationVisible) return
        isPreparationVisible = false
        setPaintChromeVisible(true)
        setMainContentVisible(true)
        binding.paintPreparationOverlay.animate()
            .alpha(0f)
            .setDuration(PREPARATION_FADE_OUT_MS)
            .withEndAction {
                if (!isPreparationVisible) {
                    binding.shimmerPreparationThumbnail.stopShimmer()
                    binding.paintPreparationOverlay.visibility = View.GONE
                }
            }
            .start()
    }

    /**
     * Progress is persisted by the ViewModel when a fill completes.  Save the visual thumbnail
     * shortly afterwards, once a burst of taps settles, so Library/My Work never stays stale
     * while repeated fills still remain responsive.
     */
    private fun scheduleThumbnailSave() {
        thumbnailSaveJob?.cancel()
        thumbnailSaveJob = lifecycleScope.launch {
            delay(THUMBNAIL_SAVE_DEBOUNCE_MS)
            val thumbnail = binding.paintCanvas.generateThumbnail(WORK_PREVIEW_THUMBNAIL_SIZE)
            withContext(Dispatchers.IO) {
                viewModel.saveThumbnail(thumbnail)
            }
        }
    }

    private fun setPaintChromeVisible(isVisible: Boolean) {
        val visibility = if (isVisible && !isPreparationVisible) View.VISIBLE else View.GONE
        binding.btnBack.visibility = visibility
        binding.btnHint.visibility = visibility
        binding.tvHintCount.visibility = visibility
    }

    private fun syncPaintSettings() {
        viewModel.setAutoSwitchColorEnabled(SharedPrefManager.isAutoSwitchColor)
        binding.paintCanvas.setFillInAnimationEnabled(SharedPrefManager.isFillInAnimation)
        binding.paintCanvas.setHighlightTheme(
            HighlightThemes.fromId(this, SharedPrefManager.highlightThemeId)
        )
    }

    private fun scrollPaletteToSelectedColor(selectedIndex: Int) {
        if (selectedIndex == -1 || selectedIndex == lastRenderedSelectedPaletteIndex) return
        lastRenderedSelectedPaletteIndex = selectedIndex
        val displayPosition = adapter.displayPositionForOriginalIndex(selectedIndex)
        if (displayPosition != -1) {
            binding.rvPalette.smoothScrollToPosition(displayPosition)
        }
    }

    private fun finishGuide() {
        SharedPrefManager.isShowGuide = false
        isGuideVisible = false
        binding.llGuide.visibility = View.GONE
        binding.guideOverlay.clearSpotlight()
        setMainContentVisible(true)
        loadAndShowNativeCollapsibleDraw {}
    }

    private fun loadAndShowNativeCollapsibleDraw(onShowOrFailed: () -> Unit) {
        if (!AdmobLib.getShowAds()) {
            binding.frNativeSmall.gone()
            binding.frNativeExpand.gone()
            binding.whiteLine.gone()
            onShowOrFailed()
            return
        }

        when (RemoteConfig.remoteNativeCollapsibleDraw) {
            1L -> {
                binding.frNativeSmall.visible()
                binding.frNativeExpand.gone()
                AdmobLib.loadAndShowNative(
                    activity = this,
                    admobNativeModel = AdsManager.NATIVE_COLLAPSIBLE_DRAW,
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
                    admobNativeModel = AdsManager.NATIVE_COLLAPSIBLE_DRAW,
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

    /**
     * Tô sẵn mọi vùng ngay trên canvas để soi chất lượng màu thật (có zoom/pan), khác với nút
     * "Full" chỉ hiện ảnh preview dựng sẵn trong một ImageView.
     *
     * Đây là chế độ XEM: tiến trình đã lưu không bị đụng tới, bấm lần nữa là quay về đúng
     * trạng thái đang tô dở.
     */
    private fun toggleFillAllOnCanvas() {
        val state = viewModel.uiState.value
        val renderData = state.renderData
        if (renderData == null || state.isLoading) {
            Toast.makeText(this, R.string.image_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (isFillAllPreviewActive) {
            isFillAllPreviewActive = false
            binding.btnFillAll.setText(R.string.fill)
            lifecycleScope.launch {
                binding.paintCanvas.resetProgress()
                if (state.completedColorMap.isNotEmpty()) {
                    binding.paintCanvas.restoreProgressSuspend(state.completedColorMap)
                }
                binding.paintCanvas.setCompletedRegions(state.completedMaskColors)
                // Chỉ mở lại số sau khi lớp pixel đã về đúng tiến trình thật, nếu không sẽ
                // thấy số hiện đè lên ảnh vẫn còn đang tô đầy trong vài frame.
                binding.paintCanvas.setPreviewFillMode(false)
                binding.paintCanvas.highlightNumber(state.highlightMaskColors)
            }
        } else {
            isFillAllPreviewActive = true
            binding.btnFillAll.setText(R.string.undo)
            binding.paintCanvas.setPreviewFillMode(true)
            binding.paintCanvas.highlightNumber(emptyList())
            lifecycleScope.launch {
                binding.paintCanvas.fillAllSuspend(renderData.allMaskColorsToTargetColors)
            }
        }
    }

    private fun exitFillAllPreviewState() {
        if (!isFillAllPreviewActive) return
        isFillAllPreviewActive = false
        binding.btnFillAll.setText(R.string.fill)
        binding.paintCanvas.setPreviewFillMode(false)
    }

    private fun toggleFullColorPreview() {
        if (fullPreviewBitmap == null) {
            Toast.makeText(this, R.string.preview_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        isFullColorPreviewVisible = !isFullColorPreviewVisible
        updateFullPreviewVisibility()
    }

    private fun hideFullColorPreview() {
        if (!isFullColorPreviewVisible) return
        isFullColorPreviewVisible = false
        updateFullPreviewVisibility()
    }

    private fun updateFullPreviewVisibility() {
        val shouldShow = isFullColorPreviewVisible &&
                !isGuideVisible &&
                !isLoadingVisible &&
                !isPreparationVisible &&
                fullPreviewBitmap != null

        binding.fullPreviewOverlay.visibility = if (shouldShow) View.VISIBLE else View.GONE
        binding.paintCanvas.isEnabled = !shouldShow
        binding.paintCanvas.isClickable = !shouldShow
        binding.ivFullPreview.setImageBitmap(if (shouldShow) fullPreviewBitmap else null)
        updateZoomNormalButtonVisibility()
    }

    private fun resetCanvasViewport() {
        if (isCanvasAtInitialViewport || isResettingViewport) return

        isResettingViewport = true
        updateZoomNormalButtonVisibility()
        lifecycleScope.launch {
            try {
                binding.paintCanvas.animateToFitScreen()
            } finally {
                isResettingViewport = false
                updateZoomNormalButtonVisibility()
            }
        }
    }

    private fun updateZoomNormalButtonVisibility() {
        val shouldShow = !isCanvasAtInitialViewport &&
            !isPreparationVisible &&
            !isGuideVisible &&
            !isLoadingVisible &&
            !isFullColorPreviewVisible &&
            !isNavigatingToCompleted &&
            binding.paintCanvas.visibility == View.VISIBLE

        binding.btnZoomNormal.visibility = if (shouldShow) View.VISIBLE else View.GONE
        binding.btnZoomNormal.isEnabled = shouldShow && !isResettingViewport
    }

    private fun resetFullPreviewCache(renderKey: String) {
        if (fullPreviewRenderKey == renderKey) return
        fullPreviewBitmap?.recycle()
        fullPreviewBitmap = null
        fullPreviewRenderKey = renderKey
        isFullColorPreviewVisible = false
        binding.ivFullPreview.setImageBitmap(null)
        binding.fullPreviewOverlay.visibility = View.GONE
    }

    private suspend fun ensureFullPreviewBitmap(renderData: com.pixlory.color.by.number.ui.paint.PaintRenderData) {
        if (fullPreviewBitmap != null && fullPreviewRenderKey == "${renderData.category}/${renderData.levelId}") {
            return
        }

        val previewBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildFullPreviewBitmap(
                displayLineBitmap = renderData.displayLineBitmap,
                maskBitmap = renderData.maskBitmap,
                detailBitmap = renderData.detailBitmap,
                allMaskColorsToTargetColors = renderData.allMaskColorsToTargetColors
            )
        }

        fullPreviewBitmap?.recycle()
        fullPreviewBitmap = previewBitmap
        fullPreviewRenderKey = "${renderData.category}/${renderData.levelId}"
    }

    private fun buildFullPreviewBitmap(
        displayLineBitmap: Bitmap,
        maskBitmap: Bitmap,
        detailBitmap: Bitmap?,
        allMaskColorsToTargetColors: Map<Int, Int>
    ): Bitmap {
        val width = maskBitmap.width
        val height = maskBitmap.height
        val maskPixels = IntArray(width * height)
        val coloredPixels = IntArray(width * height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        for (index in maskPixels.indices) {
            val maskColor = maskPixels[index]
            coloredPixels[index] = allMaskColorsToTargetColors[maskColor] ?: 0
        }

        val coloredBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        coloredBitmap.setPixels(coloredPixels, 0, width, 0, 0, width, height)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(coloredBitmap, 0f, 0f, null)
        if (detailBitmap != null && detailBitmap.width == width && detailBitmap.height == height) {
            canvas.drawBitmap(detailBitmap, 0f, 0f, null)
        }
        canvas.drawBitmap(
            displayLineBitmap,
            null,
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            previewMultiplyPaint
        )
        coloredBitmap.recycle()
        return result
    }

    private fun handleEvent(event: PaintUiEvent) {
        when (event) {
            PaintUiEvent.FinishScreen -> finish()
            is PaintUiEvent.FocusOnMaskColor -> {
                if (!SharedPrefManager.consumeHint()) {
                    renderHintBalance()
                    return
                }
                renderHintBalance()
                achievementRepository.track(AchievementEvent.HintUsed)
                soundManagerOrNull()?.play(SoundEffect.HINT)
                binding.paintCanvas.focusOnRegionByMaskColor(event.maskColor)
            }
            is PaintUiEvent.LevelCompleted -> navigateToTimelapsePreview(event)
            PaintUiEvent.RequestResetConfirmation -> showResetConfirmationDialog()
            is PaintUiEvent.ShowToast -> Toast.makeText(this, getString(event.messageRes), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun navigateToTimelapsePreview(event: PaintUiEvent.LevelCompleted) {
        achievementRepository.track(
            AchievementEvent.ArtworkCompleted(event.category, event.levelId)
        )
        val collectedPaintDrops = appContainer.paintDropRepository.trackArtworkCompleted(
            event.category,
            event.levelId
        )

        if (isNavigatingToCompleted) return
        isNavigatingToCompleted = true
        pauseDrawInterCountdown()
        updateZoomNormalButtonVisibility()

        lifecycleScope.launch {
            kotlinx.coroutines.delay(COMPLETED_NAVIGATION_DELAY_MS)

            binding.paintCanvas.animateToFitScreen()
            val completedThumbnail = binding.paintCanvas.generateThumbnail(WORK_PREVIEW_THUMBNAIL_SIZE)
            withContext(Dispatchers.IO) {
                viewModel.saveThumbnail(completedThumbnail)
            }
            playCompletionAnimation()

            startActivity(
                Intent(this@PaintActivity, TimelapsePreviewActivity::class.java).apply {
                    putExtra(TimelapsePreviewActivity.EXTRA_CATEGORY, event.category)
                    putExtra(TimelapsePreviewActivity.EXTRA_LEVEL_ID, event.levelId)
                    putExtra(TimelapsePreviewActivity.EXTRA_COLLECTED_COUNT, collectedPaintDrops)
                    putExtra(TimelapsePreviewActivity.EXTRA_OPEN_PICTURE_COMPLETED_ON_SKIP, true)
                    putExtra(TimelapsePreviewActivity.EXTRA_FROM_HOME, intent.getBooleanExtra(EXTRA_FROM_HOME, false))
                }
            )
            finish()
        }
    }

    private suspend fun playCompletionAnimation() {
        suspendCancellableCoroutine { continuation ->
            val animationView = binding.lavCompletionBlast
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animationView.removeAnimatorListener(this)
                    binding.completionAnimationOverlay.visibility = View.GONE
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    animationView.removeAnimatorListener(this)
                    binding.completionAnimationOverlay.visibility = View.GONE
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }

            continuation.invokeOnCancellation {
                animationView.removeAnimatorListener(listener)
                animationView.cancelAnimation()
                binding.completionAnimationOverlay.visibility = View.GONE
            }

            binding.completionAnimationOverlay.visibility = View.VISIBLE
            binding.completionAnimationOverlay.bringToFront()
            soundManagerOrNull()?.play(SoundEffect.COMPLETION)
            animationView.apply {
                removeAllAnimatorListeners()
                addAnimatorListener(listener)
                progress = 0f
                playAnimation()
            }
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset)
            .setMessage(R.string.reset_progress_confirmation)
            .setPositiveButton(R.string.yes) { _, _ ->
                viewModel.onResetConfirmed()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showWatchAdsDialog() {
        showDialogOnce(WatchAdsDialog.TAG) {
            WatchAdsDialog().apply {
                onWatchAd = ::showRewardedHintAd
            }
        }
    }

    private fun onHintButtonClicked() {
        if (SharedPrefManager.hintBalance > 0) {
            viewModel.onHintRequested()
        } else {
            showWatchAdsDialog()
        }
    }

    private fun renderHintBalance() {
        val hintBalance = SharedPrefManager.hintBalance
        binding.tvHintCount.text = if (hintBalance > 0) hintBalance.toString() else "+"
    }

    private fun showRewardedHintAd() {
        pauseDrawInterCountdown()
        val shouldResetDrawInter = RemoteConfig.remoteRewardUnlock == 1L &&
            drawInterRemainingMs?.let { it <= DRAW_INTER_WARNING_MS } == true
        isRewardedHintAdShowing = true
        loadAndShowRewardAds(
            navAction = {
                SharedPrefManager.addHints(SharedPrefManager.REWARDED_AD_HINT_AMOUNT)
                renderHintBalance()
                isRewardedHintAdShowing = false
                if (shouldResetDrawInter) resetDrawInterCountdown() else startDrawInterCountdown()
            },
            onFail = {
                isRewardedHintAdShowing = false
                startDrawInterCountdown()
            }
        )
    }

    private fun isDrawInterEnabled(): Boolean =
        RemoteConfig.remoteInterDraw > 0L &&
            RemoteConfig.remoteTimeShowInterDraw > 0L &&
            AdmobLib.getShowAds() &&
            !isNavigatingToCompleted &&
            !isFinishing &&
            !isDestroyed

    private fun canAdvanceDrawInterCountdown(): Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !isGuideVisible &&
            !isPreparationVisible &&
            !isLoadingVisible &&
            !isFullColorPreviewVisible &&
            !isFillAllPreviewActive &&
            binding.paintCanvas.visibility == View.VISIBLE &&
            supportFragmentManager.fragments.none { it is DialogFragment && it.isVisible }

    private fun startDrawInterCountdown() {
        if (!isDrawInterEnabled() || isDrawInterstitialShowing || isRewardedHintAdShowing) {
            hideDrawInterWarning()
            return
        }
        if (drawInterCountdownJob?.isActive == true) return

        if (drawInterRemainingMs == null) {
            drawInterRemainingMs = RemoteConfig.remoteTimeShowInterDraw
        }
        drawInterLastTickMs = SystemClock.elapsedRealtime()
        drawInterCountdownJob = lifecycleScope.launch {
            while (isActive && isDrawInterEnabled()) {
                val now = SystemClock.elapsedRealtime()
                if (canAdvanceDrawInterCountdown()) {
                    val elapsed = (now - drawInterLastTickMs).coerceAtLeast(0L)
                    val remaining = (drawInterRemainingMs ?: 0L) - elapsed
                    drawInterRemainingMs = remaining.coerceAtLeast(0L)
                    drawInterLastTickMs = now
                    if (drawInterRemainingMs == 0L) {
                        showDrawInterstitial()
                        return@launch
                    }
                    updateDrawInterWarning(drawInterRemainingMs ?: 0L)
                } else {
                    drawInterLastTickMs = now
                    hideDrawInterWarning()
                }
                delay(
                    if ((drawInterRemainingMs ?: 0L) > DRAW_INTER_WARNING_MS + DRAW_INTER_NORMAL_TICK_MS) {
                        DRAW_INTER_NORMAL_TICK_MS
                    } else {
                        DRAW_INTER_WARNING_TICK_MS
                    }
                )
            }
            hideDrawInterWarning()
        }
    }

    private fun pauseDrawInterCountdown() {
        if (drawInterCountdownJob?.isActive == true && canAdvanceDrawInterCountdown()) {
            val elapsed = (SystemClock.elapsedRealtime() - drawInterLastTickMs).coerceAtLeast(0L)
            drawInterRemainingMs = ((drawInterRemainingMs ?: 0L) - elapsed).coerceAtLeast(0L)
        }
        drawInterCountdownJob?.cancel()
        drawInterCountdownJob = null
        drawInterLastTickMs = 0L
        hideDrawInterWarning()
    }

    private fun resetDrawInterCountdown() {
        pauseDrawInterCountdown()
        drawInterRemainingMs = RemoteConfig.remoteTimeShowInterDraw
        startDrawInterCountdown()
    }

    private fun showDrawInterstitial() {
        if (isDrawInterstitialShowing || !isDrawInterEnabled()) return
        isDrawInterstitialShowing = true
        pauseDrawInterCountdown()
        loadAndShowInterDraw(interAdBlockView()) {
            isDrawInterstitialShowing = false
            resetDrawInterCountdown()
        }
    }

    private fun updateDrawInterWarning(remainingMs: Long) {
        if (remainingMs > DRAW_INTER_WARNING_MS) {
            hideDrawInterWarning()
            return
        }
        val seconds = ((remainingMs + 999L) / 1_000L).toInt()
        if (drawInterWarningSeconds == seconds) return
        drawInterWarningSeconds = seconds
        val message = getString(R.string.inter_draw_countdown, seconds)
        val snackbar = drawInterSnackbar
        if (snackbar != null) {
            snackbar.setText(message)
            return
        }

        drawInterSnackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_INDEFINITE).apply {
            animationMode = Snackbar.ANIMATION_MODE_FADE
            view.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            show()
        }
    }

    private fun hideDrawInterWarning() {
        drawInterSnackbar?.dismiss()
        drawInterSnackbar = null
        drawInterWarningSeconds = null
    }

    override fun onPause() {
        pauseDrawInterCountdown()
        super.onPause()
        if (isFillAllPreviewActive) return
        if (isNavigatingToCompleted) return
        thumbnailSaveJob?.cancel()
        thumbnailSaveJob = null
        viewModel.saveThumbnail(binding.paintCanvas.generateThumbnail(WORK_PREVIEW_THUMBNAIL_SIZE))
    }

    override fun onResume() {
        super.onResume()
        syncPaintSettings()
        renderHintBalance()
        startDrawInterCountdown()
    }

    override fun onDestroy() {
        pauseDrawInterCountdown()
        thumbnailSaveJob?.cancel()
        thumbnailSaveJob = null
        // hintRewardedAdTimeoutJob?.cancel()
        // hintRewardedAdTimeoutJob = null
        super.onDestroy()
        fullPreviewBitmap?.recycle()
        fullPreviewBitmap = null
    }
}
