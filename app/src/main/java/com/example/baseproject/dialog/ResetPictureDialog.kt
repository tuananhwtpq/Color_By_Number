package com.example.baseproject.dialog

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentResetPictureDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

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