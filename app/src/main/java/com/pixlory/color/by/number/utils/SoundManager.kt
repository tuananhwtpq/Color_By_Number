package com.pixlory.color.by.number.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R

/**
 * Owns all in-app audio. Callers describe the current screen or semantic event;
 * this class decides whether it is enabled and which Android player to use.
 */
class SoundManager(context: Context) {

    private val appContext = context.applicationContext
    private var currentScene = SoundScene.SILENT
    private var currentMusicResId: Int? = null
    private var musicPlayer: MediaPlayer? = null

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedEffectIds = mutableSetOf<Int>()
    private val effectIds: Map<SoundEffect, Int>

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedEffectIds += sampleId
        }
        effectIds = SoundEffect.entries.associateWith { effect ->
            soundPool.load(appContext, effect.resourceId, 1)
        }
    }

    fun onSceneResumed(scene: SoundScene) {
        currentScene = scene
        syncMusic()
    }

    fun onScenePaused(scene: SoundScene) {
        if (currentScene == scene) {
            musicPlayer?.takeIf { it.isPlaying }?.pause()
        }
    }

    fun setBackgroundMusicEnabled(enabled: Boolean) {
        SharedPrefManager.isBackgroundMusicEnabled = enabled
        syncMusic()
    }

    fun setSoundEffectsEnabled(enabled: Boolean) {
        SharedPrefManager.isSoundEffectsEnabled = enabled
    }

    fun play(effect: SoundEffect) {
        if (!SharedPrefManager.isSoundEffectsEnabled || currentScene == SoundScene.SILENT) return

        val sampleId = effectIds[effect] ?: return
        if (sampleId !in loadedEffectIds) return
        soundPool.play(sampleId, EFFECT_VOLUME, EFFECT_VOLUME, 1, 0, 1f)
    }

    fun release() {
        musicPlayer?.release()
        musicPlayer = null
        currentMusicResId = null
        soundPool.release()
    }

    private fun syncMusic() {
        val targetResId = if (!SharedPrefManager.isBackgroundMusicEnabled) {
            null
        } else {
            when (currentScene) {
                SoundScene.HOME -> R.raw.home
                SoundScene.DRAWING -> R.raw.draw
                SoundScene.SILENT -> null
            }
        }

        if (targetResId == null) {
            musicPlayer?.takeIf { it.isPlaying }?.pause()
            return
        }

        if (currentMusicResId != targetResId) {
            musicPlayer?.release()
            musicPlayer = MediaPlayer.create(appContext, targetResId)?.apply {
                isLooping = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }
            currentMusicResId = targetResId
        }

        musicPlayer?.takeIf { !it.isPlaying }?.start()
    }

    private companion object {
        const val EFFECT_VOLUME = 1f
    }
}

enum class SoundScene {
    HOME,
    DRAWING,
    SILENT
}

enum class SoundEffect(val resourceId: Int) {
    CLICK(R.raw.click_button),
    PALETTE(R.raw.click_number),
    HINT(R.raw.hint),
    COMPLETION(R.raw.complete_picture)
}

fun Context.soundManagerOrNull(): SoundManager? =
    applicationContext.let { it as? MyApplication }?.soundManager
