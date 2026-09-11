package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentNeedMorePaintDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick


class NeedMorePaintDialog : BaseDialog<FragmentNeedMorePaintDialogBinding>(
    FragmentNeedMorePaintDialogBinding::inflate,
) {

    companion object {
        const val TAG = "NeedMorePaintDialog"
    }

    var onGoToLibrary: (() -> Unit)? = null

    override fun initView() {
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnGoToLibrary.setOnUnDoubleClick {
            dismiss()
            onGoToLibrary?.invoke()
        }
    }
}
