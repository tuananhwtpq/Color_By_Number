package com.pixlory.color.by.number.adapters

/**
 * Keeps the transient "play completion animation, then remove" state separate from
 * RecyclerView holders. Tokens make delayed animator callbacks harmless.
 */
internal class PaletteCompletionStateTracker {
    private sealed interface State {
        data object Queued : State
        data class Running(val token: Long) : State
        data object Removed : State
    }

    private val states = mutableMapOf<Int, State>()
    private var nextToken = 0L

    fun queue(originalIndex: Int) {
        if (states[originalIndex] !is State.Removed) {
            states[originalIndex] = State.Queued
        }
    }

    fun restoreAsRemoved(originalIndex: Int) {
        states[originalIndex] = State.Removed
    }

    fun clear(originalIndex: Int) {
        states.remove(originalIndex)
    }

    fun retainOnly(completedIndexes: Set<Int>) {
        states.keys.retainAll(completedIndexes)
    }

    fun claimAnimation(originalIndex: Int): Long? {
        if (states[originalIndex] !is State.Queued) return null

        val token = ++nextToken
        states[originalIndex] = State.Running(token)
        return token
    }

    fun complete(originalIndex: Int, token: Long): Boolean {
        if (states[originalIndex] != State.Running(token)) return false

        states[originalIndex] = State.Removed
        return true
    }

    fun isVisible(originalIndex: Int): Boolean = states[originalIndex] !is State.Removed

    fun isRunning(originalIndex: Int, token: Long): Boolean =
        states[originalIndex] == State.Running(token)
}
