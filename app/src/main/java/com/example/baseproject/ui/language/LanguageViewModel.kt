package com.example.baseproject.ui.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.baseproject.data.repository.SettingsRepository
import com.example.baseproject.models.LanguageModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LanguageViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(LanguageUiState())
    val uiState: StateFlow<LanguageUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LanguageUiEvent>()
    val events: SharedFlow<LanguageUiEvent> = _events.asSharedFlow()

    fun initialize(isFromHome: Boolean) {
        _uiState.update {
            it.copy(
                isFromHome = isFromHome,
                languages = settingsRepository.getLanguageList(),
                selectedLanguage = if (isFromHome) settingsRepository.getSelectedLanguage() else null
            )
        }
        if (!isFromHome) {
            emitEvent(LanguageUiEvent.RequestNotificationPermission)
        }
    }

    fun applyLanguage(selectedLanguage: LanguageModel?) {
        if (selectedLanguage == null) {
            emitEvent(LanguageUiEvent.ShowToast("Please select language first"))
            return
        }
        settingsRepository.setSelectedLanguage(selectedLanguage)
        emitEvent(
            if (_uiState.value.isFromHome) LanguageUiEvent.NavigateToMain
            else LanguageUiEvent.NavigateToIntro
        )
    }

    private fun emitEvent(event: LanguageUiEvent) {
        viewModelScope.launch {
            _events.emit(event)
        }
    }
}
