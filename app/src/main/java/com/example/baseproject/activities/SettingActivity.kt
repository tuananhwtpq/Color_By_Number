package com.example.baseproject.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.baseproject.R
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivitySettingBinding
import com.example.baseproject.utils.Common
import com.example.baseproject.utils.setOnUnDoubleClick

class SettingActivity : BaseActivity<ActivitySettingBinding>(ActivitySettingBinding::inflate) {


    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    override fun initData() {

    }

    override fun initView() {
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnLanguage.setOnUnDoubleClick {
            startActivity(Intent(this, LanguageActivity::class.java))
        }

        binding.btnFeedback.setOnUnDoubleClick {
            Common.feedbackApp(this)
        }

        binding.btnShareApp.setOnUnDoubleClick {
            Common.shareApp(this)
        }

        binding.btnPrivacy.setOnUnDoubleClick {
            Common.gotoPrivacyPolicy(this)
        }

        binding.btnAppInfo.setOnUnDoubleClick {
            // nav to app info
        }
    }

}