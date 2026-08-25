package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentSaveDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

class SaveDialog : BaseDialog<FragmentSaveDialogBinding>(
    FragmentSaveDialogBinding::inflate
) {
    companion object {
        const val TAG = "SaveDialog"
    }

    var onSavePicture: (() -> Unit)? = null
    var onSaveVideo: (() -> Unit)? = null

    override fun initView() {

    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnPicture.setOnUnDoubleClick {
            dismiss()
            onSavePicture?.invoke()
        }
        binding.btnVideo.setOnUnDoubleClick {
            dismiss()
            onSaveVideo?.invoke()
        }
    }

}
