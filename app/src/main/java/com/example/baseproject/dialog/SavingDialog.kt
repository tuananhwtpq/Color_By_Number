package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentSavingDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


class SavingDialog : BaseDialog<FragmentSavingDialogBinding>(FragmentSavingDialogBinding::inflate) {
    companion object {
        const val TAG = "SavingDialog"
    }

    var onClose: (() -> Unit)? = null

    override fun initView() {
        isCancelable = false
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick {
            onClose?.invoke()
            dismiss()
        }
    }

}
