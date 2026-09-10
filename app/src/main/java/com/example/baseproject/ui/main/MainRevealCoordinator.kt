package com.example.baseproject.ui.main

/**
 * Coordinates the hand-off from the first visible preparing frame to usable Main content.
 * Time spent behind Android's starting surface is deliberately excluded from the minimum.
 */
class MainRevealCoordinator(
    private val minimumVisibleDurationMillis: Long
) {
    data class RevealPlan(val delayMillis: Long)

    private var preparingVisibleAtMillis: Long? = null
    private var contentReady = false
    private var revealScheduled = false
    private var revealed = false

    fun onPreparingDrawn(nowMillis: Long): RevealPlan? {
        if (preparingVisibleAtMillis == null) {
            preparingVisibleAtMillis = nowMillis
        }
        return planRevealIfReady(nowMillis)
    }

    fun onContentReady(nowMillis: Long): RevealPlan? {
        contentReady = true
        return planRevealIfReady(nowMillis)
    }

    fun onTimeout(): RevealPlan? {
        if (revealScheduled || revealed) return null
        revealScheduled = true
        return RevealPlan(delayMillis = 0L)
    }

    fun onRevealed() {
        revealed = true
    }

    private fun planRevealIfReady(nowMillis: Long): RevealPlan? {
        if (!contentReady || revealScheduled || revealed) return null
        val visibleAt = preparingVisibleAtMillis ?: return null
        revealScheduled = true
        return RevealPlan(
            delayMillis = (minimumVisibleDurationMillis - (nowMillis - visibleAt))
                .coerceAtLeast(0L)
        )
    }
}
