package com.example.baseproject.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.repository.AssetLevelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val assetLevelRepository: AssetLevelRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var allLevels: List<LevelConfig> = emptyList()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                assetLevelRepository.loadAllLevels()
            }.onSuccess { levels ->
                allLevels = levels
                val categories = levels.map { it.category }.distinct().sorted()
                val selectedCategory = _uiState.value.selectedCategory?.takeIf { it in categories }
                    ?: categories.firstOrNull()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = categories,
                        selectedCategory = selectedCategory,
                        visibleLevels = selectedCategory?.let(::filterLevels).orEmpty()
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Failed to load levels",
                        categories = emptyList(),
                        selectedCategory = null,
                        visibleLevels = emptyList()
                    )
                }
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
}
