package com.example.baseproject.ui.paint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                allRegions = bundle.config.palette
                uniqueColors = allRegions.groupBy { it.number }
                    .map { it.value.first() }
                    .sortedBy { it.number }
                regionMetadata = bundle.regions

                val completedMaskColors = paintingProgressRepository.loadProgress(category, levelId)
                val paletteProgress = calculatePaletteProgress(completedMaskColors)
                val completedIndexes = calculateCompletedIndexes(completedMaskColors)
                val selectedIndex =
                    uniqueColors.indices.firstOrNull { it !in completedIndexes } ?: 0

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        title = bundle.config.name,
                        palette = uniqueColors,
                        paletteProgress = paletteProgress,
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
                            maskBitmap = bundle.maskBitmap,
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
                _events.emit(PaintUiEvent.ShowToast("Failed to load level"))
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
                _events.emit(PaintUiEvent.ShowToast("Bạn đã tô xong màu này rồi!"))
            }
        }
    }

    fun onResetConfirmed() {
        val category = category ?: return
        val levelId = levelId ?: return

        paintingProgressRepository.resetProgress(category, levelId)
        thumbnailRepository.deleteThumbnail(category, levelId)

        val selectedIndex = if (uniqueColors.isNotEmpty()) 0 else 0
        _uiState.update {
            it.copy(
                paletteProgress = List(uniqueColors.size) { 0f },
                selectedPaletteIndex = selectedIndex,
                completedMaskColors = emptySet(),
                completedColorMap = emptyMap(),
                completedIndexes = emptySet(),
                highlightMaskColors = highlightForIndex(selectedIndex, emptySet()),
                activeColors = activeColorsForIndex(selectedIndex)
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
        paintingProgressRepository.saveProgress(category, levelId, newCompleted)

        val paletteProgress = calculatePaletteProgress(newCompleted)
        val completedIndexes = calculateCompletedIndexes(newCompleted)
        var selectedIndex = _uiState.value.selectedPaletteIndex
        val selectedColor = uniqueColors.getOrNull(selectedIndex)
        if (selectedColor != null) {
            val validRegions = allRegions.filter { it.number == selectedColor.number }
            val isSelectedColorCompleted =
                validRegions.all { newCompleted.contains(it.getMaskColorInt()) }
            if (isSelectedColorCompleted) {
                selectedIndex =
                    uniqueColors.indices.firstOrNull { it !in completedIndexes } ?: selectedIndex
            }
        }

        _uiState.update {
            it.copy(
                paletteProgress = paletteProgress,
                completedMaskColors = newCompleted,
                completedColorMap = completedColorMap(newCompleted),
                completedIndexes = completedIndexes,
                selectedPaletteIndex = selectedIndex,
                highlightMaskColors = if (completedIndexes.size == uniqueColors.size) {
                    emptyList()
                } else {
                    highlightForIndex(selectedIndex, newCompleted)
                },
                activeColors = if (completedIndexes.size == uniqueColors.size) {
                    emptyMap()
                } else {
                    activeColorsForIndex(selectedIndex)
                }
            )
        }

        if (completedIndexes.size == uniqueColors.size) {
            viewModelScope.launch {
                _events.emit(PaintUiEvent.ShowToast("Level Completed! 🎉"))
            }
        }
    }

    fun saveThumbnail(bitmap: android.graphics.Bitmap?) {
        val category = category ?: return
        val levelId = levelId ?: return
        if (bitmap == null || _uiState.value.completedMaskColors.isEmpty()) return
        try {
            thumbnailRepository.saveThumbnail(category, levelId, bitmap)
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

    private fun completedColorMap(completedMaskColors: Set<Int>): Map<Int, Int> {
        return allRegions
            .filter { completedMaskColors.contains(it.getMaskColorInt()) }
            .associate { it.getMaskColorInt() to it.getTargetColorInt() }
    }
}
