package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentRewardClaimDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick


class RewardClaimDialog : BaseDialog<FragmentRewardClaimDialogBinding>(
    FragmentRewardClaimDialogBinding::inflate
) {
    companion object {
        const val TAG = "RewardClaimDialog"
    }

    override fun initView() {

    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }
}
