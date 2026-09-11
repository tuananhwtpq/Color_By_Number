package com.pixlory.color.by.number.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainRevealCoordinatorTest {

    @Test
    fun minimumDurationStartsWhenPreparingIsActuallyDrawn() {
        val coordinator = MainRevealCoordinator(minimumVisibleDurationMillis = 900L)

        assertNull(coordinator.onPreparingDrawn(nowMillis = 1_000L))
        val plan = coordinator.onContentReady(nowMillis = 1_350L)

        assertEquals(550L, plan?.delayMillis)
    }

    @Test
    fun contentReadyBeforeFirstDrawStillWaitsForVisiblePreparingDuration() {
        val coordinator = MainRevealCoordinator(minimumVisibleDurationMillis = 900L)

        assertNull(coordinator.onContentReady(nowMillis = 500L))
        val plan = coordinator.onPreparingDrawn(nowMillis = 1_000L)

        assertEquals(900L, plan?.delayMillis)
    }

    @Test
    fun contentReadyAfterMinimumDurationRevealsImmediately() {
        val coordinator = MainRevealCoordinator(minimumVisibleDurationMillis = 900L)

        coordinator.onPreparingDrawn(nowMillis = 1_000L)
        val plan = coordinator.onContentReady(nowMillis = 2_100L)

        assertEquals(0L, plan?.delayMillis)
    }

    @Test
    fun duplicateReadySignalsCannotScheduleMultipleReveals() {
        val coordinator = MainRevealCoordinator(minimumVisibleDurationMillis = 900L)

        coordinator.onPreparingDrawn(nowMillis = 1_000L)
        assertEquals(700L, coordinator.onContentReady(nowMillis = 1_200L)?.delayMillis)
        assertNull(coordinator.onContentReady(nowMillis = 1_300L))
        assertNull(coordinator.onPreparingDrawn(nowMillis = 1_400L))
    }

    @Test
    fun timeoutRevealsImmediatelyAndIgnoresLateContent() {
        val coordinator = MainRevealCoordinator(minimumVisibleDurationMillis = 900L)

        coordinator.onPreparingDrawn(nowMillis = 1_000L)
        assertEquals(0L, coordinator.onTimeout()?.delayMillis)
        assertNull(coordinator.onContentReady(nowMillis = 1_100L))
    }
}
