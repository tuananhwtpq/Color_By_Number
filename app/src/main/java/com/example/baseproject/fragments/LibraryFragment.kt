package com.example.baseproject.fragments

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.baseproject.activities.PaintActivity
import com.example.baseproject.adapters.LevelAdapter
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.AssetRepository
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.databinding.FragmentLibraryBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class LibraryFragment : BaseFragment<FragmentLibraryBinding>(FragmentLibraryBinding::inflate) {

    private lateinit var repository: AssetRepository
    private var allLevels = listOf<LevelConfig>()
    private var categories = listOf<String>()

    override fun initData() {

    }

    override fun initView() {

        repository = AssetRepository(requireActivity())

        binding.rvLevels.layoutManager = GridLayoutManager(requireActivity(), 2)

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.text?.let { category ->
                    filterLevels(category.toString())
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        loadData()
    }

    override fun initActionView() {
    }

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            allLevels = repository.loadAllLevels()
            categories = allLevels.map { it.category }.distinct()

            // Setup Tabs
            binding.tabLayout.removeAllTabs()
            for (category in categories) {
                binding.tabLayout.addTab(binding.tabLayout.newTab().setText(category))
            }

            // Initial filter
            if (categories.isNotEmpty()) {
                filterLevels(categories.first())
            }

            binding.progressBar.visibility = View.GONE
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