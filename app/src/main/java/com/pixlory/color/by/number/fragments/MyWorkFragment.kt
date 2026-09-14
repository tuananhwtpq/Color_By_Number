package com.pixlory.color.by.number.fragments

import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.activities.AchieveActivity
import com.pixlory.color.by.number.activities.PaintActivity
import com.pixlory.color.by.number.adapters.LevelAdapter
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.bases.BaseFragment
import com.pixlory.color.by.number.data.LevelConfig
import com.pixlory.color.by.number.databinding.FragmentMyWorkBinding
import com.pixlory.color.by.number.dialog.DeletePictureDialog
import com.pixlory.color.by.number.dialog.MyworkCurrentPictureDialog
import com.pixlory.color.by.number.dialog.ResetPictureDialog
import com.pixlory.color.by.number.dialog.WatchAdsDialog
import com.pixlory.color.by.number.ui.main.MainViewModel
import com.pixlory.color.by.number.ui.mywork.MyWorkUiState
import com.pixlory.color.by.number.ui.mywork.MyWorkViewModel
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.CompletedPictureActions
import com.pixlory.color.by.number.utils.setOnSoundClickListener
import kotlinx.coroutines.flow.collectLatest


class MyWorkFragment : BaseFragment<FragmentMyWorkBinding>(FragmentMyWorkBinding::inflate) {

    companion object {
        private const val TAB_IN_PROGRESS = 0
        private const val TAB_COMPLETED = 1
        private const val LIBRARY_TAB_POSITION = 0
    }

    private val appContainer by lazy {
        (requireActivity().application as MyApplication).appContainer
    }

    private val viewModel: MyWorkViewModel by viewModels {
        SimpleViewModelFactory {
            MyWorkViewModel(
                appContainer.assetLevelRepository,
                appContainer.collectionRepository,
                appContainer.paintingProgressRepository,
                appContainer.thumbnailRepository
            )
        }
    }

    private val mainViewModel: MainViewModel by activityViewModels {
        SimpleViewModelFactory { MainViewModel() }
    }

    private var selectedTab = TAB_IN_PROGRESS
    private var latestState = MyWorkUiState()
    private val completedPictureActions by lazy {
        CompletedPictureActions(
            activity = requireActivity(),
            fragmentManager = parentFragmentManager,
            lifecycleScope = lifecycleScope,
            appContainer = appContainer,
            onResetComplete = { viewModel.loadData() }
        )
    }

    override fun initData() {

    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.root)
        binding.btnSetting.visibility = View.GONE
        binding.rvMyWork.layoutManager = GridLayoutManager(requireActivity(), 2)
        updateTabSelection(TAB_IN_PROGRESS)
        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                latestState = state
                renderState(state)
            }
        }
    }

    override fun initActionView() {
        binding.tvTabInProgress.setOnSoundClickListener { updateTabSelection(TAB_IN_PROGRESS) }
        binding.tvTabCompleted.setOnSoundClickListener { updateTabSelection(TAB_COMPLETED) }
        binding.btnArchive.setOnSoundClickListener {
            startActivity(Intent(requireActivity(), AchieveActivity::class.java))
        }
        binding.btnGoToLibrary.setOnSoundClickListener {
            mainViewModel.onTabSelected(LIBRARY_TAB_POSITION)
        }
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.root)
        viewModel.loadData(showLoading = false)
    }

    private fun updateTabSelection(tab: Int) {
        selectedTab = tab
        binding.tvTabInProgress.background = ContextCompat.getDrawable(
            requireContext(),
            if (tab == TAB_IN_PROGRESS) R.drawable.bg_library_category_tab_selected else R.drawable.bg_library_category_tab_unselected
        )
        binding.tvTabCompleted.background = ContextCompat.getDrawable(
            requireContext(),
            if (tab == TAB_COMPLETED) R.drawable.bg_library_category_tab_selected else R.drawable.bg_library_category_tab_unselected
        )
        renderState(latestState)
    }

    private fun renderState(state: MyWorkUiState) {
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        val levels =
            if (selectedTab == TAB_IN_PROGRESS) state.inProgressLevels else state.completedLevels
        binding.tvNumberCount.text = levels.size.toString()

        val hasData = levels.isNotEmpty()
        binding.llEmptyState.visibility =
            if (!state.isLoading && !hasData) View.VISIBLE else View.GONE

        if (hasData) {
            binding.rvMyWork.visibility = View.VISIBLE
            binding.rvMyWork.adapter = LevelAdapter(
                appContainer.paintingProgressRepository,
                appContainer.thumbnailRepository
            ) { level -> onMyWorkItemClicked(level) }.apply {
                submitList(levels)
            }
        } else {
            binding.rvMyWork.visibility = View.GONE
        }
    }

    private fun onMyWorkItemClicked(level: LevelConfig) {
        showMyworkCurrentPictureDialog(level, isCompleted = selectedTab == TAB_COMPLETED)
    }

    private fun showMyworkCurrentPictureDialog(level: LevelConfig, isCompleted: Boolean) {
        MyworkCurrentPictureDialog().apply {
            this.isCompleted = isCompleted
            previewFile =
                appContainer.thumbnailRepository.getThumbnailFile(level.category, level.id)
            onColor = { openPaintActivity(level) }
            onReset = {
                showResetPictureDialog(level)
                dismiss()
            }
            onDelete = {
                showDeletePictureDialog(level)
                dismiss()
            }
            onSave = {
                completedPictureActions.showSaveDialog(level.category, level.id)
                dismiss()
            }
            onShare = {
                completedPictureActions.showShareDialog(level.category, level.id)
                dismiss()
            }
        }.show(parentFragmentManager, MyworkCurrentPictureDialog.TAG)
    }

    private fun showResetPictureDialog(level: LevelConfig) {
        ResetPictureDialog().apply {
            onRestart = {
                appContainer.paintingProgressRepository.resetProgress(level.category, level.id)
                appContainer.thumbnailRepository.deleteThumbnail(level.category, level.id)
                viewModel.loadData()
            }
        }.show(parentFragmentManager, ResetPictureDialog::class.java.simpleName)
    }

    private fun showDeletePictureDialog(level: LevelConfig) {
        DeletePictureDialog().apply {
            onDelete = {
                appContainer.paintingProgressRepository.resetProgress(level.category, level.id)
                appContainer.thumbnailRepository.deleteThumbnail(level.category, level.id)
                viewModel.loadData()
            }
        }.show(parentFragmentManager, DeletePictureDialog.TAG)
    }

    private fun openPaintActivity(level: LevelConfig) {
        val intent = Intent(requireActivity(), PaintActivity::class.java)
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
