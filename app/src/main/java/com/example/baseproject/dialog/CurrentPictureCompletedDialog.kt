package com.example.baseproject.dialog

import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentCurrentPictureCompletedDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick
import java.io.File


class CurrentPictureCompletedDialog : BaseDialog<FragmentCurrentPictureCompletedDialogBinding>(
    FragmentCurrentPictureCompletedDialogBinding::inflate
) {
    companion object {
        const val TAG = "CurrentPictureCompletedDialog"
    }

    var previewFile: File? = null
    var onReset: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null

    override fun initView() {
        Glide.with(this)
            .load(previewFile)
            .placeholder(R.color.white)
            .error(R.color.white)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(binding.ivImage)
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnReset.setOnUnDoubleClick {
            onReset?.invoke()
            dismiss()
        }
        binding.btnSave.setOnUnDoubleClick {
            onSave?.invoke()
            dismiss()
        }
        binding.btnShare.setOnUnDoubleClick {
            onShare?.invoke()
            dismiss()
        }
    }

}
