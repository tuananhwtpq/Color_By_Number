package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.data.repository.PaintDropStats
import com.pixlory.color.by.number.databinding.FragmentPaintDropInfoDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

class PaintDropInfoDialog : BaseDialog<FragmentPaintDropInfoDialogBinding>(
    FragmentPaintDropInfoDialogBinding::inflate,
) {

    companion object {
        const val TAG = "PaintDropInfoDialog"
    }

    var stats: PaintDropStats = PaintDropStats(
        paintDrops = 0,
        daysExplored = 0,
        worksCompleted = 0,
        areasUnlocked = 0,
    )

    override fun initView() = with(binding) {
        tvPaintDropCount.text = stats.paintDrops.toString()
        tvDaysExplored.text = stats.daysExplored.toString()
        tvWorksCompleted.text = stats.worksCompleted.toString()
        tvAreasUnlocked.text = stats.areasUnlocked.toString()
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }
}
