package com.example.baseproject.fragments

import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.activities.AchieveActivity
import com.example.baseproject.activities.PaintActivity
import com.example.baseproject.adapters.LevelAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.databinding.FragmentMyWorkBinding
import com.example.baseproject.dialog.DeletePictureDialog
import com.example.baseproject.dialog.MyworkCurrentPictureDialog
import com.example.baseproject.dialog.ResetPictureDialog
import com.example.baseproject.ui.main.MainViewModel
import com.example.baseproject.ui.mywork.MyWorkUiState
import com.example.baseproject.ui.mywork.MyWorkViewModel
import com.example.baseproject.utils.AppThemeManager
import com.example.baseproject.utils.CompletedPictureActions
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
        binding.tvTabInProgress.setOnClickListener { updateTabSelection(TAB_IN_PROGRESS) }
        binding.tvTabCompleted.setOnClickListener { updateTabSelection(TAB_COMPLETED) }
        binding.btnArchive.setOnClickListener {
            startActivity(Intent(requireActivity(), AchieveActivity::class.java))
        }
        binding.btnGoToLibrary.setOnClickListener {
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
                levels,
                appContainer.paintingProgressRepository,
                appContainer.thumbnailRepository,
                lifecycleScope
            ) { level -> onMyWorkItemClicked(level) }
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
        intent.putExtra("CATEGORY", level.category)
        intent.putExtra("LEVEL_ID", level.id)
        startActivity(intent)
    }

}
