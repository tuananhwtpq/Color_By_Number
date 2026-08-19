package com.example.baseproject.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityPictureCompletedBinding
import com.example.baseproject.dialog.SaveDialog
import com.example.baseproject.utils.ImageSaver
import com.example.baseproject.utils.ImageSharer
import com.example.baseproject.utils.setOnUnDoubleClick
import com.example.baseproject.utils.showToast
import kotlinx.coroutines.launch

class PictureCompletedActivity : BaseActivity<ActivityPictureCompletedBinding>(
    ActivityPictureCompletedBinding::inflate
) {

    companion object {
        const val EXTRA_CATEGORY = "CATEGORY"
        const val EXTRA_LEVEL_ID = "LEVEL_ID"
        const val EXTRA_COLLECTED_COUNT = "COLLECTED_COUNT"
    }

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private var category: String? = null
    private var levelId: String? = null
    private var collectedCount: Int = 0
    private var isSavingPicture = false
    private var isSharingPicture = false

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
    }

    override fun initActionView() {
        binding.btnBackToHome.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnSave.setOnUnDoubleClick {
            showSaveDialog()
        }

        binding.btnShare.setOnUnDoubleClick {
            sharePicture()
        }
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
        val displayName = "Pixlory_${category}_${levelId}"

        lifecycleScope.launch {
            val result = ImageSharer.prepareShareUri(applicationContext, sourceFile, displayName)
            isSharingPicture = false

            val shareUri = result.getOrNull()
            if (shareUri == null) {
                showToast(getString(R.string.share_failed))
                return@launch
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = ImageSharer.SHARE_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            } catch (e: ActivityNotFoundException) {
                // Máy không có app nào nhận ACTION_SEND ảnh (hiếm, nhưng gặp trên emulator trần).
                showToast(getString(R.string.share_failed))
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
        val displayName = "Pixlory_${category}_${levelId}_${System.currentTimeMillis()}"

        lifecycleScope.launch {
            val result = ImageSaver.saveImageToGallery(applicationContext, sourceFile, displayName)
            isSavingPicture = false
            showToast(
                getString(
                    if (result.isSuccess) R.string.download_success else R.string.download_failed
                )
            )
        }
    }

}
