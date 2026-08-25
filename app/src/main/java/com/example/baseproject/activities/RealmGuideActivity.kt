package com.example.baseproject.activities

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.baseproject.R
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityRealmGuideBinding
import com.example.baseproject.utils.AppThemeManager
import com.example.baseproject.utils.setOnUnDoubleClick

class RealmGuideActivity : BaseActivity<ActivityRealmGuideBinding>(ActivityRealmGuideBinding::inflate) {

    private val onBackPressedCallback = object : OnBackPressedCallback(true){
        override fun handleOnBackPressed() {
            finish()
        }
    }


    override fun initData() {
    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.main)
    }

    override fun initActionView() {

        binding.btnClose.setOnUnDoubleClick {
            onBackPressedDispatcher.onBackPressed()
        }
    }

}
