package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentDeletePictureDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick


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
