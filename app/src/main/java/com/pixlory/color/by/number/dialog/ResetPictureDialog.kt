package com.pixlory.color.by.number.dialog

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentResetPictureDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

class ResetPictureDialog : BaseDialog<FragmentResetPictureDialogBinding>(
    FragmentResetPictureDialogBinding::inflate
) {
    var onRestart: (() -> Unit)? = null

    override fun initView() {

    }

    override fun initActionView() {
        binding.btnCancel.setOnUnDoubleClick {
            dismiss()
        }

        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnRestart.setOnUnDoubleClick {
            onRestart?.invoke()
            dismiss()
        }
    }

}