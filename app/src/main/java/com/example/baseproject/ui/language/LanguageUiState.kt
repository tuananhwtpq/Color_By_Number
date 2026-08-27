package com.example.baseproject.ui.language

import androidx.annotation.StringRes
import com.example.baseproject.models.LanguageModel

data class LanguageUiState(
    val isFromHome: Boolean = true,
    val languages: List<LanguageModel> = emptyList(),
    val selectedLanguage: LanguageModel? = null
)

sealed interface LanguageUiEvent {
    object RequestNotificationPermission : LanguageUiEvent
    data class ShowToast(@param:StringRes val messageRes: Int) : LanguageUiEvent
    object NavigateToIntro : LanguageUiEvent
    object NavigateToMain : LanguageUiEvent
}
