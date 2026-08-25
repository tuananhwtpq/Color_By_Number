package com.example.baseproject.utils

import android.content.Context
import androidx.annotation.RawRes
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object RealmAnimationCache {

    suspend fun loadComposition(
        context: Context,
        @RawRes animationRes: Int,
    ): LottieComposition = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        LottieCompositionFactory
            .fromRawResSync(appContext, animationRes)
            .value ?: throw IOException("Cannot load realm animation $animationRes")
    }
}
