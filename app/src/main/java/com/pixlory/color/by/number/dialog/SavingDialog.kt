package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentSavingDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick


class SavingDialog : BaseDialog<FragmentSavingDialogBinding>(FragmentSavingDialogBinding::inflate) {
    companion object {
        const val TAG = "SavingDialog"
        private const val ARG_CONTENT_RES = "CONTENT_RES"

        fun newInstance(contentRes: Int): SavingDialog =
            SavingDialog().apply {
                arguments = android.os.Bundle().apply {
                    putInt(ARG_CONTENT_RES, contentRes)
                }
            }
    }

    var onClose: (() -> Unit)? = null

    override fun initView() {
        isCancelable = false
        val contentRes = arguments?.getInt(
            ARG_CONTENT_RES,
            R.string.video_is_being_saved_to_your_device
        ) ?: R.string.video_is_being_saved_to_your_device
        binding.tvContent.setText(contentRes)
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick {
            onClose?.invoke()
            dismiss()
        }
    }

}
