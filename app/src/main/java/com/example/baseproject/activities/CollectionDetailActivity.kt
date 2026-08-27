package com.example.baseproject.activities

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.example.baseproject.MyApplication
import com.example.baseproject.adapters.LevelAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progressFraction
import com.example.baseproject.databinding.ActivityCollectionDetailBinding
import com.example.baseproject.dialog.CurrentPictureDialog
import com.example.baseproject.dialog.ResetPictureDialog
import com.example.baseproject.utils.AppThemeManager
import com.example.baseproject.utils.CompletedPictureActions
import com.example.baseproject.ui.collection.CollectionDetailUiState
import com.example.baseproject.ui.collection.CollectionDetailViewModel
import kotlinx.coroutines.flow.collectLatest

class CollectionDetailActivity : BaseActivity<ActivityCollectionDetailBinding>(
    ActivityCollectionDetailBinding::inflate
) {

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
        binding.btnBack.setOnClickListener { finish() }
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
            binding.tvTitle.text = collection.title
            binding.tvNumberCount.text = collection.imageCount.toString()
            Glide.with(binding.ivThumbnail)
                .load(collection.thumbnailUrl)
                .into(binding.ivThumbnail)

            val description = collection.description
            binding.tvDescription.visibility =
                if (description.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvDescription.text = description.orEmpty()
        }

        binding.tvNumberCountDone.text = "${state.completedCount}/${state.levels.size}"

        binding.rvLevels.adapter = LevelAdapter(
            state.levels,
            appContainer.paintingProgressRepository,
            appContainer.thumbnailRepository,
            lifecycleScope
        ) { level -> onLevelClicked(level) }
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
        intent.putExtra("CATEGORY", level.category)
        intent.putExtra("LEVEL_ID", level.id)
        startActivity(intent)
    }
}
