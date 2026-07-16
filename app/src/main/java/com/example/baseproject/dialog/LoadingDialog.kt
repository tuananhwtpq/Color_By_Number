package com.example.baseproject.dialog

import android.content.Context
import android.view.View
import com.example.baseproject.bases.FullBaseDialog
import com.example.baseproject.databinding.FragmentLoadingDialogBinding


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