package com.example.baseproject.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.baseproject.app.StartupContentPreloader
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progress.SavedProgressMetadataResolver
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.PaintingProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val assetLevelRepository: AssetLevelRepository,
    private val startupContentPreloader: StartupContentPreloader,
    paintingProgressRepository: PaintingProgressRepository
) : ViewModel() {

    private val savedProgressMetadataResolver = SavedProgressMetadataResolver(
        assetLevelRepository,
        paintingProgressRepository
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var allLevels: List<LevelConfig> = emptyList()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val levels = savedProgressMetadataResolver.resolve(
                    startupContentPreloader.start().await().getOrThrow()
                )
                showLevels(levels)
                refreshLevelsInBackground(levels)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Failed to load levels",
                        categories = emptyList(),
                        categoryNames = emptyMap(),
                        selectedCategory = null,
                        visibleLevels = emptyList()
                    )
                }
            }
        }
    }

    fun reloadLevels() {
        viewModelScope.launch {
            try {
                // The startup result is only a first-load snapshot. PaintActivity may have
                // resolved and cached the level's total region count after that snapshot was
                // created, which is required to render the saved progress percentage.
                val levels = savedProgressMetadataResolver.resolve(
                    assetLevelRepository.loadAllLevels()
                )
                showLevels(levels)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (throwable: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = throwable.message) }
            }
        }
    }

    fun selectCategory(category: String) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                visibleLevels = filterLevels(category)
            )
        }
    }

    private fun filterLevels(category: String): List<LevelConfig> =
        allLevels.filter { it.category == category }

    private fun showLevels(levels: List<LevelConfig>) {
        allLevels = levels
        val categories = levels.map { it.category }.distinct().sorted()
        val categoryNames = levels
            .groupBy { it.category }
            .mapValues { (_, categoryLevels) ->
                categoryLevels.firstNotNullOfOrNull { it.categoryName }
                    ?: categoryLevels.first().category
            }
        val selectedCategory = _uiState.value.selectedCategory?.takeIf { it in categories }
            ?: categories.firstOrNull()
        _uiState.update {
            it.copy(
                isLoading = false,
                categories = categories,
                categoryNames = categoryNames,
                selectedCategory = selectedCategory,
                visibleLevels = selectedCategory?.let(::filterLevels).orEmpty()
            )
        }
    }

    private fun refreshLevelsInBackground(previousLevels: List<LevelConfig>) {
        viewModelScope.launch {
            runCatching {
                assetLevelRepository.refreshAllLevels()
            }.onSuccess { freshLevels ->
                if (freshLevels != previousLevels) {
                    showLevels(freshLevels)
                }
            }
        }
    }
}
