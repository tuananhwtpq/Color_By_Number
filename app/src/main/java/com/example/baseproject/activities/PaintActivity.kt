package com.example.baseproject.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.data.repository.AchievementEvent
import com.example.baseproject.adapters.PaletteAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityPaintBinding
import com.example.baseproject.ui.paint.PaintUiEvent
import com.example.baseproject.ui.paint.PaintUiState
import com.example.baseproject.ui.paint.PaintViewModel
import com.example.baseproject.utils.Constants
import com.example.baseproject.utils.SharedPrefManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PaintActivity : BaseActivity<ActivityPaintBinding>(ActivityPaintBinding::inflate) {

    companion object {
        private const val GUIDE_STEP_01 = 0
        private const val GUIDE_STEP_02 = 1
        private const val GUIDE_STEP_03 = 2
        private const val GUIDE_PALETTE_HORIZONTAL_PADDING_DP = 8f
        private const val GUIDE_PALETTE_VERTICAL_PADDING_DP = 14f
        private const val WORK_PREVIEW_THUMBNAIL_SIZE = 900
        private const val COMPLETED_NAVIGATION_DELAY_MS = 400L

        private const val DBG_TAG = "PBN_DBG_a91f"
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
    private var guideStep: Int = GUIDE_STEP_01
    private var isGuideVisible: Boolean = false
    private var shouldStartGuideWhenContentReady: Boolean = false
    private var isLoadingVisible: Boolean = false
    private var isFullColorPreviewVisible: Boolean = false
    private var isFillAllPreviewActive: Boolean = false
    private var isNavigatingToCompleted: Boolean = false
    private var fullPreviewBitmap: Bitmap? = null
    private var fullPreviewRenderKey: String? = null
    private val guideRectBuffer = Rect()

    private val previewMultiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    override fun initData() {
        category = intent.getStringExtra("CATEGORY")
        levelId = intent.getStringExtra("LEVEL_ID")

        if (category == null || levelId == null) {
            finish()
            return
        }
    }

    override fun initView() {
        initViews()
        setupGuideIfNeeded()
        collectUi()
    }

    override fun initActionView() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnHint.setOnClickListener { viewModel.onHintRequested() }
        binding.btnPreviewFull.setOnClickListener { toggleFullColorPreview() }
        binding.btnFillAll.setOnClickListener { toggleFillAllOnCanvas() }
        binding.btnCloseFullPreview.setOnClickListener { hideFullColorPreview() }
        binding.fullPreviewOverlay.setOnClickListener { hideFullColorPreview() }
        binding.ivFullPreview.setOnClickListener { }
//        binding.btnReset.setOnClickListener { viewModel.requestResetConfirmation() }

        viewModel.loadLevel(
            category = category ?: return,
            levelId = levelId ?: return
        )
    }

    private fun initViews() {
        binding.rvPalette.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.paintCanvas.onRegionFilledListener = { maskInt ->
            Log.d(
                DBG_TAG,
                "ON_REGION_FILLED_CB mask=$maskInt(${Integer.toHexString(maskInt)}) t=${System.currentTimeMillis()}"
            )
            viewModel.onRegionFilled(maskInt)
        }
        binding.fullPreviewOverlay.visibility = View.GONE
        binding.llGuide.setOnClickListener {
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
            if (state.isLoading && !isGuideVisible) View.VISIBLE else View.GONE
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
                    ensureFullPreviewBitmap(renderData)
                    binding.paintCanvas.setBitmapsSuspend(
                        renderData.lineBitmap,
                        renderData.displayLineBitmap,
                        renderData.maskBitmap,
                        renderData.detailBitmap,
                        renderData.regions
                    )
                    if (state.completedColorMap.isNotEmpty()) {
                        binding.paintCanvas.restoreProgressSuspend(state.completedColorMap)
                    }
                    binding.paintCanvas.setCompletedRegions(state.completedMaskColors)
                    binding.paintCanvas.highlightNumber(state.highlightMaskColors)
                    binding.paintCanvas.setActiveColors(state.activeColors)
                    showPendingGuideIfNeeded()
                    binding.root.post { updateGuideOverlayForCurrentStep() }
                }
            } else if (!isFillAllPreviewActive) {
                Log.d(
                    DBG_TAG,
                    "VM_STATE completed=${state.completedMaskColors} highlight=${state.highlightMaskColors} " +
                        "t=${System.currentTimeMillis()}"
                )
                // Đang xem bản tô đầy thì bỏ qua: mọi lệnh vẽ ở đây sẽ đè lên lớp preview
                // (ví dụ chọn màu khác trên palette sẽ bật lại highlight giữa ảnh đã tô).
                if (lastCompletedMaskColors.isNotEmpty() && state.completedMaskColors.isEmpty()) {
                    binding.paintCanvas.setCompletedRegions(emptySet())
                    binding.paintCanvas.resetProgress()
                } else {
                    binding.paintCanvas.setCompletedRegions(state.completedMaskColors)
                }
                binding.paintCanvas.highlightNumber(state.highlightMaskColors)
                binding.paintCanvas.setActiveColors(state.activeColors)
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
        val visibility = if (isVisible) View.VISIBLE else View.GONE
//        binding.topBar.visibility = visibility
        binding.paintCanvas.visibility = visibility
        binding.paletteContainer.visibility = visibility
        binding.btnPreviewFull.visibility = visibility
        binding.btnFillAll.visibility = visibility
        binding.progressBar.visibility =
            if (isVisible && isLoadingVisible && !isGuideVisible) View.VISIBLE else View.GONE
        updateFullPreviewVisibility()
    }

    private fun finishGuide() {
        SharedPrefManager.isShowGuide = false
        isGuideVisible = false
        binding.llGuide.visibility = View.GONE
        binding.guideOverlay.clearSpotlight()
        setMainContentVisible(true)
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
            Toast.makeText(this, "Ảnh chưa sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }
        if (isFillAllPreviewActive) {
            isFillAllPreviewActive = false
            binding.btnFillAll.text = "Fill"
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
            binding.btnFillAll.text = "Undo"
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
        binding.btnFillAll.text = "Fill"
        binding.paintCanvas.setPreviewFillMode(false)
    }

    private fun toggleFullColorPreview() {
        if (fullPreviewBitmap == null) {
            Toast.makeText(this, "Preview chưa sẵn sàng", Toast.LENGTH_SHORT).show()
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
                fullPreviewBitmap != null

        binding.fullPreviewOverlay.visibility = if (shouldShow) View.VISIBLE else View.GONE
        binding.paintCanvas.isEnabled = !shouldShow
        binding.paintCanvas.isClickable = !shouldShow
        binding.ivFullPreview.setImageBitmap(if (shouldShow) fullPreviewBitmap else null)
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

    private suspend fun ensureFullPreviewBitmap(renderData: com.example.baseproject.ui.paint.PaintRenderData) {
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
                // Chỉ tính là đã dùng gợi ý khi thật sự có vùng để chỉ (bấm hụt thì
                // PaintViewModel bắn ShowToast chứ không bắn event này).
                achievementRepository.track(AchievementEvent.HintUsed)
                binding.paintCanvas.focusOnRegionByMaskColor(event.maskColor)
            }
            is PaintUiEvent.LevelCompleted -> navigateToPictureCompleted(event)
            PaintUiEvent.RequestResetConfirmation -> showResetConfirmationDialog()
            is PaintUiEvent.ShowToast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun navigateToPictureCompleted(event: PaintUiEvent.LevelCompleted) {
        // TODO: khi có màn Daily thì truyền isDaily = true cho tranh thuộc Daily.
        achievementRepository.track(
            AchievementEvent.ArtworkCompleted(event.category, event.levelId)
        )
        appContainer.paintDropRepository.trackArtworkCompleted(event.category, event.levelId)

        if (isNavigatingToCompleted) return
        isNavigatingToCompleted = true

        lifecycleScope.launch {
            // Chờ một nhịp để vùng cuối cùng kịp hiện đầy đủ trên canvas trước khi chuyển màn.
            kotlinx.coroutines.delay(COMPLETED_NAVIGATION_DELAY_MS)

            // Ảnh hoàn thiện được ghi ra file rồi truyền category/levelId sang, KHÔNG nhét
            // Bitmap thẳng vào Intent: bitmap 900x900 (~3MB) vượt xa giới hạn ~1MB của Binder
            // và sẽ ném TransactionTooLargeException.
            viewModel.saveThumbnail(binding.paintCanvas.generateThumbnail(WORK_PREVIEW_THUMBNAIL_SIZE))

            startActivity(
                Intent(this@PaintActivity, PictureCompletedActivity::class.java).apply {
                    putExtra(PictureCompletedActivity.EXTRA_CATEGORY, event.category)
                    putExtra(PictureCompletedActivity.EXTRA_LEVEL_ID, event.levelId)
                    putExtra(PictureCompletedActivity.EXTRA_COLLECTED_COUNT, event.collectedCount)
                }
            )
            finish()
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset")
            .setMessage("Bạn có chắc chắn muốn xóa toàn bộ tiến trình của bức tranh này và tô lại từ đầu?")
            .setPositiveButton("Có") { _, _ ->
                viewModel.onResetConfirmed()
            }
            .setNegativeButton("Không", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Đang xem bản tô đầy thì canvas không phản ánh tiến trình thật — lưu lúc này sẽ ghi
        // đè thumbnail bằng ảnh đã hoàn thiện dù người dùng mới tô được vài mảng.
        if (isFillAllPreviewActive) return
        // Luồng hoàn thành đã tự lưu ngay trước khi chuyển màn, khỏi dựng lại bitmap 900x900.
        if (isNavigatingToCompleted) return
        viewModel.saveThumbnail(binding.paintCanvas.generateThumbnail(WORK_PREVIEW_THUMBNAIL_SIZE))
    }

    override fun onDestroy() {
        super.onDestroy()
        fullPreviewBitmap?.recycle()
        fullPreviewBitmap = null
    }
}
