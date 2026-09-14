package com.pixlory.color.by.number.activities

import android.animation.ValueAnimator
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
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
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class TimelapsePreviewActivity : BaseActivity<ActivityTimelapsePreviewBinding>(
    ActivityTimelapsePreviewBinding::inflate
) {

    override val shouldMonitorNetwork = true

    companion object {
        const val EXTRA_CATEGORY = "CATEGORY"
        const val EXTRA_LEVEL_ID = "LEVEL_ID"
        const val EXTRA_COLLECTED_COUNT = "COLLECTED_COUNT"
        const val EXTRA_OPEN_PICTURE_COMPLETED_ON_SKIP = "OPEN_PICTURE_COMPLETED_ON_SKIP"

        private const val PREVIEW_DURATION_MS = 15_000L
        private const val PREVIEW_FRAME_DELAY_MS = 33L
        private const val PERCENTAGE_ANIMATION_DURATION_MS = 2_200L
        private const val MIN_PERCENTAGE = 89
        private const val MAX_PERCENTAGE = 99
        private const val TAG = "TimelapsePreview"
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private var category: String? = null
    private var levelId: String? = null
    private var collectedPaintDrops: Int = 0
    private var shouldOpenPictureCompletedOnClose = false
    private var renderJob: Job? = null
    private var previewJob: Job? = null
    private var renderer: TimelapseFrameRenderer? = null
    private var isClosing = false
    private val percentageAnimators = mutableListOf<ValueAnimator>()

    private val onBackPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            closePreview()
        }
    }

    override fun initData() {
        category = intent.getStringExtra(EXTRA_CATEGORY)
        levelId = intent.getStringExtra(EXTRA_LEVEL_ID)
        collectedPaintDrops = intent.getIntExtra(EXTRA_COLLECTED_COUNT, 0)
        shouldOpenPictureCompletedOnClose = intent.getBooleanExtra(
            EXTRA_OPEN_PICTURE_COMPLETED_ON_SKIP,
            false
        )
        if (category == null || levelId == null) {
            finish()
        }
    }

    override fun initView() {
        onBackPressedDispatcher.addCallback(onBackPressCallback)
        animatePlayerPercentages()
        loadTimelapse()
    }

    override fun initActionView() {
        binding.btnSkip.setOnUnDoubleClick {
            closePreview()
        }

        binding.ivSkip.setOnUnDoubleClick {
            closePreview()
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        previewJob?.cancel()
        percentageAnimators.forEach(ValueAnimator::cancel)
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

    private fun animatePlayerPercentages() {
        val surpassedTarget = randomPercentage(MIN_PERCENTAGE * 100 + 1, MAX_PERCENTAGE * 100 - 1)
        val oneMoreTarget = randomPercentage(
            (surpassedTarget * 100).roundToInt() + 1,
            MAX_PERCENTAGE * 100
        )

        animatePercentage(surpassedTarget) { percentage ->
            binding.tvSurpassed.text = getHighlightedPercentageText(
                R.string.timelapse_surpassed_format,
                percentage
            )
        }
        animatePercentage(oneMoreTarget) { percentage ->
            binding.tvOneMore.text = getHighlightedPercentageText(
                R.string.timelapse_one_more_format,
                percentage
            )
        }
    }

    private fun randomPercentage(minInclusive: Int, maxExclusive: Int): Float {
        val basisPoints = Random.nextInt(minInclusive, maxExclusive)
            .let { if (it % 100 == 0) it + 1 else it }
        return basisPoints / 100f
    }

    private fun animatePercentage(target: Float, onPercentageUpdated: (Float) -> Unit) {
        onPercentageUpdated(0f)
        ValueAnimator.ofFloat(0f, target).apply {
            duration = PERCENTAGE_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                onPercentageUpdated(animator.animatedValue as Float)
            }
            percentageAnimators += this
            start()
        }
    }

    private fun getHighlightedPercentageText(stringRes: Int, percentage: Float): SpannableString {
        val percentageText = String.format(Locale.getDefault(), "%.2f%%", percentage)
        val text = getString(stringRes, percentageText)
        val percentageStart = text.indexOf(percentageText)
        return SpannableString(text).apply {
            if (percentageStart == -1) return@apply
            val percentageEnd = percentageStart + percentageText.length
            setSpan(
                TextAppearanceSpan(this@TimelapsePreviewActivity, R.style.Caption),
                percentageStart,
                percentageEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@TimelapsePreviewActivity, R.color.orange500)),
                percentageStart,
                percentageEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * Bitmap rendering is CPU-bound and cancellation is cooperative.  Wait until every render
     * coroutine has returned before recycling the renderer's buffers.
     */
    private fun closePreview() {
        if (isClosing) return
        isClosing = true
        percentageAnimators.forEach(ValueAnimator::cancel)
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
            if (shouldOpenPictureCompletedOnClose) {
                openPictureCompleted()
            } else {
                finish()
            }
        }
    }

    private fun openPictureCompleted() {
        val category = category ?: run {
            finish()
            return
        }
        val levelId = levelId ?: run {
            finish()
            return
        }
        startActivity(
            android.content.Intent(this, PictureCompletedActivity::class.java).apply {
                putExtra(PictureCompletedActivity.EXTRA_CATEGORY, category)
                putExtra(PictureCompletedActivity.EXTRA_LEVEL_ID, levelId)
                putExtra(PictureCompletedActivity.EXTRA_COLLECTED_COUNT, collectedPaintDrops)
            }
        )
        finish()
    }
}
