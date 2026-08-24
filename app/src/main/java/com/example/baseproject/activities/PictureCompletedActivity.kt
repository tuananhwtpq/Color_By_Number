package com.example.baseproject.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.TimelapseVideoUnavailableException
import com.example.baseproject.databinding.ActivityPictureCompletedBinding
import com.example.baseproject.dialog.SaveDialog
import com.example.baseproject.dialog.SavePicSuccessDialog
import com.example.baseproject.dialog.SavingDialog
import com.example.baseproject.dialog.ShareDialog
import com.example.baseproject.utils.ImageSaver
import com.example.baseproject.utils.ImageSharer
import com.example.baseproject.utils.VideoSharer
import com.example.baseproject.utils.VideoSaver
import com.example.baseproject.utils.setOnUnDoubleClick
import com.example.baseproject.utils.showToast
import com.example.baseproject.utils.toFileNameKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PictureCompletedActivity : BaseActivity<ActivityPictureCompletedBinding>(
    ActivityPictureCompletedBinding::inflate
) {

    companion object {
        const val EXTRA_CATEGORY = "CATEGORY"
        const val EXTRA_LEVEL_ID = "LEVEL_ID"
        const val EXTRA_COLLECTED_COUNT = "COLLECTED_COUNT"
        private const val TAG = "PictureCompleted"
        private const val PRE_GENERATE_DELAY_MS = 500L
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

    private val onBackPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    override fun initData() {
        category = intent.getStringExtra(EXTRA_CATEGORY)
        levelId = intent.getStringExtra(EXTRA_LEVEL_ID)
        collectedCount = intent.getIntExtra(EXTRA_COLLECTED_COUNT, 0)
    }

    override fun initView() {
        onBackPressedDispatcher.addCallback(onBackPressCallback)

        binding.tvCollectedCount.text = collectedCount.toString()

        val category = category
        val levelId = levelId
        if (category == null || levelId == null) return

        // Ảnh hoàn thiện được PaintActivity ghi ra file trước khi chuyển màn (không truyền
        // bitmap qua Intent), nên ở đây chỉ cần đọc lại đúng file đó.
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
        binding.btnBackToHome.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnSave.setOnUnDoubleClick {
            showSaveDialog()
        }

        binding.btnShare.setOnUnDoubleClick {
            showShareDialog()
        }

        binding.btnVideo.setOnUnDoubleClick {
            openTimelapsePreview()
        }
    }

    private fun openTimelapsePreview() {
        val category = category
        val levelId = levelId
        if (category == null || levelId == null) {
            showToast(getString(R.string.timelapse_unavailable))
            return
        }

        startActivity(
            Intent(this, TimelapsePreviewActivity::class.java).apply {
                putExtra(TimelapsePreviewActivity.EXTRA_CATEGORY, category)
                putExtra(TimelapsePreviewActivity.EXTRA_LEVEL_ID, levelId)
            }
        )
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
            var wasCancelledByUser = false
            var savedSuccessfully = false
            val messageRes = try {
                val cachedVideo = appContainer.timelapseVideoCache.ensureVideo(category, levelId)
                saveTimelapseVideo(cachedVideo, displayName)
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
