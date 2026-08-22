package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentAreaLockedDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


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
