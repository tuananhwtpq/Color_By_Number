package com.example.baseproject.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SplashViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SplashUiEvent>()
    val events: SharedFlow<SplashUiEvent> = _events.asSharedFlow()

    private var minimumDelayCompleted = false
    private var adsFlowCompleted = false

    fun startSplash(
        hasNetwork: Boolean,
        delayMillis: Long = 3000L
    ) {
        minimumDelayCompleted = false
        adsFlowCompleted = !hasNetwork
        _uiState.update { it.copy(showAdsLoading = hasNetwork) }

        viewModelScope.launch {
            delay(delayMillis)
            minimumDelayCompleted = true
            navigateIfReady()
        }

        if (hasNetwork) {
            viewModelScope.launch {
                _events.emit(SplashUiEvent.RequestConsent)
            }
        }
    }

    fun onConsentResolved(canRequestAds: Boolean) {
        if (canRequestAds) {
            viewModelScope.launch {
                _events.emit(SplashUiEvent.InitializeAds)
            }
        } else {
            onAdsFlowCompleted()
        }
    }

    fun onAdsInitialized() {
        onAdsFlowCompleted()
    }

    fun onAdsInitializationFailed() {
        onAdsFlowCompleted()
    }

    private fun onAdsFlowCompleted() {
        adsFlowCompleted = true
        _uiState.update { it.copy(showAdsLoading = false) }
        viewModelScope.launch {
            navigateIfReady()
        }
    }

    private suspend fun navigateIfReady() {
        if (minimumDelayCompleted && adsFlowCompleted) {
            _events.emit(SplashUiEvent.NavigateToMain)
        }
    }
}
