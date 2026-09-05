package com.example.baseproject.ui.paint

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.baseproject.R
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.PaletteItem
import com.example.baseproject.data.RegionData
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.data.repository.ThumbnailRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PaintViewModel(
    private val assetLevelRepository: AssetLevelRepository,
    private val paintingProgressRepository: PaintingProgressRepository,
    private val thumbnailRepository: ThumbnailRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaintUiState())
    val uiState: StateFlow<PaintUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PaintUiEvent>()
    val events: SharedFlow<PaintUiEvent> = _events.asSharedFlow()

    private var levelConfig: LevelConfig? = null
    private var allRegions: List<PaletteItem> = emptyList()
    private var uniqueColors: List<PaletteItem> = emptyList()
    private var regionMetadata: List<RegionData> = emptyList()
    private var category: String? = null
    private var levelId: String? = null
    private var autoSwitchColorEnabled: Boolean = false

    // Tag debug tạm thời — xoá cùng các Log.d bên dưới sau khi xác định xong nguyên nhân.
    private val dbgTag = "PBN_DBG_a91f"

    fun loadLevel(category: String, levelId: String) {
        if (this.category == category && this.levelId == levelId && _uiState.value.renderData != null) {
            return
        }
        this.category = category
        this.levelId = levelId

        viewModelScope.launch {
            _uiState.update { PaintUiState(isLoading = true) }
            runCatching {
                assetLevelRepository.loadLevelBundle(category, levelId)
            }.onSuccess { bundle ->
                levelConfig = bundle.config
                allRegions = bundle.config.toRegionPaletteItems()
                uniqueColors = allRegions.groupBy { it.number }
                    .map { it.value.first() }
                    .sortedBy { it.number }
                regionMetadata = bundle.regions

                val completedMaskColors = paintingProgressRepository.loadProgress(category, levelId)
                val paletteProgress = calculatePaletteProgress(completedMaskColors)
                val overallProgress = calculateOverallProgress(completedMaskColors)
                val completedIndexes = calculateCompletedIndexes(completedMaskColors)
                val selectedIndex =
                    uniqueColors.indices.firstOrNull { it !in completedIndexes } ?: -1

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        title = bundle.config.name,
                        palette = uniqueColors,
                        paletteProgress = paletteProgress,
                        overallProgress = overallProgress,
                        selectedPaletteIndex = selectedIndex,
                        completedMaskColors = completedMaskColors,
                        completedColorMap = completedColorMap(completedMaskColors),
                        completedIndexes = completedIndexes,
                        highlightMaskColors = highlightForIndex(selectedIndex, completedMaskColors),
                        activeColors = activeColorsForIndex(selectedIndex),
                        renderData = PaintRenderData(
                            category = category,
                            levelId = levelId,
                            lineBitmap = bundle.lineBitmap,
                            displayLineBitmap = bundle.displayLineBitmap,
                            displayLineSvg = bundle.displayLineSvg,
                            maskBitmap = bundle.maskBitmap,
                            detailBitmap = bundle.detailBitmap,
                            fillCoverageBitmap = bundle.fillCoverageBitmap,
                            regions = bundle.regions,
                            allMaskColorsToTargetColors = allRegions.associate {
                                it.getMaskColorInt() to it.getTargetColorInt()
                            }
                        ),
                        errorMessage = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Failed to load level"
                    )
                }
                _events.emit(PaintUiEvent.ShowToast(R.string.failed_to_load_level))
                _events.emit(PaintUiEvent.FinishScreen)
            }
        }
    }

    fun onPaletteSelected(position: Int) {
        if (position !in uniqueColors.indices || position in _uiState.value.completedIndexes) return
        val completedMaskColors = _uiState.value.completedMaskColors
        _uiState.update {
            it.copy(
                selectedPaletteIndex = position,
                highlightMaskColors = highlightForIndex(position, completedMaskColors),
                activeColors = activeColorsForIndex(position)
            )
        }
    }

    fun setAutoSwitchColorEnabled(enabled: Boolean) {
        autoSwitchColorEnabled = enabled
    }

    fun onHintRequested() {
        if (uniqueColors.isEmpty()) return
        val selectedColor = uniqueColors.getOrNull(_uiState.value.selectedPaletteIndex) ?: return
        val validRegions = allRegions.filter { it.number == selectedColor.number }

        val preferredMaskColor = regionMetadata
            .filter {
                it.number == selectedColor.number &&
                        !_uiState.value.completedMaskColors.contains(it.maskColorInt)
            }
            .sortedWith(
                compareByDescending<RegionData> { !it.hideNumber }
                    .thenByDescending { it.area }
            )
            .firstOrNull()
            ?.maskColorInt
            ?: validRegions.find { !_uiState.value.completedMaskColors.contains(it.getMaskColorInt()) }
                ?.getMaskColorInt()

        viewModelScope.launch {
            if (preferredMaskColor != null) {
                _events.emit(PaintUiEvent.FocusOnMaskColor(preferredMaskColor))
            } else {
                _events.emit(PaintUiEvent.ShowToast(R.string.you_have_finished_this_color))
            }
        }
    }

    fun onResetConfirmed() {
        val category = category ?: return
        val levelId = levelId ?: return

        paintingProgressRepository.resetProgress(category, levelId)
        thumbnailRepository.deleteThumbnail(category, levelId)

        _uiState.update {
            it.copy(
                paletteProgress = List(uniqueColors.size) { 0f },
                overallProgress = 0f,
                selectedPaletteIndex = -1,
                completedMaskColors = emptySet(),
                completedColorMap = emptyMap(),
                completedIndexes = emptySet(),
                highlightMaskColors = emptyList(),
                activeColors = emptyMap()
            )
        }
    }

    fun requestResetConfirmation() {
        viewModelScope.launch {
            _events.emit(PaintUiEvent.RequestResetConfirmation)
        }
    }

    fun onRegionFilled(maskInt: Int) {
        val category = category ?: return
        val levelId = levelId ?: return
        val newCompleted = _uiState.value.completedMaskColors + maskInt
        val saveStart = System.currentTimeMillis()
        paintingProgressRepository.appendPaintHistory(category, levelId, maskInt)
        paintingProgressRepository.saveProgress(category, levelId, newCompleted)
        Log.d(
            dbgTag,
            "VM_ON_REGION_FILLED mask=$maskInt(${Integer.toHexString(maskInt)}) " +
                "saveProgressMs=${System.currentTimeMillis() - saveStart} t=${System.currentTimeMillis()}"
        )

        val paletteProgress = calculatePaletteProgress(newCompleted)
        val overallProgress = calculateOverallProgress(newCompleted)
        val completedIndexes = calculateCompletedIndexes(newCompleted)
        val selectedIndex = _uiState.value.selectedPaletteIndex
        val selectedColor = uniqueColors.getOrNull(selectedIndex)
        val isSelectedColorCompleted = if (selectedColor != null) {
            val validRegions = allRegions.filter { it.number == selectedColor.number }
            validRegions.all { newCompleted.contains(it.getMaskColorInt()) }
        } else {
            false
        }
        val nextSelectedIndex = when {
            !isSelectedColorCompleted -> selectedIndex
            autoSwitchColorEnabled -> nextSelectableIndexAfter(selectedIndex, completedIndexes)
            else -> -1
        }

        _uiState.update {
            it.copy(
                paletteProgress = paletteProgress,
                overallProgress = overallProgress,
                completedMaskColors = newCompleted,
                completedColorMap = completedColorMap(newCompleted),
                completedIndexes = completedIndexes,
                selectedPaletteIndex = nextSelectedIndex,
                highlightMaskColors = if (nextSelectedIndex == -1 || completedIndexes.size == uniqueColors.size) {
                    emptyList()
                } else {
                    highlightForIndex(nextSelectedIndex, newCompleted)
                },
                activeColors = if (nextSelectedIndex == -1 || completedIndexes.size == uniqueColors.size) {
                    emptyMap()
                } else {
                    activeColorsForIndex(nextSelectedIndex)
                }
            )
        }

        if (completedIndexes.size == uniqueColors.size) {
            viewModelScope.launch {
                _events.emit(
                    PaintUiEvent.LevelCompleted(
                        category = category,
                        levelId = levelId
                    )
                )
            }
        }
    }

    fun saveThumbnail(bitmap: android.graphics.Bitmap?) {
        val category = category ?: return
        val levelId = levelId ?: return
        if (bitmap == null || _uiState.value.completedMaskColors.isEmpty()) return
        try {
            // Truyền thẳng kích thước đã chụp (PaintCanvasView.generateThumbnail): bỏ qua
            // tham số này sẽ rơi vào mặc định 400px của repository, ép ảnh 900px xuống 400px
            // rồi lại phóng to ~2.5x khi hiện full-width trong CurrentPictureDialog → mờ.
            thumbnailRepository.saveThumbnail(category, levelId, bitmap, bitmap.width)
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateCompletedIndexes(completedMaskColors: Set<Int>): Set<Int> {
        return uniqueColors.mapIndexedNotNull { index, color ->
            val validRegions = allRegions.filter { it.number == color.number }
            index.takeIf { validRegions.all { item -> completedMaskColors.contains(item.getMaskColorInt()) } }
        }.toSet()
    }

    private fun calculatePaletteProgress(completedMaskColors: Set<Int>): List<Float> {
        return uniqueColors.map { color ->
            val validRegions = allRegions.filter { it.number == color.number }
            if (validRegions.isEmpty()) {
                0f
            } else {
                val completedCount =
                    validRegions.count { completedMaskColors.contains(it.getMaskColorInt()) }
                completedCount.toFloat() / validRegions.size.toFloat()
            }
        }
    }

    private fun calculateOverallProgress(completedMaskColors: Set<Int>): Float {
        if (allRegions.isEmpty()) return 0f
        return completedMaskColors.size.toFloat() / allRegions.size.toFloat()
    }

    private fun highlightForIndex(index: Int, completedMaskColors: Set<Int>): List<Int> {
        val selectedColor = uniqueColors.getOrNull(index) ?: return emptyList()
        return allRegions
            .filter { it.number == selectedColor.number && !completedMaskColors.contains(it.getMaskColorInt()) }
            .map { it.getMaskColorInt() }
    }

    private fun activeColorsForIndex(index: Int): Map<Int, Int> {
        val selectedColor = uniqueColors.getOrNull(index) ?: return emptyMap()
        return allRegions
            .filter { it.number == selectedColor.number }
            .associate { it.getMaskColorInt() to it.getTargetColorInt() }
    }

    private fun nextSelectableIndexAfter(
        selectedIndex: Int,
        completedIndexes: Set<Int>,
    ): Int {
        if (selectedIndex !in uniqueColors.indices) return -1
        return ((selectedIndex + 1)..uniqueColors.lastIndex)
            .firstOrNull { it !in completedIndexes }
            ?: -1
    }

    private fun completedColorMap(completedMaskColors: Set<Int>): Map<Int, Int> {
        return allRegions
            .filter { completedMaskColors.contains(it.getMaskColorInt()) }
            .associate { it.getMaskColorInt() to it.getTargetColorInt() }
    }
}
