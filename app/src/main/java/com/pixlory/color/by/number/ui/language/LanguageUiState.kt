package com.pixlory.color.by.number.ui.language

import androidx.annotation.StringRes
import com.pixlory.color.by.number.models.LanguageModel

data class LanguageUiState(
    val isFromHome: Boolean = true,
    val languages: List<LanguageModel> = emptyList(),
    val selectedLanguage: LanguageModel? = null
)

sealed interface LanguageUiEvent {
    data class ShowToast(@param:StringRes val messageRes: Int) : LanguageUiEvent
    object NavigateToMainWithPreparing : LanguageUiEvent
    object NavigateToMain : LanguageUiEvent
}
