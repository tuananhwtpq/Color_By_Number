package com.example.baseproject.ui.splash

sealed interface SplashUiEvent {
    object RequestConsent : SplashUiEvent
    object InitializeAds : SplashUiEvent
    object NavigateToMain : SplashUiEvent
}
