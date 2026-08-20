package com.example.baseproject.dialog

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

    override fun initView() {
        val achievement = achievement ?: return
        val definition = achievement.definition

        val iconRes = definition.iconCompletedRes ?: definition.iconRes
        binding.ivAchieveImage.setImageResource(iconRes ?: R.drawable.ic_mini_achieve)
        binding.ivAchieveImage.alpha = if (iconRes != null) 1f else 0.4f

        binding.tvAchieveName.text = getString(definition.titleRes)
        binding.tvAchieveDetail.text = getString(definition.descriptionRes)
        binding.tvDateTime.text = achievement.unlockedAtMillis?.let(::formatUnlockedDate).orEmpty()
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }

    // Locale.getDefault() bám theo ngôn ngữ người dùng chọn trong app (BaseActivity đã set).
    private fun formatUnlockedDate(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(DATE_PATTERN, Locale.getDefault()))

}
