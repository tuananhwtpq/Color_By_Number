package com.example.baseproject.ui.paint

import android.graphics.Bitmap
import com.example.baseproject.data.PaletteItem
import com.example.baseproject.data.RegionData

data class PaintUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val palette: List<PaletteItem> = emptyList(),
    val paletteProgress: List<Float> = emptyList(),
    val selectedPaletteIndex: Int = 0,
    val completedMaskColors: Set<Int> = emptySet(),
    val completedColorMap: Map<Int, Int> = emptyMap(),
    val completedIndexes: Set<Int> = emptySet(),
    val highlightMaskColors: List<Int> = emptyList(),
    val activeColors: Map<Int, Int> = emptyMap(),
    val renderData: PaintRenderData? = null,
    val errorMessage: String? = null,
)

data class PaintRenderData(
    val category: String,
    val levelId: String,
    val lineBitmap: Bitmap,
    val maskBitmap: Bitmap,
    val regions: List<RegionData>
)

sealed interface PaintUiEvent {
    data class ShowToast(val message: String) : PaintUiEvent
    data class FocusOnMaskColor(val maskColor: Int) : PaintUiEvent
    object FinishScreen : PaintUiEvent
    object RequestResetConfirmation : PaintUiEvent
}
