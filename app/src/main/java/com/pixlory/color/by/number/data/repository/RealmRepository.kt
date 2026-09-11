package com.pixlory.color.by.number.data.repository

import com.pixlory.color.by.number.data.Realm

interface RealmRepository {
    suspend fun loadRealms(): List<Realm>
    suspend fun loadRealm(realmId: String): Realm?
}
