package com.example.baseproject.activities

import android.os.SystemClock
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.TimelapseFrameRenderer
import com.example.baseproject.databinding.ActivityTimelapsePreviewBinding
import com.example.baseproject.utils.setOnUnDoubleClick
import com.example.baseproject.utils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class TimelapsePreviewActivity : BaseActivity<ActivityTimelapsePreviewBinding>(
    ActivityTimelapsePreviewBinding::inflate
) {

    companion object {
        const val EXTRA_CATEGORY = "CATEGORY"
        const val EXTRA_LEVEL_ID = "LEVEL_ID"

        private const val PREVIEW_DURATION_MS = 15_000L
        private const val PREVIEW_FRAME_DELAY_MS = 33L
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private var category: String? = null
    private var levelId: String? = null
    private var renderJob: Job? = null
    private var previewJob: Job? = null
    private var renderer: TimelapseFrameRenderer? = null

    private val onBackPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    override fun initData() {
        category = intent.getStringExtra(EXTRA_CATEGORY)
        levelId = intent.getStringExtra(EXTRA_LEVEL_ID)
        if (category == null || levelId == null) {
            finish()
        }
    }

    override fun initView() {
        onBackPressedDispatcher.addCallback(onBackPressCallback)
        loadTimelapse()
    }

    override fun initActionView() {
        binding.btnSkip.setOnUnDoubleClick {
            finish()
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        previewJob?.cancel()
        renderer?.recycle()
        renderer = null
        super.onDestroy()
    }

    private fun loadTimelapse() {
        val category = category ?: return
        val levelId = levelId ?: return

        binding.progressBar.visibility = View.VISIBLE
        renderJob = lifecycleScope.launch {
            val loadedRenderer = withContext(Dispatchers.Default) {
                val history = appContainer.paintingProgressRepository
                    .loadPaintHistory(category, levelId)
                if (history.isEmpty()) return@withContext null

                val bundle = appContainer.assetLevelRepository.loadLevelBundle(category, levelId)
                TimelapseFrameRenderer(bundle, history)
            }

            if (loadedRenderer == null || loadedRenderer.stepCount == 0) {
                showToast(getString(R.string.timelapse_unavailable))
                finish()
                return@launch
            }

            renderer = loadedRenderer
            binding.progressBar.visibility = View.GONE
            binding.previewView.setFrameBitmap(
                withContext(Dispatchers.Default) {
                    loadedRenderer.renderStep(0)
                }
            )
            startPreview(loadedRenderer)
        }
    }

    private fun startPreview(renderer: TimelapseFrameRenderer) {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var lastStep = -1

            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val progress = (elapsed.toFloat() / PREVIEW_DURATION_MS).coerceIn(0f, 1f)
                val targetStep = (renderer.stepCount * progress).roundToInt()
                    .coerceIn(0, renderer.stepCount)

                if (targetStep != lastStep) {
                    withContext(Dispatchers.Default) {
                        renderer.renderStep(targetStep)
                    }
                    lastStep = targetStep
                    binding.previewView.invalidate()
                }

                if (progress >= 1f) break
                delay(PREVIEW_FRAME_DELAY_MS)
            }
        }
    }
}
