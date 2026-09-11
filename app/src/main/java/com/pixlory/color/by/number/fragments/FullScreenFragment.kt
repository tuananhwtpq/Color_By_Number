package com.pixlory.color.by.number.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.activities.IntroActivity
import com.pixlory.color.by.number.bases.BaseFragment
import com.pixlory.color.by.number.databinding.FragmentFullScreenBinding


class FullScreenFragment :
    BaseFragment<FragmentFullScreenBinding>(FragmentFullScreenBinding::inflate) {

    override fun initData() {

    }

    override fun initView() {

    }

    override fun initActionView() {

        binding.ivClose.setOnClickListener {
            (activity as? IntroActivity)?.nextPage()
        }
    }

    override fun onResume() {
        super.onResume()
//        val position = arguments?.getInt("position") ?: -1
//        (activity as? IntroActivity)?.showNativeFullScreen(binding.frNative)
    }

}