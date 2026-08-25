package com.example.baseproject.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progressFraction
import com.example.baseproject.data.repository.CollectionRepository
import com.example.baseproject.data.repository.PaintingProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollectionDetailViewModel(
    private val collectionId: String,
    private val collectionRepository: CollectionRepository,
    private val paintingProgressRepository: PaintingProgressRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                collectionRepository.loadCollectionDetail(collectionId)
            }.onSuccess { detail ->
                if (detail == null) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Collection not found")
                    }
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        collection = detail.collection,
                        levels = detail.levels,
                        completedCount = countCompleted(detail.levels),
                        errorMessage = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Failed to load collection"
                    )
                }
            }
        }
    }

    /** Gọi lại khi quay về từ màn tô để cập nhật "đã xong / tổng". */
    fun refreshProgress() {
        _uiState.update { it.copy(completedCount = countCompleted(it.levels)) }
    }

    private fun countCompleted(levels: List<LevelConfig>): Int = levels.count { level ->
        val completedMaskColors =
            paintingProgressRepository.loadProgress(level.category, level.id)
        level.progressFraction(completedMaskColors) >= 1f
    }
}
