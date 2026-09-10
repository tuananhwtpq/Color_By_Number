package com.example.baseproject.app

import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.LevelBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupContentPreloaderTest {

    @Test
    fun concurrentCallersShareOneInitialLoad() = runBlocking {
        val loadGate = CompletableDeferred<Unit>()
        val repository = FakeAssetLevelRepository(loadGate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val preloader = StartupContentPreloader(repository, scope)

            val firstLoad = preloader.start()
            val secondLoad = preloader.start()

            assertSame(firstLoad, secondLoad)
            assertEquals(1, repository.refreshCalls)

            loadGate.complete(Unit)

            assertTrue(firstLoad.await().isSuccess)
            assertEquals(1, repository.refreshCalls)
        } finally {
            scope.cancel()
        }
    }

    private class FakeAssetLevelRepository(
        private val loadGate: CompletableDeferred<Unit>
    ) : AssetLevelRepository {
        var refreshCalls = 0

        override suspend fun loadAllLevels(): List<LevelConfig> = emptyList()

        override suspend fun refreshAllLevels(): List<LevelConfig> {
            refreshCalls++
            loadGate.await()
            return listOf(sampleLevel())
        }

        override suspend fun loadLevelBundle(category: String, levelId: String): LevelBundle =
            error("Not needed for startup preloader test")
    }

    private companion object {
        fun sampleLevel() = LevelConfig(
            id = "owl-01",
            name = "Owl",
            category = "animal",
            width = 1,
            height = 1,
            palette = emptyList()
        )
    }
}
