package com.example.baseproject.data

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.baseproject.R
import com.example.baseproject.utils.Constants

/**
 * Điều kiện để mở khoá một achievement. Chia làm hai nhóm:
 *
 * - Suy ra từ việc đã tô xong tranh nào ([ArtworksCompleted], [ArtworkInCategory],
 *   [CollectionCompleted]): chỉ cần một danh sách các bức đã hoàn thành là tính được tất cả,
 *   không phải đọc lại assets.
 * - Đếm riêng ([ConsecutiveDaysOpened], [RealmsUnlocked], [HintsUsed], [DailyArtworksCompleted]):
 *   những hành động không để lại dấu vết nào khác nên phải tự ghi lại.
 */
sealed interface AchievementRule {
    /**
     * Số ngày khác nhau người dùng mở app. Qua ngày mới thì tăng tiếp, không reset khi bỏ lỡ ngày.
     */
    data object ConsecutiveDaysOpened : AchievementRule

    /** Số bức tranh KHÁC NHAU đã tô xong. */
    data object ArtworksCompleted : AchievementRule

    /** Tô xong ít nhất một bức thuộc [assetCategory] — tên thư mục trong assets. */
    data class ArtworkInCategory(val assetCategory: String) : AchievementRule

    /** Tô xong trọn bộ một collection. [collectionName] là tên thư mục trong assets/Collection. */
    data class CollectionCompleted(val collectionName: String) : AchievementRule {
        val assetPath: String get() = "${Constants.ASSET_COLLECTION_ROOT}/$collectionName"
    }

    data object RealmsUnlocked : AchievementRule

    data object HintsUsed : AchievementRule

    data object DailyArtworksCompleted : AchievementRule
}

/**
 * Định nghĩa tĩnh của một achievement. Phần tiến độ và thời điểm mở khoá do
 * AchievementRepository tính, xem [Achievement].
 */
data class AchievementDefinition(
    val id: String,
    @StringRes val titleRes: Int? = null,
    @StringRes val descriptionRes: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val targetCount: Int,
    val rule: AchievementRule,
    /**
     * Huy hiệu lúc chưa đạt và lúc đã đạt. Để null nghĩa là chưa có art — UI sẽ dùng ảnh tạm,
     * nhờ vậy nhìn vào file này là biết ngay achievement nào còn thiếu ảnh.
     */
    @DrawableRes val iconRes: Int? = null,
    @DrawableRes val iconCompletedRes: Int? = null,
    val iconUrl: String? = null,
    val iconCompletedUrl: String? = null,
    val sortOrder: Int = Int.MAX_VALUE,
    val isPremium: Boolean = false
) {
    fun titleText(context: Context): String =
        title ?: titleRes?.let(context::getString) ?: id

    fun descriptionText(context: Context): String =
        description ?: descriptionRes?.let(context::getString).orEmpty()
}

object AchievementCatalog {

    val definitions: List<AchievementDefinition> = listOf(
        AchievementDefinition(
            id = "first_splash",
            titleRes = R.string.achievement_first_splash_title,
            descriptionRes = R.string.achievement_first_splash_desc,
            targetCount = 1,
            rule = AchievementRule.ConsecutiveDaysOpened,
            iconRes = R.drawable.first_splash,
            iconCompletedRes = R.drawable.first_splash_done
        ),
        AchievementDefinition(
            id = "color_explorer",
            titleRes = R.string.achievement_color_explorer_title,
            descriptionRes = R.string.achievement_color_explorer_desc,
            targetCount = 3,
            rule = AchievementRule.ConsecutiveDaysOpened,
            iconRes = R.drawable.color_explorer,
            iconCompletedRes = R.drawable.color_explorer_done
        ),
        AchievementDefinition(
            id = "weekly_artist",
            titleRes = R.string.achievement_weekly_artist_title,
            descriptionRes = R.string.achievement_weekly_artist_desc,
            targetCount = 7,
            rule = AchievementRule.ConsecutiveDaysOpened,
            iconRes = R.drawable.weekly_artist,
            iconCompletedRes = R.drawable.weekly_artist_done
        ),
        AchievementDefinition(
            id = "color_enthusiast",
            titleRes = R.string.achievement_color_enthusiast_title,
            descriptionRes = R.string.achievement_color_enthusiast_desc,
            targetCount = 14,
            rule = AchievementRule.ConsecutiveDaysOpened,
            iconRes = R.drawable.color_enthusiast,
            iconCompletedRes = R.drawable.color_enthusiast_done
        ),
        AchievementDefinition(
            id = "creative_soul",
            titleRes = R.string.achievement_creative_soul_title,
            descriptionRes = R.string.achievement_creative_soul_desc,
            targetCount = 30,
            rule = AchievementRule.ConsecutiveDaysOpened,
            iconRes = R.drawable.creative_soul,
            iconCompletedRes = R.drawable.creative_soul_done
        ),
        AchievementDefinition(
            id = "master_colorist",
            titleRes = R.string.achievement_master_colorist_title,
            descriptionRes = R.string.achievement_master_colorist_desc,
            targetCount = 60,
            rule = AchievementRule.ConsecutiveDaysOpened,
            iconRes = R.drawable.master_colorist,
            iconCompletedRes = R.drawable.master_colorist_done
        ),
        AchievementDefinition(
            id = "color_legend",
            titleRes = R.string.achievement_color_legend_title,
            descriptionRes = R.string.achievement_color_legend_desc,
            targetCount = 100,
            rule = AchievementRule.ConsecutiveDaysOpened
        ),
        AchievementDefinition(
            id = "eternal_artist",
            titleRes = R.string.achievement_eternal_artist_title,
            descriptionRes = R.string.achievement_eternal_artist_desc,
            targetCount = 365,
            rule = AchievementRule.ConsecutiveDaysOpened
        ),
        AchievementDefinition(
            id = "first_masterpiece",
            titleRes = R.string.achievement_first_masterpiece_title,
            descriptionRes = R.string.achievement_first_masterpiece_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworksCompleted,
            iconRes = R.drawable.first_masterpiece,
            iconCompletedRes = R.drawable.first_masterpiece_done
        ),
        AchievementDefinition(
            id = "rising_artist",
            titleRes = R.string.achievement_rising_artist_title,
            descriptionRes = R.string.achievement_rising_artist_desc,
            targetCount = 5,
            rule = AchievementRule.ArtworksCompleted
        ),
        AchievementDefinition(
            id = "gallery_builder",
            titleRes = R.string.achievement_gallery_builder_title,
            descriptionRes = R.string.achievement_gallery_builder_desc,
            targetCount = 10,
            rule = AchievementRule.ArtworksCompleted
        ),
        AchievementDefinition(
            id = "art_collector",
            titleRes = R.string.achievement_art_collector_title,
            descriptionRes = R.string.achievement_art_collector_desc,
            targetCount = 15,
            rule = AchievementRule.ArtworksCompleted
        ),
        AchievementDefinition(
            id = "color_virtuoso",
            titleRes = R.string.achievement_color_virtuoso_title,
            descriptionRes = R.string.achievement_color_virtuoso_desc,
            targetCount = 25,
            rule = AchievementRule.ArtworksCompleted
        ),
        AchievementDefinition(
            id = "master_of_colors",
            titleRes = R.string.achievement_master_of_colors_title,
            descriptionRes = R.string.achievement_master_of_colors_desc,
            targetCount = 40,
            rule = AchievementRule.ArtworksCompleted
        ),
        AchievementDefinition(
            id = "anime_fan",
            titleRes = R.string.achievement_anime_fan_title,
            descriptionRes = R.string.achievement_anime_fan_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Manga")
        ),
        AchievementDefinition(
            id = "inner_peace",
            titleRes = R.string.achievement_inner_peace_title,
            descriptionRes = R.string.achievement_inner_peace_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Meditate")
        ),
        AchievementDefinition(
            id = "summer_lover",
            titleRes = R.string.achievement_summer_lover_title,
            descriptionRes = R.string.achievement_summer_lover_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Summer")
        ),
        AchievementDefinition(
            id = "animal_friend",
            titleRes = R.string.achievement_animal_friend_title,
            descriptionRes = R.string.achievement_animal_friend_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Animal")
        ),
        AchievementDefinition(
            id = "festival_joy",
            titleRes = R.string.achievement_festival_joy_title,
            descriptionRes = R.string.achievement_festival_joy_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Festival")
        ),
        AchievementDefinition(
            id = "food_lover",
            titleRes = R.string.achievement_food_lover_title,
            descriptionRes = R.string.achievement_food_lover_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Foods")
        ),
        AchievementDefinition(
            id = "story_dreamer",
            titleRes = R.string.achievement_story_dreamer_title,
            descriptionRes = R.string.achievement_story_dreamer_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Fairy Tail")
        ),
        AchievementDefinition(
            id = "bloom_seeker",
            titleRes = R.string.achievement_bloom_seeker_title,
            descriptionRes = R.string.achievement_bloom_seeker_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Florals")
        ),
        AchievementDefinition(
            id = "art_enthusiast",
            titleRes = R.string.achievement_art_enthusiast_title,
            descriptionRes = R.string.achievement_art_enthusiast_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Art")
        ),
        AchievementDefinition(
            id = "world_explorer",
            titleRes = R.string.achievement_world_explorer_title,
            descriptionRes = R.string.achievement_world_explorer_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Travel")
        ),
        AchievementDefinition(
            id = "playful_spirit",
            titleRes = R.string.achievement_playful_spirit_title,
            descriptionRes = R.string.achievement_playful_spirit_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Cartoon")
        ),
        AchievementDefinition(
            id = "nature_explorer",
            titleRes = R.string.achievement_nature_explorer_title,
            descriptionRes = R.string.achievement_nature_explorer_desc,
            targetCount = 1,
            rule = AchievementRule.ArtworkInCategory("Scenery")
        ),
        AchievementDefinition(
            id = "festival_of_fortune",
            titleRes = R.string.achievement_festival_of_fortune_title,
            descriptionRes = R.string.achievement_festival_of_fortune_desc,
            targetCount = 10,
            rule = AchievementRule.CollectionCompleted("Lunar New Year")
        ),
        AchievementDefinition(
            id = "racing_spirit",
            titleRes = R.string.achievement_racing_spirit_title,
            descriptionRes = R.string.achievement_racing_spirit_desc,
            targetCount = 6,
            rule = AchievementRule.CollectionCompleted("Racing Legends")
        ),
        AchievementDefinition(
            id = "star_chaser",
            titleRes = R.string.achievement_star_chaser_title,
            descriptionRes = R.string.achievement_star_chaser_desc,
            targetCount = 6,
            rule = AchievementRule.CollectionCompleted("Starlight Journey")
        ),
        AchievementDefinition(
            id = "purrfect_companion",
            titleRes = R.string.achievement_purrfect_companion_title,
            descriptionRes = R.string.achievement_purrfect_companion_desc,
            targetCount = 8,
            rule = AchievementRule.CollectionCompleted("Cat moments")
        ),
        AchievementDefinition(
            id = "easter_celebration",
            titleRes = R.string.achievement_easter_celebration_title,
            descriptionRes = R.string.achievement_easter_celebration_desc,
            targetCount = 4,
            rule = AchievementRule.CollectionCompleted("Happy Easter Day")
        ),
        AchievementDefinition(
            id = "glass_artisan",
            titleRes = R.string.achievement_glass_artisan_title,
            descriptionRes = R.string.achievement_glass_artisan_desc,
            targetCount = 10,
            rule = AchievementRule.CollectionCompleted("Light Through Glass")
        ),
        AchievementDefinition(
            id = "sweet_moments",
            titleRes = R.string.achievement_sweet_moments_title,
            descriptionRes = R.string.achievement_sweet_moments_desc,
            targetCount = 8,
            rule = AchievementRule.CollectionCompleted("Sweet Paradise")
        ),
        AchievementDefinition(
            id = "realm_explorer",
            titleRes = R.string.achievement_realm_explorer_title,
            descriptionRes = R.string.achievement_realm_explorer_desc,
            targetCount = 1,
            rule = AchievementRule.RealmsUnlocked
        ),
        AchievementDefinition(
            id = "growing_world",
            titleRes = R.string.achievement_growing_world_title,
            descriptionRes = R.string.achievement_growing_world_desc,
            targetCount = 3,
            rule = AchievementRule.RealmsUnlocked
        ),
        AchievementDefinition(
            id = "realm_guardian",
            titleRes = R.string.achievement_realm_guardian_title,
            descriptionRes = R.string.achievement_realm_guardian_desc,
            targetCount = 6,
            rule = AchievementRule.RealmsUnlocked
        ),
        AchievementDefinition(
            id = "first_clue",
            titleRes = R.string.achievement_first_clue_title,
            descriptionRes = R.string.achievement_first_clue_desc,
            targetCount = 1,
            rule = AchievementRule.HintsUsed
        ),
        AchievementDefinition(
            id = "hint_hunter",
            titleRes = R.string.achievement_hint_hunter_title,
            descriptionRes = R.string.achievement_hint_hunter_desc,
            targetCount = 5,
            rule = AchievementRule.HintsUsed
        ),
        AchievementDefinition(
            id = "smart_solver",
            titleRes = R.string.achievement_smart_solver_title,
            descriptionRes = R.string.achievement_smart_solver_desc,
            targetCount = 10,
            rule = AchievementRule.HintsUsed
        ),
        AchievementDefinition(
            id = "guided_artist",
            titleRes = R.string.achievement_guided_artist_title,
            descriptionRes = R.string.achievement_guided_artist_desc,
            targetCount = 20,
            rule = AchievementRule.HintsUsed
        ),
        AchievementDefinition(
            id = "hint_master",
            titleRes = R.string.achievement_hint_master_title,
            descriptionRes = R.string.achievement_hint_master_desc,
            targetCount = 30,
            rule = AchievementRule.HintsUsed
        ),
        AchievementDefinition(
            id = "daily_starter",
            titleRes = R.string.achievement_daily_starter_title,
            descriptionRes = R.string.achievement_daily_starter_desc,
            targetCount = 3,
            rule = AchievementRule.DailyArtworksCompleted
        ),
        AchievementDefinition(
            id = "daily_habit",
            titleRes = R.string.achievement_daily_habit_title,
            descriptionRes = R.string.achievement_daily_habit_desc,
            targetCount = 7,
            rule = AchievementRule.DailyArtworksCompleted
        ),
        AchievementDefinition(
            id = "daily_artist",
            titleRes = R.string.achievement_daily_artist_title,
            descriptionRes = R.string.achievement_daily_artist_desc,
            targetCount = 14,
            rule = AchievementRule.DailyArtworksCompleted
        ),
        AchievementDefinition(
            id = "daily_devotion",
            titleRes = R.string.achievement_daily_devotion_title,
            descriptionRes = R.string.achievement_daily_devotion_desc,
            targetCount = 25,
            rule = AchievementRule.DailyArtworksCompleted
        ),
        AchievementDefinition(
            id = "daily_champion",
            titleRes = R.string.achievement_daily_champion_title,
            descriptionRes = R.string.achievement_daily_champion_desc,
            targetCount = 40,
            rule = AchievementRule.DailyArtworksCompleted
        )
    )
}
