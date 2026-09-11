package com.pixlory.color.by.number.data.remote

import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog

object RemoteRealmMapper {
    fun toRealm(dto: RemoteRealmDto, assetLoader: RemoteAssetLoader): Realm {
        val bundledRealm = RealmCatalog.findById(dto.id)
        return Realm(
            id = dto.id,
            name = dto.name,
            nameRes = bundledRealm?.nameRes,
            animationRes = bundledRealm?.animationRes ?: 0,
            thumbnailRes = bundledRealm?.thumbnailRes ?: 0,
            unlockCost = dto.unlockCost,
            sortOrder = dto.sortOrder ?: Int.MAX_VALUE,
            animationUrl = assetLoader.resolveUrl(dto.animationPath),
            previewImageUrl = assetLoader.resolveUrl(dto.previewImagePath),
            isPremium = dto.isPremium == true
        )
    }

    fun sortRealms(realms: List<RemoteRealmDto>): List<RemoteRealmDto> =
        realms.sortedWith(compareBy<RemoteRealmDto> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.name })
}
