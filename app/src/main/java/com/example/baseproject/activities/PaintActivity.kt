package com.example.baseproject.activities

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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PaintActivity : BaseActivity<ActivityPaintBinding>(ActivityPaintBinding::inflate) {

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
        collectUi()
    }

    override fun initActionView() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnHint.setOnClickListener { viewModel.onHintRequested() }
        binding.btnReset.setOnClickListener { viewModel.requestResetConfirmation() }

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
        binding.progressBar.visibility =
            if (state.isLoading) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvTitle.text = state.title

        if (state.palette.isNotEmpty() && (!this::adapter.isInitialized || adapter.itemCount != state.palette.size)) {
            adapter = PaletteAdapter(state.palette) { position, _ ->
                viewModel.onPaletteSelected(position)
            }
            binding.rvPalette.adapter = adapter
        }

        if (this::adapter.isInitialized) {
            adapter.setCompletedIndexes(state.completedIndexes)
            if (state.selectedPaletteIndex in 0 until adapter.itemCount && adapter.selectedIndex != state.selectedPaletteIndex) {
                adapter.setSelection(state.selectedPaletteIndex)
            }

            if (lastSelectedIndex != state.selectedPaletteIndex && state.selectedPaletteIndex in 0 until adapter.itemCount) {
                binding.rvPalette.smoothScrollToPosition(state.selectedPaletteIndex)
            }
        }

        val renderData = state.renderData
        if (renderData != null) {
            val newRenderKey = "${renderData.category}/${renderData.levelId}"
            if (currentRenderKey != newRenderKey) {
                currentRenderKey = newRenderKey
                lifecycleScope.launch {
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

        lastSelectedIndex = state.selectedPaletteIndex
        lastCompletedMaskColors = state.completedMaskColors
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
}
