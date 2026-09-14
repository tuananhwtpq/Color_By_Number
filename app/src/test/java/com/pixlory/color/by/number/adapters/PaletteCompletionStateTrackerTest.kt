package com.pixlory.color.by.number.adapters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteCompletionStateTrackerTest {

    @Test
    fun finishingAWhileBIsRunningKeepsBVisibleUntilItsOwnCallback() {
        val tracker = PaletteCompletionStateTracker()
        tracker.queue(0)
        tracker.queue(1)

        val animationA = requireNotNull(tracker.claimAnimation(0))
        val animationB = requireNotNull(tracker.claimAnimation(1))

        assertNotEquals(animationA, animationB)
        assertTrue(tracker.complete(0, animationA))

        assertFalse(tracker.isVisible(0))
        assertTrue(tracker.isVisible(1))
        assertTrue(tracker.isRunning(1, animationB))

        assertTrue(tracker.complete(1, animationB))
        assertFalse(tracker.isVisible(1))
    }

    @Test
    fun staleCallbackCannotRemoveANewerAnimation() {
        val tracker = PaletteCompletionStateTracker()
        tracker.queue(3)
        val firstAnimation = requireNotNull(tracker.claimAnimation(3))
        assertTrue(tracker.complete(3, firstAnimation))

        tracker.clear(3)
        tracker.queue(3)
        val secondAnimation = requireNotNull(tracker.claimAnimation(3))

        assertFalse(tracker.complete(3, firstAnimation))
        assertTrue(tracker.isRunning(3, secondAnimation))
        assertTrue(tracker.complete(3, secondAnimation))
    }
}
