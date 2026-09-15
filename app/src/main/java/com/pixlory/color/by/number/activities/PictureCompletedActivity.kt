package com.pixlory.color.by.number.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.TimelapseFrameRenderer
import com.pixlory.color.by.number.data.TimelapseVideoUnavailableException
import com.pixlory.color.by.number.databinding.ActivityPictureCompletedBinding
import com.pixlory.color.by.number.dialog.SaveDialog
import com.pixlory.color.by.number.dialog.SavePicSuccessDialog
import com.pixlory.color.by.number.dialog.SavingDialog
import com.pixlory.color.by.number.dialog.ShareDialog
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.ImageSaver
import com.pixlory.color.by.number.utils.ImageSharer
import com.pixlory.color.by.number.utils.VideoSharer
import com.pixlory.color.by.number.utils.VideoSaver
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.utils.setOnSoundClickListener
import com.pixlory.color.by.number.utils.setRequireShowRate
import com.pixlory.color.by.number.utils.showToast
import com.pixlory.color.by.number.utils.toFileNameKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class PictureCompletedActivity : BaseActivity<ActivityPictureCompletedBinding>(
    ActivityPictureCompletedBinding::inflate
) {

    override val shouldMonitorNetwork = true

    companion object {
        const val EXTRA_CATEGORY = "CATEGORY"
        const val EXTRA_LEVEL_ID = "LEVEL_ID"
        const val EXTRA_COLLECTED_COUNT = "COLLECTED_COUNT"
        const val EXTRA_FROM_HOME = "EXTRA_FROM_HOME"
        private const val TAG = "PictureCompleted"
        private const val PRE_GENERATE_DELAY_MS = 500L
        private const val MIN_SAVE_VIDEO_DIALOG_MS = 2_000L
        private const val INLINE_TIMELAPSE_DURATION_MS = 15_000L
        private const val INLINE_TIMELAPSE_FRAME_DELAY_MS = 33L
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private var category: String? = null
    private var levelId: String? = null
    private var collectedCount: Int = 0
    private var isSavingPicture = false
    private var isSharingPicture = false
    private var isSharingVideo = false
    private var isSavingVideo = false
    private var savingVideoJob: Job? = null
    private var savingDialog: SavingDialog? = null
    private var preGenerateVideoJob: Job? = null
    private var inlineTimelapseRenderJob: Job? = null
    private var inlineTimelapsePreviewJob: Job? = null
    private var inlineTimelapseRenderer: TimelapseFrameRenderer? = null
    private var inlineTimelapseSession = 0
    private var isInlineTimelapseActive = false

    private val onBackPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (intent.getBooleanExtra(EXTRA_FROM_HOME, false)) {
                loadAndShowInterBackToHome(
                    navAction = {
                        setRequireShowRate(true)
                        finish()
                    },
                    viewBlock = interAdBlockView()
                )
            } else {
                finish()
            }
        }
    }

    override fun initData() {
        category = intent.getStringExtra(EXTRA_CATEGORY)
        levelId = intent.getStringExtra(EXTRA_LEVEL_ID)
        collectedCount = intent.getIntExtra(EXTRA_COLLECTED_COUNT, 0)
    }

    override fun initView() {
        AppThemeManager.applyCompleteBackground(binding.main)
        onBackPressedDispatcher.addCallback(onBackPressCallback)

        binding.tvCollectedCount.text = collectedCount.toString()

        val category = category
        val levelId = levelId
        if (category == null || levelId == null) return

        loadAndShowNativeCollapsibleOther(
            binding.frNativeSmall,
            binding.frNativeExpand,
            binding.whiteLine
        )

        val completedFile = appContainer.thumbnailRepository.getThumbnailFile(category, levelId)
        Glide.with(this)
            .load(completedFile)
            .placeholder(R.color.white)
            .error(R.color.white)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(binding.ivImage)

        preGenerateTimelapseVideo(category, levelId)
    }

    override fun initActionView() {
//        binding.btnContinue.setOnSoundClickListener {
//            openCompletedPictureCategory()
//        }

        binding.btnBackToHome.setOnSoundClickListener {
            loadAndShowInterBackToHome(
                navAction = {
                    setRequireShowRate(true)
                    openCompletedPictureCategory()
                },
                viewBlock = interAdBlockView()
            )
        }

        binding.btnSave.setOnUnDoubleClick {
            showSaveDialog()
        }

        binding.btnShare.setOnUnDoubleClick {
            showShareDialog()
        }

        binding.btnVideo.setOnUnDoubleClick {
            toggleInlineTimelapse()
        }
    }

    private fun openCompletedPictureCategory() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_SELECTED_TAB, MainActivity.TAB_LIBRARY)
                category?.let { putExtra(MainActivity.EXTRA_LIBRARY_CATEGORY, it) }
            }
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyCompleteBackground(binding.main)
    }

    override fun onPause() {
        if (isInlineTimelapseActive) {
            stopInlineTimelapse()
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopInlineTimelapse()
        super.onDestroy()
    }

    private fun toggleInlineTimelapse() {
        if (isInlineTimelapseActive) {
            stopInlineTimelapse()
        } else {
            startInlineTimelapse()
        }
    }

    private fun startInlineTimelapse() {
        val category = category
        val levelId = levelId
        if (category == null || levelId == null) {
            showToast(getString(R.string.timelapse_unavailable))
            return
        }

        val session = ++inlineTimelapseSession
        isInlineTimelapseActive = true
        updateInlineTimelapseUi(isPlaying = true)

        inlineTimelapseRenderJob = lifecycleScope.launch {
            try {
                val loadedRenderer = withContext(Dispatchers.Default) {
                    val history = appContainer.paintingProgressRepository
                        .loadPaintHistory(category, levelId)
                    if (history.isEmpty()) {
                        throw TimelapseVideoUnavailableException(
                            "Paint history is empty for $category/$levelId"
                        )
                    }
                    val bundle = appContainer.assetLevelRepository.loadLevelBundle(category, levelId)
                    TimelapseFrameRenderer(bundle, history)
                }

                if (!isInlineTimelapseSessionActive(session)) {
                    loadedRenderer.recycle()
                    return@launch
                }

                inlineTimelapseRenderer = loadedRenderer
                val initialFrame = withContext(Dispatchers.Default) {
                    loadedRenderer.renderStep(0)
                }
                if (!isInlineTimelapseSessionActive(session)) return@launch

                binding.timelapsePreviewView.setFrameBitmap(initialFrame)
                binding.timelapsePreviewView.visibility = View.VISIBLE
                binding.ivImage.visibility = View.GONE
                startInlineTimelapsePreview(loadedRenderer, session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TimelapseVideoUnavailableException) {
                showInlineTimelapseFailure(session, error)
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Not enough memory to render inline timelapse", error)
                showInlineTimelapseFailure(session)
            } catch (error: Exception) {
                Log.e(TAG, "Cannot render inline timelapse", error)
                showInlineTimelapseFailure(session)
            } finally {
                if (session == inlineTimelapseSession) {
                    inlineTimelapseRenderJob = null
                }
            }
        }
    }

    private fun startInlineTimelapsePreview(
        renderer: TimelapseFrameRenderer,
        session: Int
    ) {
        inlineTimelapsePreviewJob = lifecycleScope.launch {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                var lastStep = -1

                while (isActive && isInlineTimelapseSessionActive(session)) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val progress = (elapsed.toFloat() / INLINE_TIMELAPSE_DURATION_MS)
                        .coerceIn(0f, 1f)
                    val targetStep = (renderer.stepCount * progress).roundToInt()
                        .coerceIn(0, renderer.stepCount)

                    if (targetStep != lastStep) {
                        val frame = withContext(Dispatchers.Default) {
                            renderer.renderStep(targetStep)
                        }
                        if (!isInlineTimelapseSessionActive(session)) return@launch
                        binding.timelapsePreviewView.setFrameBitmap(frame)
                        lastStep = targetStep
                    }

                    if (progress >= 1f) {
                        stopInlineTimelapse()
                        return@launch
                    }
                    delay(INLINE_TIMELAPSE_FRAME_DELAY_MS)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Not enough memory while playing inline timelapse", error)
                showInlineTimelapseFailure(session)
            } catch (error: Exception) {
                Log.e(TAG, "Cannot play inline timelapse", error)
                showInlineTimelapseFailure(session)
            } finally {
                if (session == inlineTimelapseSession) {
                    inlineTimelapsePreviewJob = null
                }
            }
        }
    }

    private fun isInlineTimelapseSessionActive(session: Int): Boolean {
        return isInlineTimelapseActive && session == inlineTimelapseSession
    }

    private fun showInlineTimelapseFailure(
        session: Int,
        error: TimelapseVideoUnavailableException? = null
    ) {
        if (session != inlineTimelapseSession) return
        error?.let { Log.w(TAG, "Inline timelapse is unavailable: ${it.message}") }
        showToast(getString(R.string.timelapse_unavailable))
        stopInlineTimelapse()
    }

    private fun stopInlineTimelapse() {
        val jobsToStop = listOfNotNull(inlineTimelapseRenderJob, inlineTimelapsePreviewJob)
        val rendererToRecycle = inlineTimelapseRenderer

        inlineTimelapseSession++
        isInlineTimelapseActive = false
        inlineTimelapseRenderJob = null
        inlineTimelapsePreviewJob = null
        inlineTimelapseRenderer = null
        jobsToStop.forEach(Job::cancel)

        binding.timelapsePreviewView.setFrameBitmap(null)
        binding.timelapsePreviewView.visibility = View.GONE
        binding.ivImage.visibility = View.VISIBLE
        updateInlineTimelapseUi(isPlaying = false)

        if (rendererToRecycle != null) {
            lifecycleScope.launch {
                jobsToStop.joinAll()
                rendererToRecycle.recycle()
            }
        }
    }

    private fun updateInlineTimelapseUi(isPlaying: Boolean) {
        binding.btnVideo.setImageResource(
            if (isPlaying) R.drawable.ic_skip_new else R.drawable.ic_video
        )
        binding.tvVideo.setText(if (isPlaying) R.string.skip else R.string.video)
    }

    private fun sharePicture() {
        val category = category
        val levelId = levelId
        if (category == null || levelId == null) {
            showToast(getString(R.string.share_failed))
            return
        }

        // Dựng ảnh chia sẻ mất một nhịp I/O, chặn bấm chồng để không mở nhiều chooser.
        if (isSharingPicture) return
        isSharingPicture = true

        val sourceFile = appContainer.thumbnailRepository.getThumbnailFile(category, levelId)
        val displayName = "Pixlory_${category}_${levelId}".toFileNameKey()

        lifecycleScope.launch {
            val shareUri = try {
                ImageSharer.prepareShareUri(applicationContext, sourceFile, displayName)
                    .getOrElse { error ->
                        throw IOException("Prepare picture share failed", error)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cannot prepare picture share", e)
                showToast(getString(R.string.share_failed))
                null
            } finally {
                isSharingPicture = false
            }

            if (shareUri != null) {
                shareContent(shareUri, ImageSharer.SHARE_MIME_TYPE)
            }
        }
    }

    private fun shareVideo() {
        val category = category
        val levelId = levelId
        if (category == null || levelId == null) {
            showToast(getString(R.string.share_failed))
            return
        }
        if (isSharingVideo) return
        isSharingVideo = true

        lifecycleScope.launch {
            showLoading(true)
            val shareUri = try {
                val cachedVideo = appContainer.timelapseVideoCache.ensureVideo(category, levelId)
                prepareTimelapseVideoShare(cachedVideo)
            } catch (e: TimelapseVideoUnavailableException) {
                Log.w(TAG, "Cannot share timelapse video: ${e.message}")
                showToast(getString(R.string.timelapse_unavailable))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while sharing timelapse video", e)
                showToast(getString(R.string.share_failed))
                null
            } catch (e: Exception) {
                Log.e(TAG, "Cannot share timelapse video", e)
                showToast(getString(R.string.share_failed))
                null
            } finally {
                isSharingVideo = false
                showLoading(false)
            }

            if (shareUri != null) shareContent(shareUri, VideoSharer.SHARE_MIME_TYPE)
        }
    }

    private fun shareContent(shareUri: Uri, mimeType: String): Boolean {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            return true
        } catch (e: ActivityNotFoundException) {
            showToast(getString(R.string.share_failed))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open share chooser", e)
            showToast(getString(R.string.share_failed))
        }
        return false
    }

    private suspend fun prepareTimelapseVideoShare(cachedVideo: File): Uri {
        return VideoSharer.prepareShareUri(
            context = applicationContext,
            sourceFile = cachedVideo,
        ).getOrElse { error ->
            throw IOException("Prepare timelapse video share uri failed", error)
        }
    }

    private fun showShareDialog() {
        showDialogOnce(ShareDialog.TAG) {
            ShareDialog().apply {
                onSharePicture = {
                    sharePicture()
                }
                onShareVideo = {
                    shareVideo()
                }
            }
        }
    }

    private fun showSaveDialog() {
        showDialogOnce(SaveDialog.TAG) {
            SaveDialog().apply {
                onSavePicture = {
                    savePictureToGallery()
                }
                onSaveVideo = {
                    saveVideoToGallery()
                }
            }
        }
    }

    private fun savePictureToGallery() {
        val category = category
        val levelId = levelId
        if (category == null || levelId == null) {
            showToast(getString(R.string.download_failed))
            return
        }

        // Chặn bấm liên tiếp để không tạo ra nhiều bản copy trong thư viện.
        if (isSavingPicture) return
        isSavingPicture = true

        val sourceFile = appContainer.thumbnailRepository.getThumbnailFile(category, levelId)
        val displayName = "Pixlory_${category}_${levelId}_${System.currentTimeMillis()}".toFileNameKey()

        lifecycleScope.launch {
            val result = ImageSaver.saveImageToGallery(applicationContext, sourceFile, displayName)
            isSavingPicture = false
            if (result.isSuccess) {
                showSaveSuccessDialog(R.string.picture_was_saved_to_your_device)
            } else {
                showToast(getString(R.string.download_failed))
            }
        }
    }

    private fun saveVideoToGallery() {
        val category = category
        val levelId = levelId
        if (category == null || levelId == null) {
            showToast(getString(R.string.download_failed))
            return
        }
        if (isSavingVideo) return
        isSavingVideo = true

        val displayName = "Pixlory_${category}_${levelId}_${System.currentTimeMillis()}".toFileNameKey()

        showSavingDialog()
        savingVideoJob = lifecycleScope.launch {
            val savingStartedAt = SystemClock.elapsedRealtime()
            var wasCancelledByUser = false
            var savedSuccessfully = false
            val messageRes = try {
                val cachedVideo = appContainer.timelapseVideoCache.ensureVideo(category, levelId)
                saveTimelapseVideo(cachedVideo, displayName)
                waitForMinimumSaveVideoDialogDuration(savingStartedAt)
                savedSuccessfully = true
                null
            } catch (e: TimelapseVideoUnavailableException) {
                Log.w(TAG, "Cannot save timelapse video: ${e.message}")
                R.string.timelapse_unavailable
            } catch (e: CancellationException) {
                wasCancelledByUser = true
                R.string.download_failed
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while saving timelapse video", e)
                R.string.download_failed
            } catch (e: Exception) {
                Log.e(TAG, "Cannot save timelapse video", e)
                R.string.download_failed
            } finally {
                isSavingVideo = false
                savingVideoJob = null
                dismissSavingDialog()
            }

            when {
                savedSuccessfully -> showSaveSuccessDialog(R.string.video_was_saved_to_your_device)
                wasCancelledByUser -> Unit
                messageRes != null -> showToast(getString(messageRes))
            }
        }
    }

    private suspend fun saveTimelapseVideo(
        cachedVideo: File,
        displayName: String,
    ) {
        VideoSaver.saveVideoToGallery(
            context = applicationContext,
            sourceFile = cachedVideo,
            displayName = displayName,
        ).getOrElse { error ->
            throw IOException("Save timelapse video to gallery failed", error)
        }
    }

    private suspend fun waitForMinimumSaveVideoDialogDuration(startedAt: Long) {
        val remainingMs = MIN_SAVE_VIDEO_DIALOG_MS - (SystemClock.elapsedRealtime() - startedAt)
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
    }

    private fun showSavingDialog() {
        if (supportFragmentManager.isStateSaved) return
        savingDialog = SavingDialog().apply {
            onClose = {
                preGenerateVideoJob?.cancel()
                savingVideoJob?.cancel()
                showToast(getString(R.string.download_failed))
            }
        }
        savingDialog?.show(supportFragmentManager, SavingDialog.TAG)
    }

    private fun dismissSavingDialog() {
        savingDialog?.dismissAllowingStateLoss()
        savingDialog = null
    }

    private fun showSaveSuccessDialog(contentRes: Int) {
        binding.root.post {
            showDialogOnce(SavePicSuccessDialog.TAG) {
                SavePicSuccessDialog.newInstance(contentRes)
            }
        }
    }

    private fun preGenerateTimelapseVideo(category: String, levelId: String) {
        preGenerateVideoJob?.cancel()
        preGenerateVideoJob = lifecycleScope.launch {
            delay(PRE_GENERATE_DELAY_MS)
            try {
                appContainer.timelapseVideoCache.ensureVideo(category, levelId)
            } catch (e: TimelapseVideoUnavailableException) {
                Log.w(TAG, "Cannot pre-generate timelapse video: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while pre-generating timelapse video", e)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot pre-generate timelapse video", e)
            }
        }
    }

}
