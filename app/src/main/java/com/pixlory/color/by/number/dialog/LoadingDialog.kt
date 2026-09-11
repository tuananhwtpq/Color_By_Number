package com.pixlory.color.by.number.dialog

import android.content.Context
import android.view.View
import com.pixlory.color.by.number.bases.FullBaseDialog
import com.pixlory.color.by.number.databinding.FragmentLoadingDialogBinding


class LoadingDialog(context: Context) : FullBaseDialog<FragmentLoadingDialogBinding>(
    FragmentLoadingDialogBinding::inflate, context, false
) {
    override fun initView() {

    }

    override fun initData() {

    }

    override fun initActionView() {

    }

    override val layoutContainer: View
        get() = binding.root
}