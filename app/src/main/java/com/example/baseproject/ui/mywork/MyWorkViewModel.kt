package com.example.baseproject.ui.mywork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progressFraction
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.CollectionRepository
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.data.repository.ThumbnailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MyWorkViewModel(
    private val assetLevelRepository: AssetLevelRepository,
    private val collectionRepository: CollectionRepository,
    private val paintingProgressRepository: PaintingProgressRepository,
    private val thumbnailRepository: ThumbnailRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyWorkUiState())
    val uiState: StateFlow<MyWorkUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        loadData()
    }

    fun loadData(showLoading: Boolean = true) {
        if (!showLoading && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            runCatching {
                // Tranh trong Collection không nằm trong loadAllLevels() (tab Library bỏ qua
                // folder Collection), nên phải gộp thêm ở đây để My Work thấy được chúng.
                assetLevelRepository.loadAllLevels() + collectionRepository.loadAllCollectionLevels()
            }.onSuccess { levels ->
                val inProgress = mutableListOf<LevelConfig>()
                val completed = mutableListOf<LevelConfig>()
                levels.forEach { level ->
                    val completedMaskColors =
                        paintingProgressRepository.loadProgress(level.category, level.id)
                    val progress = level.progressFraction(completedMaskColors)
                    when {
                        progress >= 1f -> completed.add(level)
                        progress > 0f -> inProgress.add(level)
                    }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        inProgressLevels = inProgress.sortedByDescending(::lastPaintedAt),
                        completedLevels = completed.sortedByDescending(::lastPaintedAt)
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // Tranh tô gần đây nhất lên đầu. Mốc thời gian được ghi từ lúc tính năng này có mặt, nên
    // với tiến trình đã lưu từ trước thì lùi về thời điểm sửa file thumbnail (thumbnail được
    // ghi lại mỗi lần rời màn tô) để thứ tự vẫn hợp lý thay vì dồn hết xuống cuối.
    private fun lastPaintedAt(level: LevelConfig): Long {
        val savedAt = paintingProgressRepository.lastPaintedAt(level.category, level.id)
        if (savedAt > 0L) return savedAt
        return thumbnailRepository.getThumbnailFile(level.category, level.id)
            .takeIf { it.exists() }
            ?.lastModified()
            ?: 0L
    }
}
