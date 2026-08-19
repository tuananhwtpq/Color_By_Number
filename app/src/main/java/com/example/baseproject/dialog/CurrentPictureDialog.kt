package com.example.baseproject.dialog

import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentCurrentPictureDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick
import java.io.File


class CurrentPictureDialog : BaseDialog<FragmentCurrentPictureDialogBinding>(
    FragmentCurrentPictureDialogBinding::inflate
) {
    var previewFile: File? = null
    var onColor: (() -> Unit)? = null
    var onReset: (() -> Unit)? = null

    override fun initView() {
        Glide.with(this)
            .load(previewFile)
            .placeholder(R.color.white)
            .error(R.color.white)
            .skipMemoryCache(true)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
            .into(binding.ivImage)

    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnColor.setOnUnDoubleClick {
            onColor?.invoke()
            dismiss()
        }
        binding.btnReset.setOnUnDoubleClick {
            onReset?.invoke()
        }
    }

}
