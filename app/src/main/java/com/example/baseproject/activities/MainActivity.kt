package com.example.baseproject.activities

import android.content.Intent
import android.view.View
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.baseproject.R
import com.example.baseproject.adapters.LevelAdapter
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.AssetRepository
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private lateinit var tabLayout: TabLayout
    private lateinit var rvLevels: RecyclerView
    private lateinit var progressBar: ProgressBar

    private lateinit var repository: AssetRepository
    private var allLevels = listOf<LevelConfig>()
    private var categories = listOf<String>()

    override fun initData() {

    }

    override fun initView() {
        tabLayout = findViewById(R.id.tabLayout)
        rvLevels = findViewById(R.id.rvLevels)
        progressBar = findViewById(R.id.progressBar)

        repository = AssetRepository(this)

        rvLevels.layoutManager = GridLayoutManager(this, 2)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
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
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            allLevels = repository.loadAllLevels()
            categories = allLevels.map { it.category }.distinct()

            // Setup Tabs
            tabLayout.removeAllTabs()
            for (category in categories) {
                tabLayout.addTab(tabLayout.newTab().setText(category))
            }

            // Initial filter
            if (categories.isNotEmpty()) {
                filterLevels(categories.first())
            }

            progressBar.visibility = View.GONE
        }
    }

    private fun filterLevels(category: String) {
        val filteredList = allLevels.filter { it.category == category }
        val adapter = LevelAdapter(filteredList, lifecycleScope) { level ->
            val intent = Intent(this, PaintActivity::class.java)
            intent.putExtra("CATEGORY", level.category)
            intent.putExtra("LEVEL_ID", level.id)
            startActivity(intent)
        }
        rvLevels.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại RecyclerView khi quay lại từ PaintActivity để load lại Thumbnail mới nhất
        rvLevels.adapter?.notifyDataSetChanged()
    }

}