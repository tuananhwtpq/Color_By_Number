package com.pixlory.color.by.number.dialog

import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentWatchAdsDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

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
