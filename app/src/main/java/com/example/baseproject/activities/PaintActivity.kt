package com.example.baseproject.activities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.adapters.PaletteAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityPaintBinding
import com.example.baseproject.ui.paint.PaintUiEvent
import com.example.baseproject.ui.paint.PaintUiState
import com.example.baseproject.ui.paint.PaintViewModel
import com.example.baseproject.utils.SharedPrefManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PaintActivity : BaseActivity<ActivityPaintBinding>(ActivityPaintBinding::inflate) {

    companion object {
        private const val GUIDE_STEP_01 = 0
        private const val GUIDE_STEP_02 = 1
        private const val GUIDE_STEP_03 = 2
    }

    private val viewModel: PaintViewModel by viewModels {
        val appContainer = (application as MyApplication).appContainer
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
    private var lastSelectedIndex: Int = -1
    private var lastCompletedMaskColors: Set<Int> = emptySet()
    private var category: String? = null
    private var levelId: String? = null
    private var guideStep: Int = GUIDE_STEP_01
    private var isGuideVisible: Boolean = false
    private var isLoadingVisible: Boolean = false
    private var isFullColorPreviewVisible: Boolean = false
    private var fullPreviewBitmap: Bitmap? = null
    private var fullPreviewRenderKey: String? = null

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

        if (state.palette.isNotEmpty() && (!this::adapter.isInitialized || adapter.itemCount != state.palette.size)) {
            adapter = PaletteAdapter(state.palette) { position, _ ->
                viewModel.onPaletteSelected(position)
            }
            binding.rvPalette.adapter = adapter
        }

        if (this::adapter.isInitialized) {
            adapter.setPaletteState(
                selectedIndex = state.selectedPaletteIndex,
                completedIndexes = state.completedIndexes,
                paletteProgress = state.paletteProgress
            )

            if (lastSelectedIndex != state.selectedPaletteIndex && state.selectedPaletteIndex in 0 until adapter.itemCount) {
                binding.rvPalette.smoothScrollToPosition(state.selectedPaletteIndex)
            }
        }

        val renderData = state.renderData
        if (renderData != null) {
            val newRenderKey = "${renderData.category}/${renderData.levelId}"
            if (currentRenderKey != newRenderKey) {
                currentRenderKey = newRenderKey
                resetFullPreviewCache(newRenderKey)
                lifecycleScope.launch {
                    ensureFullPreviewBitmap(renderData)
                    binding.paintCanvas.setBitmapsSuspend(
                        renderData.lineBitmap,
                        renderData.maskBitmap,
                        renderData.regions
                    )
                    if (state.completedColorMap.isNotEmpty()) {
                        binding.paintCanvas.restoreProgressSuspend(state.completedColorMap)
                    }
                    binding.paintCanvas.setCompletedRegions(state.completedMaskColors)
                    binding.paintCanvas.highlightNumber(state.highlightMaskColors)
                    binding.paintCanvas.setActiveColors(state.activeColors)
                }
            } else {
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
        lastSelectedIndex = state.selectedPaletteIndex
        lastCompletedMaskColors = state.completedMaskColors
    }

    private fun setupGuideIfNeeded() {
        if (SharedPrefManager.isShowGuide) {
            isGuideVisible = true
            setMainContentVisible(false)
            showGuideStep(GUIDE_STEP_01)
        } else {
            isGuideVisible = false
            binding.llGuide.visibility = View.GONE
            setMainContentVisible(true)
        }
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

        val backgroundRes = when (step) {
            GUIDE_STEP_02 -> com.example.baseproject.R.drawable.bg_guide_02
            GUIDE_STEP_03 -> com.example.baseproject.R.drawable.bg_guide_03
            else -> com.example.baseproject.R.drawable.bg_guide_01
        }
        binding.llGuide.setBackgroundResource(backgroundRes)
    }

    private fun setMainContentVisible(isVisible: Boolean) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE
//        binding.topBar.visibility = visibility
        binding.paintCanvas.visibility = visibility
        binding.paletteContainer.visibility = visibility
        binding.btnPreviewFull.visibility = visibility
        binding.progressBar.visibility =
            if (isVisible && isLoadingVisible && !isGuideVisible) View.VISIBLE else View.GONE
        updateFullPreviewVisibility()
    }

    private fun finishGuide() {
        SharedPrefManager.isShowGuide = false
        isGuideVisible = false
        binding.llGuide.visibility = View.GONE
        setMainContentVisible(true)
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
                lineBitmap = renderData.lineBitmap,
                maskBitmap = renderData.maskBitmap,
                allMaskColorsToTargetColors = renderData.allMaskColorsToTargetColors
            )
        }

        fullPreviewBitmap?.recycle()
        fullPreviewBitmap = previewBitmap
        fullPreviewRenderKey = "${renderData.category}/${renderData.levelId}"
    }

    private fun buildFullPreviewBitmap(
        lineBitmap: Bitmap,
        maskBitmap: Bitmap,
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
        canvas.drawBitmap(lineBitmap, 0f, 0f, previewMultiplyPaint)
        coloredBitmap.recycle()
        return result
    }

    private fun handleEvent(event: PaintUiEvent) {
        when (event) {
            PaintUiEvent.FinishScreen -> finish()
            is PaintUiEvent.FocusOnMaskColor -> binding.paintCanvas.focusOnRegionByMaskColor(event.maskColor)
            PaintUiEvent.RequestResetConfirmation -> showResetConfirmationDialog()
            is PaintUiEvent.ShowToast -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT)
                .show()
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
        viewModel.saveThumbnail(binding.paintCanvas.generateThumbnail(400))
    }

    override fun onDestroy() {
        super.onDestroy()
        fullPreviewBitmap?.recycle()
        fullPreviewBitmap = null
    }
}
