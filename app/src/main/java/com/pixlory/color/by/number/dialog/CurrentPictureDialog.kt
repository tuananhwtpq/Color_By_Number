package com.pixlory.color.by.number.dialog

import com.bumptech.glide.Glide
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentCurrentPictureDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
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
