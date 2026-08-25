package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentNeedMorePaintDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


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
