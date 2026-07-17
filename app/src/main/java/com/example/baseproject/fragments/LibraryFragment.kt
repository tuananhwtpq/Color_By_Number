package com.example.baseproject.fragments

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.baseproject.R
import com.example.baseproject.activities.PaintActivity
import com.example.baseproject.adapters.LevelAdapter
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.AssetRepository
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.databinding.FragmentLibraryBinding
import com.example.baseproject.databinding.ItemLibraryCategoryTabBinding
import kotlinx.coroutines.launch

class LibraryFragment : BaseFragment<FragmentLibraryBinding>(FragmentLibraryBinding::inflate) {

    private lateinit var repository: AssetRepository
    private var allLevels = listOf<LevelConfig>()
    private var categories = listOf<String>()
    private var selectedCategory: String? = null

    override fun initData() {

    }

    override fun initView() {

        repository = AssetRepository(requireActivity())

        binding.rvLevels.layoutManager = GridLayoutManager(requireActivity(), 2)

        loadData()
    }

    override fun initActionView() {
    }

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            allLevels = repository.loadAllLevels()
            categories = allLevels.map { it.category }.distinct()

            renderCategoryTabs()
            if (categories.isNotEmpty()) {
                selectCategory(selectedCategory?.takeIf { it in categories } ?: categories.first())
            } else {
                selectedCategory = null
                binding.rvLevels.adapter = null
            }

            binding.progressBar.visibility = View.GONE
        }
    }

    private fun renderCategoryTabs() {
        binding.layoutCategories.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        categories.forEachIndexed { index, category ->
            val tabBinding =
                ItemLibraryCategoryTabBinding.inflate(inflater, binding.layoutCategories, false)
            tabBinding.tvCategoryTab.text = category
            tabBinding.tvCategoryTab.setOnClickListener {
                selectCategory(category)
            }

            if (index == categories.lastIndex) {
                val layoutParams =
                    tabBinding.tvCategoryTab.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                layoutParams?.marginEnd = 0
            }

            binding.layoutCategories.addView(tabBinding.root)
        }
        updateTabSelection()
    }

    private fun selectCategory(category: String) {
        selectedCategory = category
        updateTabSelection()
        filterLevels(category)
    }

    private fun updateTabSelection() {
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

    private fun filterLevels(category: String) {
        val filteredList = allLevels.filter { it.category == category }
        val adapter = LevelAdapter(filteredList, lifecycleScope) { level ->
            val intent = Intent(requireActivity(), PaintActivity::class.java)
            intent.putExtra("CATEGORY", level.category)
            intent.putExtra("LEVEL_ID", level.id)
            startActivity(intent)
        }
        binding.rvLevels.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại RecyclerView khi quay lại từ PaintActivity để load lại Thumbnail mới nhất
        binding.rvLevels.adapter?.notifyDataSetChanged()
    }

}
