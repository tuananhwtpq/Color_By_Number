package com.example.baseproject.utils

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.example.baseproject.R
import com.example.baseproject.app.AppContainer
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.TimelapseVideoUnavailableException
import com.example.baseproject.dialog.CurrentPictureCompletedDialog
import com.example.baseproject.dialog.LoadingDialog
import com.example.baseproject.dialog.ResetPictureDialog
import com.example.baseproject.dialog.SaveDialog
import com.example.baseproject.dialog.SavePicSuccessDialog
import com.example.baseproject.dialog.SavingDialog
import com.example.baseproject.dialog.ShareDialog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CompletedPictureActions(
    private val activity: FragmentActivity,
    private val fragmentManager: FragmentManager,
    private val lifecycleScope: CoroutineScope,
    private val appContainer: AppContainer,
    private val onResetComplete: () -> Unit,
) {
    companion object {
        private const val TAG = "CompletedPictureActions"
        private const val MIN_SAVE_VIDEO_DIALOG_MS = 2_000L
    }

    private val loadingDialog by lazy { LoadingDialog(activity) }
    private var isSavingPicture = false
    private var isSharingPicture = false
    private var isSharingVideo = false
    private var isSavingVideo = false
    private var savingVideoJob: Job? = null
    private var savingDialog: SavingDialog? = null

    fun showCurrentPictureDialog(level: LevelConfig) {
        showDialogOnce(CurrentPictureCompletedDialog.TAG) {
            CurrentPictureCompletedDialog().apply {
                previewFile = appContainer.thumbnailRepository.getThumbnailFile(level.category, level.id)
                onReset = { showResetPictureDialog(level) }
                onSave = { showSaveDialog(level.category, level.id) }
                onShare = { showShareDialog(level.category, level.id) }
            }
        }
    }

    private fun showResetPictureDialog(level: LevelConfig) {
        showDialogOnce(ResetPictureDialog::class.java.simpleName) {
            ResetPictureDialog().apply {
                onRestart = {
                    appContainer.paintingProgressRepository.resetProgress(level.category, level.id)
                    appContainer.thumbnailRepository.deleteThumbnail(level.category, level.id)
                    onResetComplete()
                }
            }
        }
    }

    private fun showShareDialog(category: String, levelId: String) {
        showDialogOnce(ShareDialog.TAG) {
            ShareDialog().apply {
                onSharePicture = { sharePicture(category, levelId) }
                onShareVideo = { shareVideo(category, levelId) }
            }
        }
    }

    private fun showSaveDialog(category: String, levelId: String) {
        showDialogOnce(SaveDialog.TAG) {
            SaveDialog().apply {
                onSavePicture = { savePictureToGallery(category, levelId) }
                onSaveVideo = { saveVideoToGallery(category, levelId) }
            }
        }
    }

    private fun sharePicture(category: String, levelId: String) {
        if (isSharingPicture) return
        isSharingPicture = true

        val sourceFile = appContainer.thumbnailRepository.getThumbnailFile(category, levelId)
        val displayName = "Pixlory_${category}_${levelId}".toFileNameKey()

        lifecycleScope.launch {
            val shareUri = try {
                ImageSharer.prepareShareUri(activity.applicationContext, sourceFile, displayName)
                    .getOrElse { error ->
                        throw IOException("Prepare picture share failed", error)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cannot prepare picture share", e)
                activity.showToast(activity.getString(R.string.share_failed))
                null
            } finally {
                isSharingPicture = false
            }

            if (shareUri != null) {
                shareContent(shareUri, ImageSharer.SHARE_MIME_TYPE)
            }
        }
    }

    private fun shareVideo(category: String, levelId: String) {
        if (isSharingVideo) return
        isSharingVideo = true

        lifecycleScope.launch {
            showLoading(true)
            val shareUri = try {
                val cachedVideo = appContainer.timelapseVideoCache.ensureVideo(category, levelId)
                prepareTimelapseVideoShare(cachedVideo)
            } catch (e: TimelapseVideoUnavailableException) {
                Log.w(TAG, "Cannot share timelapse video: ${e.message}")
                activity.showToast(activity.getString(R.string.timelapse_unavailable))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while sharing timelapse video", e)
                activity.showToast(activity.getString(R.string.share_failed))
                null
            } catch (e: Exception) {
                Log.e(TAG, "Cannot share timelapse video", e)
                activity.showToast(activity.getString(R.string.share_failed))
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
            activity.startActivity(Intent.createChooser(sendIntent, activity.getString(R.string.share)))
            return true
        } catch (e: ActivityNotFoundException) {
            activity.showToast(activity.getString(R.string.share_failed))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open share chooser", e)
            activity.showToast(activity.getString(R.string.share_failed))
        }
        return false
    }

    private suspend fun prepareTimelapseVideoShare(cachedVideo: File): Uri {
        return VideoSharer.prepareShareUri(
            context = activity.applicationContext,
            sourceFile = cachedVideo,
        ).getOrElse { error ->
            throw IOException("Prepare timelapse video share uri failed", error)
        }
    }

    private fun savePictureToGallery(category: String, levelId: String) {
        if (isSavingPicture) return
        isSavingPicture = true

        val sourceFile = appContainer.thumbnailRepository.getThumbnailFile(category, levelId)
        val displayName = "Pixlory_${category}_${levelId}_${System.currentTimeMillis()}".toFileNameKey()

        lifecycleScope.launch {
            val result = ImageSaver.saveImageToGallery(activity.applicationContext, sourceFile, displayName)
            isSavingPicture = false
            if (result.isSuccess) {
                showSaveSuccessDialog(R.string.picture_was_saved_to_your_device)
            } else {
                activity.showToast(activity.getString(R.string.download_failed))
            }
        }
    }

    private fun saveVideoToGallery(category: String, levelId: String) {
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
                messageRes != null -> activity.showToast(activity.getString(messageRes))
            }
        }
    }

    private suspend fun saveTimelapseVideo(
        cachedVideo: File,
        displayName: String,
    ) {
        VideoSaver.saveVideoToGallery(
            context = activity.applicationContext,
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
        if (fragmentManager.isStateSaved) return
        savingDialog = SavingDialog().apply {
            onClose = {
                savingVideoJob?.cancel()
                this@CompletedPictureActions.activity.showToast(
                    this@CompletedPictureActions.activity.getString(R.string.download_failed)
                )
            }
        }
        savingDialog?.show(fragmentManager, SavingDialog.TAG)
    }

    private fun dismissSavingDialog() {
        savingDialog?.dismissAllowingStateLoss()
        savingDialog = null
    }

    private fun showSaveSuccessDialog(@StringRes contentRes: Int) {
        activity.window.decorView.post {
            showDialogOnce(SavePicSuccessDialog.TAG) {
                SavePicSuccessDialog.newInstance(contentRes)
            }
        }
    }

    private fun showLoading(isShow: Boolean) {
        if (!isShow && loadingDialog.isShowing) {
            loadingDialog.dismiss()
        } else if (isShow && !loadingDialog.isShowing) {
            loadingDialog.show()
        }
    }

    private fun showDialogOnce(tag: String, create: () -> DialogFragment) {
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed || fragmentManager.isStateSaved) return@post

            val hasDialogShowing = fragmentManager.fragments.any {
                it is DialogFragment &&
                    it.isAdded &&
                    it.tag != CurrentPictureCompletedDialog.TAG
            }
            if (hasDialogShowing) return@post

            create().show(fragmentManager, tag)
        }
    }
}
