package com.example.baseproject.fragments

import android.content.Intent
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.activities.PaintActivity
import com.example.baseproject.adapters.LevelAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.databinding.FragmentLibraryBinding
import com.example.baseproject.databinding.ItemLibraryCategoryTabBinding
import com.example.baseproject.ui.library.LibraryViewModel
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

    override fun initData() {

    }

    override fun initView() {
        binding.rvLevels.layoutManager = GridLayoutManager(requireActivity(), 2)
        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                binding.progressBar.visibility =
                    if (state.isLoading) android.view.View.VISIBLE else android.view.View.GONE
                renderCategoryTabs(state.categories, state.selectedCategory)
                binding.rvLevels.adapter =
                    LevelAdapter(
                        state.visibleLevels,
                        appContainer.paintingProgressRepository,
                        lifecycleScope
                    ) { level ->
                        val intent = Intent(requireActivity(), PaintActivity::class.java)
                        intent.putExtra("CATEGORY", level.category)
                        intent.putExtra("LEVEL_ID", level.id)
                        startActivity(intent)
                    }
            }
        }
    }

    override fun initActionView() {
    }

    private fun renderCategoryTabs(categories: List<String>, selectedCategory: String?) {
        binding.layoutCategories.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        categories.forEachIndexed { index, category ->
            val tabBinding =
                ItemLibraryCategoryTabBinding.inflate(inflater, binding.layoutCategories, false)
            tabBinding.tvCategoryTab.text = category
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
            val isSelected = tabView.text.toString() == selectedCategory
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
        // Cập nhật lại RecyclerView khi quay lại từ PaintActivity để load lại Thumbnail mới nhất
        binding.rvLevels.adapter?.notifyDataSetChanged()
    }

}
