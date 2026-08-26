package com.example.baseproject.data.repository

import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog

class LocalRealmRepositoryImpl : RealmRepository {
    override suspend fun loadRealms(): List<Realm> = RealmCatalog.realms

    override suspend fun loadRealm(realmId: String): Realm? =
        RealmCatalog.findById(realmId)
}
