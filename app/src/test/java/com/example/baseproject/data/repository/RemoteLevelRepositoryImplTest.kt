package com.pixlory.color.by.number.data.repository

import com.pixlory.color.by.number.data.LevelConfig
import com.pixlory.color.by.number.data.remote.AchievementResponse
import com.pixlory.color.by.number.data.remote.AchievementsResponse
import com.pixlory.color.by.number.data.remote.CategoriesData
import com.pixlory.color.by.number.data.remote.CategoriesResponse
import com.pixlory.color.by.number.data.remote.CategoryResponse
import com.pixlory.color.by.number.data.remote.CollectionResponse
import com.pixlory.color.by.number.data.remote.CollectionsResponse
import com.pixlory.color.by.number.data.remote.GroupLevelsData
import com.pixlory.color.by.number.data.remote.GroupLevelsResponse
import com.pixlory.color.by.number.data.remote.LevelDetailResponse
import com.pixlory.color.by.number.data.remote.PixcolorApi
import com.pixlory.color.by.number.data.remote.RealmsResponse
import com.pixlory.color.by.number.data.remote.RealmResponse
import com.pixlory.color.by.number.data.remote.RemoteAssetLoader
import com.pixlory.color.by.number.data.remote.RemoteGroupDto
import com.pixlory.color.by.number.data.remote.RemoteLevelSummaryDto
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
