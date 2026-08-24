package com.example.baseproject.dialog

import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentSavePicSuccessDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


class SavePicSuccessDialog : BaseDialog<FragmentSavePicSuccessDialogBinding>(
    FragmentSavePicSuccessDialogBinding::inflate
) {
    companion object {
        const val TAG = "SavePicSuccessDialog"
        private const val ARG_CONTENT_RES = "CONTENT_RES"

        fun newInstance(contentRes: Int): SavePicSuccessDialog =
            SavePicSuccessDialog().apply {
                arguments = android.os.Bundle().apply {
                    putInt(ARG_CONTENT_RES, contentRes)
                }
            }
    }

    override fun initView() {
        val contentRes = arguments?.getInt(
            ARG_CONTENT_RES,
            R.string.picture_was_saved_to_your_device
        ) ?: R.string.picture_was_saved_to_your_device
        binding.tvContent.setText(contentRes)
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }

}
