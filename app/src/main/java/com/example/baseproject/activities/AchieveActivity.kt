package com.example.baseproject.activities

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.adapters.AchievementAdapter
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.Achievement
import com.example.baseproject.databinding.ActivityAchieveBinding
import com.example.baseproject.dialog.AchieveCompletedDialog
import com.example.baseproject.dialog.AchieveDetailDialog
import com.example.baseproject.utils.setOnUnDoubleClick

class AchieveActivity : BaseActivity<ActivityAchieveBinding>(ActivityAchieveBinding::inflate) {

    private val onBackPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    private companion object {
        const val TAB_IN_PROGRESS = 0
        const val TAB_COMPLETED = 1
    }

    private val achievementRepository by lazy {
        (application as MyApplication).appContainer.achievementRepository
    }

    private val achievementAdapter by lazy {
        AchievementAdapter { achievement -> onAchievementClicked(achievement) }
    }

    private var selectedTab = TAB_IN_PROGRESS
    private var achievements: List<Achievement> = emptyList()

    override fun initData() {

    }

    override fun initView() {
        binding.rcvAchievements.layoutManager = GridLayoutManager(this, 2)
        binding.rcvAchievements.adapter = achievementAdapter
        updateTabSelection(TAB_IN_PROGRESS)

        onBackPressedDispatcher.addCallback(onBackPressCallback)
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick { onBackPressedDispatcher.onBackPressed() }
        binding.tvTabInProgress.setOnUnDoubleClick { updateTabSelection(TAB_IN_PROGRESS) }
        binding.tvTabCompleted.setOnUnDoubleClick { updateTabSelection(TAB_COMPLETED) }
    }

    override fun onResume() {
        super.onResume()
        achievements = achievementRepository.loadAchievements()
        renderList()
    }

    private fun updateTabSelection(tab: Int) {
        selectedTab = tab
        binding.tvTabInProgress.background = ContextCompat.getDrawable(
            this,
            if (tab == TAB_IN_PROGRESS) R.drawable.bg_library_category_tab_selected
            else R.drawable.bg_library_category_tab_unselected
        )
        binding.tvTabCompleted.background = ContextCompat.getDrawable(
            this,
            if (tab == TAB_COMPLETED) R.drawable.bg_library_category_tab_selected
            else R.drawable.bg_library_category_tab_unselected
        )
        renderList()
    }

    private fun renderList() {
        val visible = achievements.filter {
            if (selectedTab == TAB_IN_PROGRESS) !it.isCompleted else it.isCompleted
        }
        binding.tvNumberCount.text = visible.size.toString()
        binding.llEmptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        achievementAdapter.submitList(visible)
    }

    private fun onAchievementClicked(achievement: Achievement) {
        if (achievement.isCompleted) {
            showDialogOnce(AchieveCompletedDialog.TAG) {
                AchieveCompletedDialog().apply { this.achievement = achievement }
            }
        } else {
            showDialogOnce(AchieveDetailDialog.TAG) {
                AchieveDetailDialog().apply { this.achievement = achievement }
            }
        }
    }
}
