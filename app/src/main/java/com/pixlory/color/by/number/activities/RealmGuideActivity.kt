package com.pixlory.color.by.number.activities

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivityRealmGuideBinding
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

class RealmGuideActivity : BaseActivity<ActivityRealmGuideBinding>(ActivityRealmGuideBinding::inflate) {

    override val shouldMonitorNetwork = true

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
