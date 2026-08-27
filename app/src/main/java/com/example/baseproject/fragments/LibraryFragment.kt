package com.example.baseproject.fragments

import android.content.Intent
import android.view.View
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import com.example.baseproject.data.progressFraction
import com.example.baseproject.databinding.FragmentLibraryBinding
import com.example.baseproject.databinding.ItemLibraryCategoryTabBinding
import com.example.baseproject.dialog.CurrentPictureDialog
import com.example.baseproject.dialog.ResetPictureDialog
import com.example.baseproject.utils.AppThemeManager
import com.example.baseproject.ui.library.LibraryViewModel
import com.example.baseproject.utils.CompletedPictureActions
import com.example.baseproject.utils.setOnUnDoubleClick
import kotlinx.coroutines.flow.collectLatest

class LibraryFragment : BaseFragment<FragmentLibraryBinding>(FragmentLibraryBinding::inflate) {

    private val appContainer by lazy {
        (requireActivity().application as MyApplication).appContainer
    }

    private val viewModel: LibraryViewModel by viewModels {
        SimpleViewModelFactory {
            LibraryViewModel(appContainer.assetLevelRepository)
        }
    }
    private val completedPictureActions by lazy {
        CompletedPictureActions(
            activity = requireActivity(),
            fragmentManager = parentFragmentManager,
            lifecycleScope = lifecycleScope,
            appContainer = appContainer,
            onResetComplete = { binding.rvLevels.adapter?.notifyDataSetChanged() }
        )
    }

    override fun initData() {

    }

    override fun initView() {
        applyAppTheme()
        binding.rvLevels.layoutManager = GridLayoutManager(requireActivity(), 2)
        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                binding.progressBar.visibility =
                    if (state.isLoading) android.view.View.VISIBLE else android.view.View.GONE
                renderCategoryTabs(state.categories, state.categoryNames, state.selectedCategory)
                binding.rvLevels.adapter =
                    LevelAdapter(
                        state.visibleLevels,
                        appContainer.paintingProgressRepository,
                        appContainer.thumbnailRepository,
                        lifecycleScope
                    ) { level ->
                        onLevelClicked(level)
                    }
            }
        }
    }

    override fun initActionView() {

        binding.btnAchieve.setOnUnDoubleClick {
            startActivity(Intent(requireActivity(), AchieveActivity::class.java))
        }
    }

    private fun renderCategoryTabs(
        categories: List<String>,
        categoryNames: Map<String, String>,
        selectedCategory: String?
    ) {
        binding.layoutCategories.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        categories.forEachIndexed { index, category ->
            val tabBinding =
                ItemLibraryCategoryTabBinding.inflate(inflater, binding.layoutCategories, false)
            tabBinding.tvCategoryTab.text = categoryNames[category] ?: category
            tabBinding.tvCategoryTab.tag = category
            tabBinding.tvCategoryTab.setOnClickListener {
                viewModel.selectCategory(category)
            }

            if (index == categories.lastIndex) {
                val layoutParams =
                    tabBinding.tvCategoryTab.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                layoutParams?.marginEnd = 0
            }

            binding.layoutCategories.addView(tabBinding.root)
        }
        updateTabSelection(selectedCategory)
    }

    private fun updateTabSelection(selectedCategory: String?) {
        repeat(binding.layoutCategories.childCount) { index ->
            val tabView = binding.layoutCategories.getChildAt(index) as? TextView ?: return@repeat
            val isSelected = tabView.tag == selectedCategory
            tabView.background = ContextCompat.getDrawable(
                requireContext(),
                if (isSelected) R.drawable.bg_library_category_tab_selected else R.drawable.bg_library_category_tab_unselected
            )
            tabView.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isSelected) R.color.white else R.color.grey_50
                )
            )
            tabView.isSelected = isSelected
        }
    }

    override fun onResume() {
        super.onResume()
        applyAppTheme()
        binding.rvLevels.adapter?.notifyDataSetChanged()
    }

    private fun applyAppTheme() {
        AppThemeManager.applyFullBackground(binding.root)
        AppThemeManager.applyTopImage(binding.ivTopImage)
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
        CurrentPictureDialog().apply {
            previewFile =
                appContainer.thumbnailRepository.getThumbnailFile(level.category, level.id)
            onColor = { openPaintActivity(level) }
            onReset = {
                showResetPictureDialog(level)
                dismiss()
            }
        }.show(parentFragmentManager, CurrentPictureDialog::class.java.simpleName)
    }

    private fun showResetPictureDialog(level: LevelConfig) {
        ResetPictureDialog().apply {
            onRestart = {
                appContainer.paintingProgressRepository.resetProgress(level.category, level.id)
                appContainer.thumbnailRepository.deleteThumbnail(level.category, level.id)
                binding.rvLevels.adapter?.notifyDataSetChanged()
            }
        }.show(parentFragmentManager, ResetPictureDialog::class.java.simpleName)
    }

    private fun openPaintActivity(level: LevelConfig) {
        val intent = Intent(requireActivity(), PaintActivity::class.java)
        intent.putExtra("CATEGORY", level.category)
        intent.putExtra("LEVEL_ID", level.id)
        startActivity(intent)
    }

}
