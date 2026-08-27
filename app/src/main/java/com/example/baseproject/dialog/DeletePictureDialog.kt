package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentDeletePictureDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


class DeletePictureDialog : BaseDialog<FragmentDeletePictureDialogBinding>(
    FragmentDeletePictureDialogBinding::inflate
) {
    companion object {
        const val TAG = "DeletePictureDialog"
    }

    var onDelete: (() -> Unit)? = null

    override fun initView() {

    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnCancel.setOnUnDoubleClick { dismiss() }
        binding.btnDelete.setOnUnDoubleClick {
            onDelete?.invoke()
            dismiss()
        }
    }

}
