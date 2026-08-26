package com.example.baseproject.data.repository

import android.util.Log
import com.example.baseproject.data.Realm
import com.example.baseproject.data.remote.PixcolorApi
import com.example.baseproject.data.remote.RemoteApiException
import com.example.baseproject.data.remote.RemoteAssetLoader
import com.example.baseproject.data.remote.RemoteRealmMapper
import com.example.baseproject.data.remote.requireSuccessfulBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RemoteRealmRepositoryImpl(
    private val api: PixcolorApi,
    private val assetLoader: RemoteAssetLoader,
    private val fallback: RealmRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RealmRepository {

    override suspend fun loadRealms(): List<Realm> =
        withFallback("loadRealms", fallbackAction = { loadRealms() }) {
            val response = api.realms()
                .requireSuccessfulBody("/api/v1/realms")
                .takeIf { it.success }
                ?: throw RemoteApiException("Realms returned success=false")

            val realms = RemoteRealmMapper.sortRealms(response.data?.realms.orEmpty())
            if (realms.isEmpty()) {
                throw RemoteApiException("Realms response did not include active realms")
            }
            realms.map { RemoteRealmMapper.toRealm(it, assetLoader) }
        }

    override suspend fun loadRealm(realmId: String): Realm? =
        withFallback("loadRealm($realmId)", fallbackAction = { loadRealm(realmId) }) {
            val realm = loadRemoteRealm(realmId)

            RemoteRealmMapper.toRealm(realm, assetLoader)
        }

    private suspend fun loadRemoteRealm(realmId: String) =
        realmIdVariants(realmId).firstNotNullOfOrNull { candidateId ->
            runCatching {
                api.realm(candidateId)
                    .requireSuccessfulBody("/api/v1/realms/$candidateId")
                    .takeIf { it.success }
                    ?.data
                    ?.realm
            }.getOrNull()
        } ?: throw RemoteApiException("Realm $realmId is missing")

    private fun realmIdVariants(realmId: String): List<String> {
        val dashedId = realmId.replace('_', '-')
        val underscoredId = realmId.replace('-', '_')
        return listOf(realmId, dashedId, underscoredId).distinct()
    }

    private suspend fun <T> withFallback(
        operation: String,
        fallbackAction: (suspend RealmRepository.() -> T)?,
        remoteAction: suspend () -> T
    ): T = withContext(ioDispatcher) {
        runCatching { remoteAction() }
            .getOrElse { error ->
                Log.w(TAG, "$operation failed; falling back to local data", error)
                val fallbackRepository = fallback
                if (fallbackRepository != null && fallbackAction != null) {
                    fallbackRepository.fallbackAction()
                } else {
                    throw error
                }
            }
    }

    private companion object {
        const val TAG = "RemoteRealmRepo"
    }
}
