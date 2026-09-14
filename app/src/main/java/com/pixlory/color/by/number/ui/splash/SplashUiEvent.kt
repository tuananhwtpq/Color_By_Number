package com.pixlory.color.by.number.ui.splash

sealed interface SplashUiEvent {
    object RequestConsent : SplashUiEvent
    object InitializeAds : SplashUiEvent
    object NavigateToMain : SplashUiEvent
}
