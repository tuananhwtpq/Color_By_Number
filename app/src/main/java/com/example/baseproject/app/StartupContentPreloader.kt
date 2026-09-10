package com.example.baseproject.app

import android.util.Log
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.repository.AssetLevelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Owns the first Library load for the lifetime of the process.
 *
 * Application starts this work while Splash and Language are visible. Language and Library await
 * the exact same deferred result, preventing competing first-load requests from queuing behind
 * the repository mutex and delaying the first Main screen.
 */
class StartupContentPreloader(
    private val assetLevelRepository: AssetLevelRepository,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var initialLoad: Deferred<Result<List<LevelConfig>>>? = null
    private var lastLoadFailed = false

    fun start(): Deferred<Result<List<LevelConfig>>> = synchronized(lock) {
        initialLoad ?: launchLoad()
    }

    /** Starts a new attempt only after the shared startup attempt has finished with an error. */
    fun retryAfterFailure(): Deferred<Result<List<LevelConfig>>> = synchronized(lock) {
        val currentLoad = initialLoad
        when {
            currentLoad == null -> launchLoad()
            currentLoad.isActive -> currentLoad
            !lastLoadFailed -> currentLoad
            else -> launchLoad()
        }
    }

    private fun launchLoad(): Deferred<Result<List<LevelConfig>>> =
        scope.async {
            try {
                // Match the former Intro flow: fetch fresh category metadata early, while image
                // thumbnails continue loading lazily after Main is visible.
                val levels = assetLevelRepository.refreshAllLevels()
                check(levels.isNotEmpty()) { "Initial library preload returned no levels" }
                synchronized(lock) { lastLoadFailed = false }
                Result.success(levels)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Initial library preload failed", error)
                synchronized(lock) { lastLoadFailed = true }
                Result.failure(error)
            }
        }.also { initialLoad = it }

    private companion object {
        const val TAG = "StartupContentPreloader"
    }
}
