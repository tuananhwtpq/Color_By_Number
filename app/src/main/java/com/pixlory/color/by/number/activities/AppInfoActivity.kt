package com.pixlory.color.by.number.activities

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivityAppInfoBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

class AppInfoActivity : BaseActivity<ActivityAppInfoBinding>(ActivityAppInfoBinding::inflate) {

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    override fun initData() {
    }

    override fun initView() {
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = getString(R.string.version, version)

    }

    override fun initActionView() {

        binding.ivBack.setOnUnDoubleClick {
            onBackPressedDispatcher.onBackPressed()
        }
    }

}