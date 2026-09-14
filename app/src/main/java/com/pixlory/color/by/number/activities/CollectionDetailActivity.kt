package com.pixlory.color.by.number.activities

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.adapters.LevelAdapter
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.LevelConfig
import com.pixlory.color.by.number.data.localization.ServerContentTextResolver
import com.pixlory.color.by.number.data.progressFraction
import com.pixlory.color.by.number.databinding.ActivityCollectionDetailBinding
import com.pixlory.color.by.number.dialog.CurrentPictureDialog
import com.pixlory.color.by.number.dialog.ResetPictureDialog
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.setOnSoundClickListener
import com.pixlory.color.by.number.utils.CompletedPictureActions
import com.pixlory.color.by.number.ui.collection.CollectionDetailUiState
import com.pixlory.color.by.number.ui.collection.CollectionDetailViewModel
import kotlinx.coroutines.flow.collectLatest

class CollectionDetailActivity : BaseActivity<ActivityCollectionDetailBinding>(
    ActivityCollectionDetailBinding::inflate
) {

    override val shouldMonitorNetwork = true

    companion object {
        const val EXTRA_COLLECTION_ID = "COLLECTION_ID"

        fun newIntent(context: Context, collectionId: String): Intent =
            Intent(context, CollectionDetailActivity::class.java)
                .putExtra(EXTRA_COLLECTION_ID, collectionId)
    }

    private val appContainer by lazy { (application as MyApplication).appContainer }

    private val collectionId: String by lazy {
        intent.getStringExtra(EXTRA_COLLECTION_ID).orEmpty()
    }

    private val viewModel: CollectionDetailViewModel by viewModels {
        SimpleViewModelFactory {
            CollectionDetailViewModel(
                collectionId,
                appContainer.collectionRepository,
                appContainer.paintingProgressRepository
            )
        }
    }
    private val completedPictureActions by lazy {
        CompletedPictureActions(
            activity = this,
            fragmentManager = supportFragmentManager,
            lifecycleScope = lifecycleScope,
            appContainer = appContainer,
            onResetComplete = {
                viewModel.refreshProgress()
                binding.rvLevels.adapter?.notifyDataSetChanged()
            }
        )
    }

    override fun initData() {
        if (collectionId.isBlank()) {
            finish()
        }
    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)

        binding.rvLevels.layoutManager = GridLayoutManager(this, 2)
        // rvLevels nằm trong NestedScrollView nên phải tắt cuộn riêng, để cả màn cuộn cùng nhau.
        binding.rvLevels.isNestedScrollingEnabled = false

        collectWithLifecycle {
            viewModel.uiState.collectLatest { state -> renderState(state) }
        }
    }

    override fun initActionView() {
        binding.btnBack.setOnSoundClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.main)
        // Quay lại từ màn tô: cập nhật lại % của từng tranh và số "đã xong / tổng".
        viewModel.refreshProgress()
        binding.rvLevels.adapter?.notifyDataSetChanged()
    }

    private fun renderState(state: CollectionDetailUiState) {
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        val collection = state.collection
        if (collection != null) {
            binding.tvTitle.text = ServerContentTextResolver.collectionTitle(
                context = this,
                collectionId = collection.id,
                serverEnglish = collection.title
            )
            binding.tvNumberCount.text = collection.imageCount.toString()
            Glide.with(binding.ivThumbnail)
                .load(collection.thumbnailUrl)
                .into(binding.ivThumbnail)

            val description = ServerContentTextResolver.collectionDescription(
                context = this,
                collectionId = collection.id,
                serverEnglish = collection.description
            )
            binding.tvDescription.visibility =
                if (description.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvDescription.text = description.orEmpty()
        }

        binding.tvNumberCountDone.text = getString(
            R.string.count_progress_format,
            state.completedCount,
            state.levels.size,
        )

        binding.rvLevels.adapter = LevelAdapter(
            appContainer.paintingProgressRepository,
            appContainer.thumbnailRepository
        ) { level -> onLevelClicked(level) }.apply {
            submitList(state.levels)
        }
    }

    private fun onLevelClicked(level: LevelConfig) {
        val completedMaskColors =
            appContainer.paintingProgressRepository.loadProgress(level.category, level.id)
        val progress = level.progressFraction(completedMaskColors)

        if (progress > 0f && progress < 1f) {
            showCurrentPictureDialog(level)
        } else if (progress >= 1f) {
            completedPictureActions.showCurrentPictureDialog(level)
        } else {
            openPaintActivity(level)
        }
    }

    private fun showCurrentPictureDialog(level: LevelConfig) {
        showDialogOnce(CurrentPictureDialog::class.java.simpleName) {
            CurrentPictureDialog().apply {
                previewFile =
                    appContainer.thumbnailRepository.getThumbnailFile(level.category, level.id)
                onColor = { openPaintActivity(level) }
                onReset = {
                    showResetPictureDialog(level)
                    dismiss()
                }
            }
        }
    }

    private fun showResetPictureDialog(level: LevelConfig) {
        showDialogOnce(ResetPictureDialog::class.java.simpleName) {
            ResetPictureDialog().apply {
                onRestart = {
                    appContainer.paintingProgressRepository.resetProgress(level.category, level.id)
                    appContainer.thumbnailRepository.deleteThumbnail(level.category, level.id)
                    viewModel.refreshProgress()
                    binding.rvLevels.adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun openPaintActivity(level: LevelConfig) {
        // level.category ở đây là đường dẫn asset đầy đủ ("Collection/Cat moments"), do
        // AssetCollectionRepositoryImpl ghi đè khi đọc config.json.
        val intent = Intent(this, PaintActivity::class.java)
        intent.putExtra(PaintActivity.EXTRA_CATEGORY, level.category)
        intent.putExtra(PaintActivity.EXTRA_LEVEL_ID, level.id)
        preparationThumbnailFor(level)?.let { thumbnail ->
            intent.putExtra(PaintActivity.EXTRA_PREPARATION_THUMBNAIL, thumbnail)
        }
        startActivity(intent)
    }

    private fun preparationThumbnailFor(level: LevelConfig): String? {
        val thumbFile = appContainer.thumbnailRepository.getThumbnailFile(level.category, level.id)
        if (thumbFile.exists()) return thumbFile.absolutePath

        return level.thumbnailUrl
            ?: level.assets?.preview?.takeIf(::isRemoteUrl)
            ?: level.assets?.sourceLine?.takeIf(::isRemoteUrl)
            ?: level.assets?.displayLine?.takeIf(::isRemoteUrl)
            ?: level.assets?.line?.takeIf(::isRemoteUrl)
    }

    private fun isRemoteUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")
}
