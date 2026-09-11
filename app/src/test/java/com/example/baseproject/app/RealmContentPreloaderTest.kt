package com.pixlory.color.by.number.app

import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.repository.RealmRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RealmContentPreloaderTest {

    @Test
    fun concurrentRequestsForTheSameRealmShareMetadataAndAnimationWarmUp() = runBlocking {
        val repository = FakeRealmRepository(remoteRealm())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localAnimationGate = CompletableDeferred<Unit>()
        var localAnimationLoads = 0
        var remoteAnimationLoads = 0

        try {
            val preloader = RealmContentPreloader(
                realmRepository = repository,
                scope = scope,
                fallbackRealm = { fallbackRealm() },
                prepareFallbackAnimation = {
                    localAnimationLoads++
                    localAnimationGate.await()
                },
                prepareRemoteAnimation = { remoteAnimationLoads++ },
            )

            val first = preloader.preload("sakura_haven")
            val second = preloader.preload("sakura-haven")

            assertSame(first, second)
            assertEquals(1, repository.loadRealmsCalls)
            assertEquals(1, localAnimationLoads)

            localAnimationGate.complete(Unit)

            assertEquals(remoteRealm(), first.await())
            assertEquals(1, remoteAnimationLoads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedRemoteLoadStillMakesTheBundledFallbackReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fallbackAnimationLoads = 0

        try {
            val preloader = RealmContentPreloader(
                realmRepository = object : RealmRepository {
                    override suspend fun loadRealms(): List<Realm> = error("offline")
                    override suspend fun loadRealm(realmId: String): Realm? = error("not needed")
                },
                scope = scope,
                fallbackRealm = { fallbackRealm() },
                prepareFallbackAnimation = { fallbackAnimationLoads++ },
                prepareRemoteAnimation = { error("Remote animation must not run for fallback") },
            )

            assertEquals(fallbackRealm(), preloader.preload("sakura_haven").await())
            assertEquals(1, fallbackAnimationLoads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedRemoteLoadIsRetriedByTheNextCaller() = runBlocking {
        val repository = object : RealmRepository {
            var loadRealmsCalls = 0

            override suspend fun loadRealms(): List<Realm> {
                loadRealmsCalls++
                if (loadRealmsCalls == 1) error("temporary offline")
                return listOf(remoteRealm())
            }

            override suspend fun loadRealm(realmId: String): Realm? = null
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            val preloader = RealmContentPreloader(
                realmRepository = repository,
                scope = scope,
                fallbackRealm = { fallbackRealm() },
                prepareFallbackAnimation = {},
                prepareRemoteAnimation = {},
            )

            assertEquals(fallbackRealm(), preloader.preload("sakura_haven").await())
            assertEquals(remoteRealm(), preloader.preload("sakura_haven").await())
            assertEquals(2, repository.loadRealmsCalls)
        } finally {
            scope.cancel()
        }
    }

    private class FakeRealmRepository(
        private val realm: Realm,
    ) : RealmRepository {
        var loadRealmsCalls = 0

        override suspend fun loadRealms(): List<Realm> {
            loadRealmsCalls++
            return listOf(realm)
        }

        override suspend fun loadRealm(realmId: String): Realm? = realm
    }

    private companion object {
        fun fallbackRealm() = Realm(
            id = "sakura_haven",
            animationRes = 1,
            thumbnailRes = 1,
            unlockCost = 0,
            sortOrder = 1,
        )

        fun remoteRealm() = fallbackRealm().copy(animationUrl = "https://example.test/sakura.json")
    }
}
