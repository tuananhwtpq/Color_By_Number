package com.pixlory.color.by.number.data.repository

import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog

class LocalRealmRepositoryImpl : RealmRepository {
    override suspend fun loadRealms(): List<Realm> = RealmCatalog.realms

    override suspend fun loadRealm(realmId: String): Realm? =
        RealmCatalog.findById(realmId)
}
