package com.example.baseproject.data.remote

import com.example.baseproject.data.Realm

object RemoteRealmMapper {
    fun toRealm(dto: RemoteRealmDto, assetLoader: RemoteAssetLoader): Realm =
        Realm(
            id = dto.id,
            name = dto.name,
            animationRes = 0,
            thumbnailRes = 0,
            unlockCost = dto.unlockCost,
            sortOrder = dto.sortOrder ?: Int.MAX_VALUE,
            animationUrl = assetLoader.resolveUrl(dto.animationPath),
            previewImageUrl = assetLoader.resolveUrl(dto.previewImagePath),
            isPremium = dto.isPremium == true
        )

    fun sortRealms(realms: List<RemoteRealmDto>): List<RemoteRealmDto> =
        realms.sortedWith(compareBy<RemoteRealmDto> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.name })
}
