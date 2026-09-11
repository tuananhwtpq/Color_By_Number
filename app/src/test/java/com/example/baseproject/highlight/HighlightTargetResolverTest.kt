package com.pixlory.color.by.number.highlight

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HighlightTargetResolverTest {

    @Test
    fun retainsAnAnimatingTargetUntilItsFillIsCommitted() {
        val targetsDuringFill = HighlightTargetResolver.resolve(
            requestedTargets = intArrayOf(8),
            retainedDuringFill = setOf(3),
            completedTargets = emptySet(),
        )

        assertArrayEquals(intArrayOf(3, 8), targetsDuringFill)

        val targetsAfterCommit = HighlightTargetResolver.resolve(
            requestedTargets = intArrayOf(8),
            retainedDuringFill = emptySet(),
            completedTargets = setOf(3),
        )

        assertArrayEquals(intArrayOf(8), targetsAfterCommit)
    }
}
