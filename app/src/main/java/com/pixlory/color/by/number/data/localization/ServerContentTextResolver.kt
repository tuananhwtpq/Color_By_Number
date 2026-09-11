package com.pixlory.color.by.number.data.localization

import android.content.Context
import androidx.annotation.StringRes
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.data.AchievementCatalog
import com.pixlory.color.by.number.data.RealmCatalog

/**
 * Resolves the presentation text for the server catalogue without ever using translated text as
 * an identifier. New server IDs deliberately fall back to the English string from the response
 * until a later app update adds their translation.
 */
object ServerContentTextResolver {

    fun categoryTitle(context: Context, categoryId: String, serverEnglish: String): String =
        context.localizedOrFallback(categoryTitleRes[categoryId.normalizedId()], serverEnglish)
            ?: serverEnglish

    fun collectionTitle(context: Context, collectionId: String, serverEnglish: String): String =
        context.localizedOrFallback(collectionTitleRes[collectionId.normalizedId()], serverEnglish)
            ?: serverEnglish

    fun collectionDescription(
        context: Context,
        collectionId: String,
        serverEnglish: String?
    ): String? = context.localizedOrFallback(
        collectionDescriptionRes[collectionId.normalizedId()],
        serverEnglish
    )

    fun realmTitle(context: Context, realmId: String, serverEnglish: String?): String? {
        val localRealm = RealmCatalog.findById(realmId.normalizedId())
        return context.localizedOrFallback(localRealm?.nameRes, serverEnglish)
    }

    fun achievementTitle(context: Context, achievementId: String, serverEnglish: String?): String? {
        val localDefinition = AchievementCatalog.definitions.firstOrNull {
            it.id == achievementId.normalizedId()
        }
        return context.localizedOrFallback(localDefinition?.titleRes, serverEnglish)
    }

    fun achievementDescription(
        context: Context,
        achievementId: String,
        serverEnglish: String?
    ): String? {
        val localDefinition = AchievementCatalog.definitions.firstOrNull {
            it.id == achievementId.normalizedId()
        }
        return context.localizedOrFallback(localDefinition?.descriptionRes, serverEnglish)
    }

    private fun Context.localizedOrFallback(
        @StringRes resourceId: Int?,
        fallback: String?
    ): String? = resourceId?.let(::getString) ?: fallback

    private fun String.normalizedId(): String = replace('-', '_')

    private val categoryTitleRes = mapOf(
        "animal" to R.string.server_category_animal_title,
        "cartoon" to R.string.server_category_cartoon_title,
        "fairy_tail" to R.string.server_category_fairy_tail_title,
        "manga" to R.string.server_category_manga_title,
        "art" to R.string.server_category_art_title,
        "festival" to R.string.server_category_festival_title,
        "florals" to R.string.server_category_florals_title,
        "foods" to R.string.server_category_foods_title,
        "meditate" to R.string.server_category_meditate_title,
        "summer" to R.string.server_category_summer_title,
        "travel_wonders" to R.string.server_category_travel_wonders_title,
        "scenery" to R.string.server_category_scenery_title,
    )

    private val collectionTitleRes = mapOf(
        "cat_moments" to R.string.server_collection_cat_moments_title,
        "sweet_paradise" to R.string.server_collection_sweet_paradise_title,
        "starlight_journey" to R.string.server_collection_starlight_journey_title,
        "happy_easter_day" to R.string.server_collection_happy_easter_day_title,
        "light_through_glass" to R.string.server_collection_light_through_glass_title,
        "lunar_new_year" to R.string.server_collection_lunar_new_year_title,
        "racing_legends" to R.string.server_collection_racing_legends_title,
    )

    private val collectionDescriptionRes = mapOf(
        "cat_moments" to R.string.server_collection_cat_moments_description,
        "sweet_paradise" to R.string.server_collection_sweet_paradise_description,
        "starlight_journey" to R.string.server_collection_starlight_journey_description,
        "happy_easter_day" to R.string.server_collection_happy_easter_day_description,
        "light_through_glass" to R.string.server_collection_light_through_glass_description,
        "lunar_new_year" to R.string.server_collection_lunar_new_year_description,
        "racing_legends" to R.string.server_collection_racing_legends_description,
    )
}
