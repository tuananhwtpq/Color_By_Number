package com.pixlory.color.by.number.data

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.data.localization.ServerContentTextResolver

/**
 * Một "cõi" (realm) hiển thị ở tab Color Realm. Nền là Lottie animation trong res/raw chứ
 * không phải ảnh tĩnh, nên khi cần ảnh (lưu về máy) phải render một frame ra bitmap.
 */
data class Realm(
    val id: String,
    val name: String? = null,
    @StringRes val nameRes: Int? = null,
    @RawRes val animationRes: Int,
    @DrawableRes val thumbnailRes: Int,
    val unlockCost: Int,
    val sortOrder: Int,
    val animationUrl: String? = null,
    val previewImageUrl: String? = null,
    val isPremium: Boolean = false,
) {
    fun displayName(context: Context): String =
        ServerContentTextResolver.realmTitle(context, id, name)
            ?: nameRes?.let(context::getString)
            ?: name.orEmpty()
}

object RealmCatalog {

    val realms: List<Realm> = listOf(
        Realm(
            id = "sakura_haven",
            nameRes = R.string.realm_sakura_haven,
            animationRes = R.raw.sakura_heaven,
            thumbnailRes = R.drawable.sakura_haven_thumbnail,
            unlockCost = 0,
            sortOrder = 1,
        ),
        Realm(
            id = "crystal_creek",
            nameRes = R.string.realm_crystal_creek,
            animationRes = R.raw.crystal_creek,
            thumbnailRes = R.drawable.crystal_creek_thumbnail,
            unlockCost = 10,
            sortOrder = 2,
        ),
        Realm(
            id = "sky_castle",
            nameRes = R.string.realm_sky_castle,
            animationRes = R.raw.sky_castle,
            thumbnailRes = R.drawable.sky_castle_thumbnail,
            unlockCost = 20,
            sortOrder = 3,
        ),
        Realm(
            id = "snowy_peaks",
            nameRes = R.string.realm_snowy_peaks,
            animationRes = R.raw.snowy_peaks,
            thumbnailRes = R.drawable.snowy_peaks_thumbnail,
            unlockCost = 30,
            sortOrder = 4,
        ),
        Realm(
            id = "starlit_forest",
            nameRes = R.string.realm_starlit_forest,
            animationRes = R.raw.starlist_forest,
            thumbnailRes = R.drawable.starlist_forest_thumbnail,
            unlockCost = 40,
            sortOrder = 5,
        ),
        Realm(
            id = "treasure_cove",
            nameRes = R.string.realm_treasure_cove,
            animationRes = R.raw.treasure_cove,
            thumbnailRes = R.drawable.treasure_cove_thumbnail,
            unlockCost = 50,
            sortOrder = 6,
        ),
    ).sortedBy { it.sortOrder }

    val default: Realm get() = realms.first()

    fun normalizeId(id: String): String = id.replace('-', '_')

    fun idsMatch(firstId: String?, secondId: String?): Boolean =
        firstId != null && secondId != null && normalizeId(firstId) == normalizeId(secondId)

    fun findById(id: String?): Realm? = id?.let(::normalizeId)?.let { normalizedId ->
        realms.firstOrNull { normalizeId(it.id) == normalizedId }
    }
}
