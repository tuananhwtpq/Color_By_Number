package com.example.baseproject.data

import androidx.annotation.RawRes
import androidx.annotation.DrawableRes
import com.example.baseproject.R

/**
 * Một "cõi" (realm) hiển thị ở tab Color Realm. Nền là Lottie animation trong res/raw chứ
 * không phải ảnh tĩnh, nên khi cần ảnh (lưu về máy) phải render một frame ra bitmap.
 */
data class Realm(
    val id: String,
    val name: String,
    @RawRes val animationRes: Int,
    @DrawableRes val thumbnailRes: Int,
    val unlockCost: Int,
    val sortOrder: Int,
)

object RealmCatalog {

    val realms: List<Realm> = listOf(
        Realm(
            id = "sakura_haven",
            name = "Sakura Haven",
            animationRes = R.raw.sakura_heaven,
            thumbnailRes = R.drawable.sakura_haven_thumbnail,
            unlockCost = 0,
            sortOrder = 1,
        ),
        Realm(
            id = "crystal_creek",
            name = "Crystal Creek",
            animationRes = R.raw.crystal_creek,
            thumbnailRes = R.drawable.crystal_creek_thumbnail,
            unlockCost = 10,
            sortOrder = 2,
        ),
        Realm(
            id = "sky_castle",
            name = "Sky Castle",
            animationRes = R.raw.sky_castle,
            thumbnailRes = R.drawable.sky_castle_thumbnail,
            unlockCost = 20,
            sortOrder = 3,
        ),
        Realm(
            id = "snowy_peaks",
            name = "Snowy Peaks",
            animationRes = R.raw.snowy_peaks,
            thumbnailRes = R.drawable.snowy_peaks_thumbnail,
            unlockCost = 30,
            sortOrder = 4,
        ),
        Realm(
            id = "starlit_forest",
            name = "Starlit Forest",
            animationRes = R.raw.starlist_forest,
            thumbnailRes = R.drawable.starlist_forest_thumbnail,
            unlockCost = 40,
            sortOrder = 5,
        ),
        Realm(
            id = "treasure_cove",
            name = "Treasure Cove",
            animationRes = R.raw.treasure_cove,
            thumbnailRes = R.drawable.treasure_cove_thumbnail,
            unlockCost = 50,
            sortOrder = 6,
        ),
    ).sortedBy { it.sortOrder }

    val default: Realm get() = realms.first()

    fun findById(id: String?): Realm? = realms.firstOrNull { it.id == id }
}
