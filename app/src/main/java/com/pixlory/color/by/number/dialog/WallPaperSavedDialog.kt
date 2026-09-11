package com.pixlory.color.by.number.dialog

import com.bumptech.glide.Glide
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentWallPaperSavedDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick


class WallPaperSavedDialog : BaseDialog<FragmentWallPaperSavedDialogBinding>(
    FragmentWallPaperSavedDialogBinding::inflate,
) {

    companion object {
        const val TAG = "WallPaperSavedDialog"
    }

    override fun initView() {
        Glide.with(this)
            .asGif()
            .load(R.raw.complete_gift)
            .into(binding.ivLottie)
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
    }

}
