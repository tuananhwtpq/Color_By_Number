package com.example.baseproject.data.repository

import com.example.baseproject.data.Realm

interface RealmRepository {
    suspend fun loadRealms(): List<Realm>
    suspend fun loadRealm(realmId: String): Realm?
}
