package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentAreaLockedDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick


class AreaLockedDialog : BaseDialog<FragmentAreaLockedDialogBinding>(
    FragmentAreaLockedDialogBinding::inflate,
) {

    companion object {
        const val TAG = "AreaLockedDialog"
    }

    override fun initView() {

    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }

}
