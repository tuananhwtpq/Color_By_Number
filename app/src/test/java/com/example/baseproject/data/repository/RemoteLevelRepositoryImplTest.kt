package com.example.baseproject.data.repository

import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.remote.AchievementResponse
import com.example.baseproject.data.remote.AchievementsResponse
import com.example.baseproject.data.remote.CategoriesData
import com.example.baseproject.data.remote.CategoriesResponse
import com.example.baseproject.data.remote.CategoryResponse
import com.example.baseproject.data.remote.CollectionResponse
import com.example.baseproject.data.remote.CollectionsResponse
import com.example.baseproject.data.remote.GroupLevelsData
import com.example.baseproject.data.remote.GroupLevelsResponse
import com.example.baseproject.data.remote.LevelDetailResponse
import com.example.baseproject.data.remote.PixcolorApi
import com.example.baseproject.data.remote.RealmsResponse
import com.example.baseproject.data.remote.RealmResponse
import com.example.baseproject.data.remote.RemoteAssetLoader
import com.example.baseproject.data.remote.RemoteGroupDto
import com.example.baseproject.data.remote.RemoteLevelSummaryDto
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.nio.file.Files

class RemoteLevelRepositoryImplTest {

    @Test
    fun refreshFromColdStartPreservesResolvedProgressMetadataFromDisk() = runBlocking {
        val cacheFile = Files.createTempDirectory("level-metadata").toFile()
            .resolve("levels.json")
        cacheFile.writeText(Gson().toJson(listOf(resolvedLevel())))

        val repository = RemoteLevelRepositoryImpl(
            api = SummaryOnlyApi(),
            assetLoader = RemoteAssetLoader(baseUrl = "https://example.test/"),
            metadataCacheFile = cacheFile,
            ioDispatcher = Dispatchers.Unconfined,
            categoryRequestLimit = 1
        )

        val refreshedLevel = repository.refreshAllLevels().single()
        val persistedLevel = Gson().fromJson(
            cacheFile.reader(),
            Array<LevelConfig>::class.java
        ).single()

        assertEquals(40, refreshedLevel.totalRegions)
        assertEquals(40, persistedLevel.totalRegions)
    }

    private fun resolvedLevel() = LevelConfig(
        id = LEVEL_ID,
        name = "Owl",
        category = CATEGORY_ID,
        width = 100,
        height = 100,
        palette = emptyList(),
        totalRegions = 40
    )

    private class SummaryOnlyApi : PixcolorApi {
        override suspend fun categories(): Response<CategoriesResponse> = Response.success(
            CategoriesResponse(
                success = true,
                data = CategoriesData(
                    categories = listOf(RemoteGroupDto(id = CATEGORY_ID))
                )
            )
        )

        override suspend fun groupLevels(
            groupType: String,
            groupId: String
        ): Response<GroupLevelsResponse> = Response.success(
            GroupLevelsResponse(
                success = true,
                data = GroupLevelsData(
                    levels = listOf(
                        RemoteLevelSummaryDto(
                            id = LEVEL_ID,
                            groupType = "CATEGORY",
                            groupId = CATEGORY_ID
                        )
                    )
                )
            )
        )

        override suspend fun category(id: String): Response<CategoryResponse> = unused()
        override suspend fun collections(): Response<CollectionsResponse> = unused()
        override suspend fun collection(id: String): Response<CollectionResponse> = unused()
        override suspend fun levelDetail(levelId: String): Response<LevelDetailResponse> = unused()
        override suspend fun realms(): Response<RealmsResponse> = unused()
        override suspend fun realm(id: String): Response<RealmResponse> = unused()
        override suspend fun achievements(): Response<AchievementsResponse> = unused()
        override suspend fun achievement(id: String): Response<AchievementResponse> = unused()

        private fun <T> unused(): Response<T> =
            throw AssertionError("This endpoint is not needed for the refresh test")
    }

    private companion object {
        const val CATEGORY_ID = "animal"
        const val LEVEL_ID = "owl-01"
    }
}
