package com.pixlory.color.by.number.data.remote

import com.pixlory.color.by.number.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRealmMapperTest {

    @Test
    fun knownRemoteRealmKeepsBundledAnimationAsFallback() {
        val remoteRealm = RemoteRealmDto(
            id = "sakura-haven",
            name = "Sakura Haven",
            animationPath = "/uploads/sakura.json"
        )

        val realm = RemoteRealmMapper.toRealm(
            remoteRealm,
            RemoteAssetLoader(baseUrl = "https://example.com/")
        )

        assertEquals(R.raw.sakura_heaven, realm.animationRes)
    }
}
