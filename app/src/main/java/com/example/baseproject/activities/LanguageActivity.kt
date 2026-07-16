package com.example.baseproject.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.R
import com.example.baseproject.adapters.LanguageAdapter
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityLanguageBinding
import com.example.baseproject.utils.Common
import com.example.baseproject.utils.Constants
import com.example.baseproject.utils.gone
import com.example.baseproject.utils.visible
import kotlinx.coroutines.launch

class LanguageActivity : BaseActivity<ActivityLanguageBinding>(ActivityLanguageBinding::inflate) {

    private var adapter: LanguageAdapter? = null
    private var isFromHome = true

    override fun initData() {
        isFromHome = intent.getBooleanExtra(Constants.LANGUAGE_EXTRA, true)
        if (!isFromHome) {
            lifecycleScope.launch {
                requestNotiPer()
            }
        }
    }

    override fun initView() {
        initLanguage()
    }

    override fun initActionView() {
        if (!isFromHome) {
            binding.ivBack.gone()
        } else {
            binding.ivBack.visible()
            binding.ivBack.setOnClickListener {
                finish()
            }
        }

        binding.ivDone.setOnClickListener {
            applyLanguage()
        }
    }

    private fun requestNotiPer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1000)
        }
    }

    private fun applyLanguage() {
        val curr = adapter?.getSelectedPositionLanguage()
        if (curr == null) {
            Toast.makeText(this,
                getString(R.string.please_select_language_first), Toast.LENGTH_SHORT).show()
            return
        }
        Common.setSelectedLanguage(curr)
        if (!isFromHome) {
            val intent = Intent(this@LanguageActivity, IntroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            val intent = Intent(this@LanguageActivity, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun initLanguage() {
        val languageList = Common.getLanguageList()
        adapter = LanguageAdapter(
            languageList
        ) {
            //todo: first select
        }
        binding.rcvLanguage.apply {
            layoutManager = LinearLayoutManager(this@LanguageActivity)
        }
        binding.rcvLanguage.adapter = adapter
        if (isFromHome) {
            adapter?.setSelectedPositionLanguage(Common.getSelectedLanguage())
        }
    }

}
