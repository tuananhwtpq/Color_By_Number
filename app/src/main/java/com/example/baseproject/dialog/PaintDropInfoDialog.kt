package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.data.repository.PaintDropStats
import com.example.baseproject.databinding.FragmentPaintDropInfoDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

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
