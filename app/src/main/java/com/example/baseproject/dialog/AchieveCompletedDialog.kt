package com.example.baseproject.dialog

import android.view.View
import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.data.Achievement
import com.example.baseproject.databinding.FragmentAchieveCompletedDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


/**
 * Dialog cho achievement ĐÃ đạt: huy hiệu bản màu kèm ngày hoàn thành.
 * [achievement] phải được gán trước khi show — BaseDialog dựng view ngay trong onCreateDialog.
 */
class AchieveCompletedDialog : BaseDialog<FragmentAchieveCompletedDialogBinding>(
    FragmentAchieveCompletedDialogBinding::inflate
) {

    companion object {
        const val TAG = "AchieveCompletedDialog"
        private const val DATE_PATTERN = "d MMMM yyyy"
    }

    var achievement: Achievement? = null
    var onRewardClaimed: ((Achievement) -> Unit)? = null

    override fun initView() {
        val achievement = achievement ?: return
        val definition = achievement.definition

        val iconUrl = definition.iconCompletedUrl ?: definition.iconUrl
        val iconRes = definition.iconCompletedRes ?: definition.iconRes
        if (!iconUrl.isNullOrBlank()) {
            Glide.with(binding.ivAchieveImage)
                .load(iconUrl)
                .placeholder(iconRes ?: R.drawable.ic_mini_achieve)
                .error(iconRes ?: R.drawable.ic_mini_achieve)
                .into(binding.ivAchieveImage)
        } else {
            binding.ivAchieveImage.setImageResource(iconRes ?: R.drawable.ic_mini_achieve)
        }
        binding.ivAchieveImage.alpha = if (iconRes != null || iconUrl != null) 1f else 0.4f

        binding.tvAchieveName.text = definition.titleText(requireContext())
        binding.tvAchieveDetail.text = definition.descriptionText(requireContext())
        binding.tvDateTime.text = achievement.unlockedAtMillis?.let(::formatUnlockedDate).orEmpty()
        renderRewardState(achievement.isRewardClaimed)
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnHint.setOnUnDoubleClick { claimReward() }
        binding.btnClaim.setOnUnDoubleClick { claimReward() }
    }

    private fun claimReward() {
        val achievement = achievement ?: return
        if (achievement.isRewardClaimed) return

        onRewardClaimed?.invoke(achievement)
        this.achievement = achievement.copy(isRewardClaimed = true)
        renderRewardState(isRewardClaimed = true)
    }

    private fun renderRewardState(isRewardClaimed: Boolean) {
        val claimedVisibility = if (isRewardClaimed) View.VISIBLE else View.GONE
        val claimVisibility = if (isRewardClaimed) View.GONE else View.VISIBLE

        binding.tvCompleted.visibility = claimedVisibility
        binding.tvAchieveDetail.visibility = claimedVisibility
        binding.tvDateTime.visibility = claimedVisibility
        binding.llHint.visibility = claimVisibility
        binding.btnClaim.visibility = claimVisibility
    }

    // Locale.getDefault() bám theo ngôn ngữ người dùng chọn trong app (BaseActivity đã set).
    private fun formatUnlockedDate(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(DATE_PATTERN, Locale.getDefault()))

}
