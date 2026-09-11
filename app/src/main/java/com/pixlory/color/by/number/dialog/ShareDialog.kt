package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentShareDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

class ShareDialog : BaseDialog<FragmentShareDialogBinding>(
    FragmentShareDialogBinding::inflate
) {
    companion object {
        const val TAG = "ShareDialog"
    }

    var onSharePicture: (() -> Unit)? = null
    var onShareVideo: (() -> Unit)? = null

    override fun initView() {
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnPicture.setOnUnDoubleClick {
            onSharePicture?.invoke()
            dismiss()
        }
        binding.btnVideo.setOnUnDoubleClick {
            onShareVideo?.invoke()
            dismiss()
        }
    }
}
