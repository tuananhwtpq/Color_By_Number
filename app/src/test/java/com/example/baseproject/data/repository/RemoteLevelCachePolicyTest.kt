package com.example.baseproject.data.repository

import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.LevelStats
import com.example.baseproject.data.progressFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLevelCachePolicyTest {
    @Test
    fun resolvedConfigAddsProgressMetadataWithoutLosingLibraryMetadata() {
        val summary = summaryConfig()
        val resolved = resolvedConfig()

        val updated = RemoteLevelCachePolicy.replaceWithResolvedConfig(
            levels = listOf(summary),
            resolvedConfig = resolved
        ).single()

        assertEquals("Animal", updated.categoryName)
        assertEquals("https://cdn.example/summary.webp", updated.thumbnailUrl)
        assertEquals(4, updated.totalRegions)
        assertTrue(updated.regions.isNullOrEmpty())
        assertEquals(0.5f, updated.progressFraction(setOf(1, 2)), 0.001f)
    }

    @Test
    fun freshSummaryKeepsCachedProgressMetadata() {
        val freshSummary = summaryConfig().copy(
            thumbnailUrl = "https://cdn.example/fresh.webp",
            sortOrder = 2
        )

        val merged = RemoteLevelCachePolicy.mergeFreshLevels(
            freshLevels = listOf(freshSummary),
            cachedLevels = listOf(resolvedConfig())
        ).single()

        assertEquals("https://cdn.example/fresh.webp", merged.thumbnailUrl)
        assertEquals(2, merged.sortOrder)
        assertEquals(4, merged.totalRegions)
        assertTrue(merged.progressFraction(setOf(1)) > 0f)
    }

    private fun summaryConfig() = LevelConfig(
        id = "owl-01",
        name = "owl-01",
        category = "animal",
        categoryName = "Animal",
        thumbnailUrl = "https://cdn.example/summary.webp",
        sortOrder = 1,
        width = 0,
        height = 0,
        palette = emptyList()
    )

    private fun resolvedConfig() = LevelConfig(
        id = "owl-01",
        name = "Owl",
        category = "animal",
        thumbnailUrl = "https://cdn.example/updated.webp",
        sortOrder = 1,
        width = 100,
        height = 100,
        palette = emptyList(),
        stats = LevelStats(totalRegions = 4)
    )
}
