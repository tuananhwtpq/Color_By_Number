package com.example.baseproject.ui.language

import com.example.baseproject.models.LanguageModel

data class LanguageUiState(
    val isFromHome: Boolean = true,
    val languages: List<LanguageModel> = emptyList(),
    val selectedLanguage: LanguageModel? = null
)

sealed interface LanguageUiEvent {
    object RequestNotificationPermission : LanguageUiEvent
    data class ShowToast(val message: String) : LanguageUiEvent
    object NavigateToIntro : LanguageUiEvent
    object NavigateToMain : LanguageUiEvent
}
