package com.example.baseproject.data.progress

import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progressFraction
import com.example.baseproject.data.progressRegionCount
import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.LevelBundle
import com.example.baseproject.data.repository.PaintingProgressRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedProgressMetadataResolverTest {

    @Test
    fun resolvesOnlySavedPaintingsWhoseRegionCountIsMissing() = runBlocking {
        val incompleteSummary = summaryLevel(id = "owl-01")
        val resolvedLevel = incompleteSummary.copy(totalRegions = 40)
        val noProgressSummary = summaryLevel(id = "fox-01")
        val alreadyResolved = summaryLevel(id = "cat-01").copy(totalRegions = 30)
        val assetRepository = FakeAssetLevelRepository(
            resolvedMetadata = mapOf(incompleteSummary.id to resolvedLevel)
        )
        val resolver = SavedProgressMetadataResolver(
            assetLevelRepository = assetRepository,
            paintingProgressRepository = FakePaintingProgressRepository(
                progressByLevelId = mapOf(incompleteSummary.id to setOf(1, 2))
            )
        )

        val result = resolver.resolve(listOf(incompleteSummary, noProgressSummary, alreadyResolved))

        assertEquals(40, result[0].totalRegions)
        assertEquals(0, result[1].progressRegionCount())
        assertEquals(30, result[2].totalRegions)
        assertEquals(listOf(incompleteSummary.id), assetRepository.resolvedLevelIds)
    }

    @Test
    fun refreshedSummaryKeepsSavedPaintingPercentageVisible() = runBlocking {
        val refreshedSummary = summaryLevel(id = "owl-01")
        val completedMaskColors = setOf(1, 2)
        val resolver = SavedProgressMetadataResolver(
            assetLevelRepository = FakeAssetLevelRepository(
                resolvedMetadata = mapOf(
                    refreshedSummary.id to refreshedSummary.copy(totalRegions = 4)
                )
            ),
            paintingProgressRepository = FakePaintingProgressRepository(
                progressByLevelId = mapOf(refreshedSummary.id to completedMaskColors)
            )
        )

        val resolved = resolver.resolve(listOf(refreshedSummary)).single()

        assertEquals(0.5f, resolved.progressFraction(completedMaskColors), 0.001f)
    }

    private fun summaryLevel(id: String) = LevelConfig(
        id = id,
        name = id,
        category = "animal",
        width = 0,
        height = 0,
        palette = emptyList()
    )

    private class FakeAssetLevelRepository(
        private val resolvedMetadata: Map<String, LevelConfig>
    ) : AssetLevelRepository {
        val resolvedLevelIds = mutableListOf<String>()

        override suspend fun loadAllLevels(): List<LevelConfig> = emptyList()

        override suspend fun resolveProgressMetadata(level: LevelConfig): LevelConfig {
            resolvedLevelIds += level.id
            return requireNotNull(resolvedMetadata[level.id])
        }

        override suspend fun loadLevelBundle(category: String, levelId: String): LevelBundle =
            error("Not needed for progress metadata resolution")
    }

    private class FakePaintingProgressRepository(
        private val progressByLevelId: Map<String, Set<Int>>
    ) : PaintingProgressRepository {
        override fun loadProgress(category: String, levelId: String): Set<Int> =
            progressByLevelId[levelId].orEmpty()

        override fun saveProgress(category: String, levelId: String, completedMaskColors: Set<Int>) = Unit
        override fun loadPaintHistory(category: String, levelId: String): List<Int> = emptyList()
        override fun appendPaintHistory(category: String, levelId: String, maskColor: Int) = Unit
        override fun resetProgress(category: String, levelId: String) = Unit
        override fun lastPaintedAt(category: String, levelId: String): Long = 0L
    }
}
