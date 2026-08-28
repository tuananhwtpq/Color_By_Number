package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentWatchAdsDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

class WatchAdsDialog : BaseDialog<FragmentWatchAdsDialogBinding>(
    FragmentWatchAdsDialogBinding::inflate
) {
    companion object {
        const val TAG = "WatchAdsDialog"
    }

    var onWatchAd: (() -> Unit)? = null

    override fun initView() {

    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnCancel.setOnUnDoubleClick { dismiss() }
        binding.btnWatchAd.setOnUnDoubleClick {
            dismiss()
            onWatchAd?.invoke()
        }
    }

}
