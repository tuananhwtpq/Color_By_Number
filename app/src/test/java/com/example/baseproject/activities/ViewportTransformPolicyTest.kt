package com.pixlory.color.by.number.activities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTransformPolicyTest {
    @Test
    fun fitCenterStateIsTreatedAsInitialViewport() {
        assertTrue(
            ViewportTransformPolicy.isAtFitCenter(
                scale = 0.5f,
                translationX = 0f,
                translationY = 250f,
                viewportWidth = 500f,
                viewportHeight = 1_000f,
                artworkWidth = 1_000,
                artworkHeight = 1_000,
            )
        )
    }

    @Test
    fun translatedViewportIsNotTreatedAsInitial() {
        assertFalse(
            ViewportTransformPolicy.isAtFitCenter(
                scale = 0.5f,
                translationX = 30f,
                translationY = 250f,
                viewportWidth = 500f,
                viewportHeight = 1_000f,
                artworkWidth = 1_000,
                artworkHeight = 1_000,
            )
        )
    }

    @Test
    fun zoomedViewportIsNotTreatedAsInitial() {
        assertFalse(
            ViewportTransformPolicy.isAtFitCenter(
                scale = 0.8f,
                translationX = 0f,
                translationY = 250f,
                viewportWidth = 500f,
                viewportHeight = 1_000f,
                artworkWidth = 1_000,
                artworkHeight = 1_000,
            )
        )
    }

    @Test
    fun tinyFloatingPointDifferencesDoNotToggleViewportState() {
        assertTrue(
            ViewportTransformPolicy.isAtFitCenter(
                scale = 0.5004f,
                translationX = 0.4f,
                translationY = 249.6f,
                viewportWidth = 500f,
                viewportHeight = 1_000f,
                artworkWidth = 1_000,
                artworkHeight = 1_000,
            )
        )
    }

    @Test
    fun unloadedArtworkIsTreatedAsInitialSoResetButtonStaysHidden() {
        assertTrue(
            ViewportTransformPolicy.isAtFitCenter(
                scale = 1f,
                translationX = 0f,
                translationY = 0f,
                viewportWidth = 500f,
                viewportHeight = 1_000f,
                artworkWidth = 0,
                artworkHeight = 0,
            )
        )
    }
}
