package com.pixlory.color.by.number.utils

import android.content.Context
import androidx.annotation.RawRes
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object RealmAnimationCache {

    private val compositions = ConcurrentHashMap<Int, LottieComposition>()
    private val remoteCompositions = ConcurrentHashMap<String, LottieComposition>()

    suspend fun loadComposition(
        context: Context,
        @RawRes animationRes: Int,
    ): LottieComposition = compositions[animationRes] ?: withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val composition = LottieCompositionFactory
            .fromRawResSync(appContext, animationRes)
            .value ?: throw IOException("Cannot load realm animation $animationRes")
        compositions.putIfAbsent(animationRes, composition) ?: composition
    }

    suspend fun loadRemoteComposition(
        context: Context,
        animationUrl: String,
    ): LottieComposition = remoteCompositions[animationUrl] ?: withContext(Dispatchers.IO) {
        val composition = LottieCompositionFactory
            .fromUrlSync(context.applicationContext, animationUrl)
            .value ?: throw IOException("Cannot load realm animation from $animationUrl")
        remoteCompositions.putIfAbsent(animationUrl, composition) ?: composition
    }
}
