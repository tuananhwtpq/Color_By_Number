package com.example.baseproject.dialog

import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.data.Achievement
import com.example.baseproject.databinding.FragmentAchieveDetailDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


class AchieveDetailDialog : BaseDialog<FragmentAchieveDetailDialogBinding>(
    FragmentAchieveDetailDialogBinding::inflate
) {

    companion object {
        const val TAG = "AchieveDetailDialog"
    }

    var achievement: Achievement? = null

    override fun initView() {
        val achievement = achievement ?: return
        val definition = achievement.definition

        if (!definition.iconUrl.isNullOrBlank()) {
            Glide.with(binding.ivAchieveImage)
                .load(definition.iconUrl)
                .placeholder(definition.iconRes ?: R.drawable.ic_mini_achieve)
                .error(definition.iconRes ?: R.drawable.ic_mini_achieve)
                .into(binding.ivAchieveImage)
        } else {
            binding.ivAchieveImage.setImageResource(definition.iconRes ?: R.drawable.ic_mini_achieve)
        }
        binding.ivAchieveImage.alpha =
            if (definition.iconRes != null || definition.iconUrl != null) 1f else 0.4f

        binding.tvAchieveName.text = definition.titleText(requireContext())
        binding.tvAchieveDetail.text = definition.descriptionText(requireContext())
        binding.progressAchieve.setProgress(
            achievement.currentCount,
            achievement.targetCount,
            animate = false
        )
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }

}
