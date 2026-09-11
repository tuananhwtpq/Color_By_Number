package com.pixlory.color.by.number.activities

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.TimelapseFrameRenderer
import com.pixlory.color.by.number.databinding.ActivityTimelapsePreviewBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.utils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
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
        private const val TAG = "TimelapsePreview"
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private var category: String? = null
    private var levelId: String? = null
    private var renderJob: Job? = null
    private var previewJob: Job? = null
    private var renderer: TimelapseFrameRenderer? = null
    private var isClosing = false

    private val onBackPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            closePreview()
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
            closePreview()
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        previewJob?.cancel()
        binding.previewView.setFrameBitmap(null)
        super.onDestroy()
    }

    private fun loadTimelapse() {
        val category = category ?: return
        val levelId = levelId ?: return

        binding.progressBar.visibility = View.VISIBLE
        renderJob = lifecycleScope.launch {
            try {
                val loadedRenderer = withContext(Dispatchers.Default) {
                    val history = appContainer.paintingProgressRepository
                        .loadPaintHistory(category, levelId)
                    if (history.isEmpty()) return@withContext null

                    val bundle = appContainer.assetLevelRepository.loadLevelBundle(category, levelId)
                    TimelapseFrameRenderer(bundle, history)
                }

                if (loadedRenderer == null || loadedRenderer.stepCount == 0) {
                    loadedRenderer?.recycle()
                    showTimelapseUnavailable()
                    return@launch
                }

                if (isClosing) {
                    loadedRenderer.recycle()
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Not enough memory to render timelapse preview", error)
                showTimelapseUnavailable()
            } catch (error: Exception) {
                Log.e(TAG, "Cannot render timelapse preview", error)
                showTimelapseUnavailable()
            }
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

    private fun showTimelapseUnavailable() {
        if (isClosing || isFinishing || isDestroyed) return
        binding.progressBar.visibility = View.GONE
        showToast(getString(R.string.timelapse_unavailable))
        closePreview()
    }

    /**
     * Bitmap rendering is CPU-bound and cancellation is cooperative.  Wait until every render
     * coroutine has returned before recycling the renderer's buffers.
     */
    private fun closePreview() {
        if (isClosing) return
        isClosing = true
        binding.btnSkip.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.previewView.setFrameBitmap(null)

        val jobsToStop = listOfNotNull(renderJob, previewJob)
        renderJob?.cancel()
        previewJob?.cancel()

        lifecycleScope.launch {
            jobsToStop.joinAll()
            renderer?.recycle()
            renderer = null
            finish()
        }
    }
}
