package com.pixlory.color.by.number.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import com.pixlory.color.by.number.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LottieFrameRendererTest {

    @Test
    fun loadedCompositionCanBeRenderedWithoutResourceId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val composition = requireNotNull(
            LottieCompositionFactory.fromRawResSync(context, R.raw.sakura_heaven).value
        )

        val renderedFrame = LottieFrameRenderer.renderFrame(
            composition = composition,
            progress = 0.5f,
            targetAspectRatio = 9f / 16f
        )

        renderedFrame.getOrNull()?.recycle()
        assertTrue(renderedFrame.isSuccess)
    }
}
