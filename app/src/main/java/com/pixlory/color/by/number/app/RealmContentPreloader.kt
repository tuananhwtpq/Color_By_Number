package com.pixlory.color.by.number.app

import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.data.repository.RealmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Shares one selected Realm's metadata and animation warm-up for the whole process. */
class RealmContentPreloader(
    private val realmRepository: RealmRepository,
    private val scope: CoroutineScope,
    private val fallbackRealm: (String) -> Realm? = RealmCatalog::findById,
    private val prepareFallbackAnimation: suspend (Realm) -> Unit,
    private val prepareRemoteAnimation: suspend (Realm) -> Unit,
) {
    private val lock = Any()
    private val preloadsByRealmId = mutableMapOf<String, Deferred<Realm>>()

    fun preload(realmId: String): Deferred<Realm> {
        val normalizedId = RealmCatalog.normalizeId(realmId)
        return synchronized(lock) {
            preloadsByRealmId[normalizedId] ?: createPreload(normalizedId).also {
                preloadsByRealmId[normalizedId] = it
                it.start()
            }
        }
    }

    private fun createPreload(realmId: String): Deferred<Realm> = scope.async(start = CoroutineStart.LAZY) {
        val fallback = fallbackRealm(realmId) ?: RealmCatalog.default
        coroutineScope {
            val fallbackAnimation = async {
                runCatching { prepareFallbackAnimation(fallback) }
            }
            val resolvedRemoteRealm = runCatching { resolveRemoteRealm(realmId, fallback) }
            if (resolvedRemoteRealm.isFailure) {
                synchronized(lock) { preloadsByRealmId.remove(realmId) }
            }
            val resolvedRealm = resolvedRemoteRealm.getOrDefault(fallback)
            val remoteAnimation = async {
                runCatching { prepareRemoteAnimation(resolvedRealm) }
            }

            fallbackAnimation.await()
            remoteAnimation.await()
            resolvedRealm
        }
    }

    private suspend fun resolveRemoteRealm(realmId: String, fallback: Realm): Realm {
        val realms = realmRepository.loadRealms()
        return realms.firstOrNull { RealmCatalog.idsMatch(it.id, realmId) }
            ?: realmRepository.loadRealm(realmId)
            ?: realms.firstOrNull { RealmCatalog.idsMatch(it.id, fallback.id) }
            ?: fallback
    }
}
