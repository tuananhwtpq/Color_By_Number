package com.example.baseproject.dialog

import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentWallPaperSavedDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick


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
