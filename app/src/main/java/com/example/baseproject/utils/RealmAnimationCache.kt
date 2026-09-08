package com.example.baseproject.utils

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
}
