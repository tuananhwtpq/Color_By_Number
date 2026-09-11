package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentSaveDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

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
