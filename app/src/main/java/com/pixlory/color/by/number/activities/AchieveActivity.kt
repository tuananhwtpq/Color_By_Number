package com.pixlory.color.by.number.activities

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.adapters.AchievementAdapter
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.Achievement
import com.pixlory.color.by.number.databinding.ActivityAchieveBinding
import com.pixlory.color.by.number.dialog.AchieveCompletedDialog
import com.pixlory.color.by.number.dialog.AchieveDetailDialog
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var loadAchievementsJob: Job? = null

    override fun initData() {

    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)

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
        AppThemeManager.applyFullBackground(binding.main)
        loadAchievements()
    }

    private fun loadAchievements() {
        loadAchievementsJob?.cancel()
        loadAchievementsJob = lifecycleScope.launch {
            val loadedAchievements = try {
                achievementRepository.loadAchievements()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            achievements = loadedAchievements
            renderList()
        }
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
                AchieveCompletedDialog().apply {
                    this.achievement = achievement
                    onRewardClaimed = { claimedAchievement ->
                        val updatedHintBalance =
                            SharedPrefManager.grantAchievementHintOnce(claimedAchievement.id)
                        achievementRepository.claimReward(claimedAchievement.id)
                        loadAchievements()
                        updatedHintBalance
                    }
                }
            }
        } else {
            showDialogOnce(AchieveDetailDialog.TAG) {
                AchieveDetailDialog().apply { this.achievement = achievement }
            }
        }
    }

    override fun onDestroy() {
        loadAchievementsJob?.cancel()
        loadAchievementsJob = null
        super.onDestroy()
    }
}
