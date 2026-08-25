package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentShareDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

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
