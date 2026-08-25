package com.example.baseproject.dialog

import android.view.View
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

        val iconRes = definition.iconCompletedRes ?: definition.iconRes
        binding.ivAchieveImage.setImageResource(iconRes ?: R.drawable.ic_mini_achieve)
        binding.ivAchieveImage.alpha = if (iconRes != null) 1f else 0.4f

        binding.tvAchieveName.text = getString(definition.titleRes)
        binding.tvAchieveDetail.text = getString(definition.descriptionRes)
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
