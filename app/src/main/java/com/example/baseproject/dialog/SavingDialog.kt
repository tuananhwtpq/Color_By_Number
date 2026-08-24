package com.example.baseproject.dialog

import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentSavingDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


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
