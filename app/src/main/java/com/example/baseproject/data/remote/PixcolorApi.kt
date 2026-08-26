package com.example.baseproject.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface PixcolorApi {
    @GET("api/v1/categories")
    suspend fun categories(): Response<CategoriesResponse>

    @GET("api/v1/categories/{id}")
    suspend fun category(@Path("id") id: String): Response<CategoryResponse>

    @GET("api/v1/collections")
    suspend fun collections(): Response<CollectionsResponse>

    @GET("api/v1/collections/{id}")
    suspend fun collection(@Path("id") id: String): Response<CollectionResponse>

    @GET("api/v1/groups/{groupType}/{groupId}/levels")
    suspend fun groupLevels(
        @Path("groupType") groupType: String,
        @Path("groupId") groupId: String
    ): Response<GroupLevelsResponse>

    @GET("api/v1/levels/{levelId}")
    suspend fun levelDetail(@Path("levelId") levelId: String): Response<LevelDetailResponse>

    @GET("api/v1/realms")
    suspend fun realms(): Response<RealmsResponse>

    @GET("api/v1/realms/{id}")
    suspend fun realm(@Path("id") id: String): Response<RealmResponse>

    @GET("api/v1/achievements")
    suspend fun achievements(): Response<AchievementsResponse>

    @GET("api/v1/achievements/{id}")
    suspend fun achievement(@Path("id") id: String): Response<AchievementResponse>
}

data class CategoriesResponse(
    val success: Boolean = false,
    val data: CategoriesData? = null
)

data class CategoriesData(
    val categories: List<RemoteGroupDto> = emptyList()
)

data class CategoryResponse(
    val success: Boolean = false,
    val data: CategoryData? = null
)

data class CategoryData(
    val category: RemoteGroupDto? = null
)

data class CollectionsResponse(
    val success: Boolean = false,
    val data: CollectionsData? = null
)

data class CollectionsData(
    val collections: List<RemoteGroupDto> = emptyList()
)

data class CollectionResponse(
    val success: Boolean = false,
    val data: CollectionData? = null
)

data class CollectionData(
    val collection: RemoteGroupDto? = null
)

data class GroupLevelsResponse(
    val success: Boolean = false,
    val data: GroupLevelsData? = null
)

data class GroupLevelsData(
    val levels: List<RemoteLevelSummaryDto> = emptyList()
)

data class LevelDetailResponse(
    val success: Boolean = false,
    val data: LevelDetailData? = null
)

data class LevelDetailData(
    val level: RemoteLevelDetailDto? = null
)

data class RealmsResponse(
    val success: Boolean = false,
    val data: RealmsData? = null
)

data class RealmsData(
    val realms: List<RemoteRealmDto> = emptyList()
)

data class RealmResponse(
    val success: Boolean = false,
    val data: RealmData? = null
)

data class RealmData(
    val realm: RemoteRealmDto? = null
)

data class AchievementsResponse(
    val success: Boolean = false,
    val data: AchievementsData? = null
)

data class AchievementsData(
    val achievements: List<RemoteAchievementDto> = emptyList()
)

data class AchievementResponse(
    val success: Boolean = false,
    val data: AchievementData? = null
)

data class AchievementData(
    val achievement: RemoteAchievementDto? = null
)
