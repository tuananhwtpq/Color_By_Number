package com.example.baseproject.activities

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivitySettingBinding
import com.example.baseproject.dialog.HighlightAreaDialog
import com.example.baseproject.utils.Common
import com.example.baseproject.utils.SharedPrefManager
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
        renderPaintSettings()
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

        binding.btnHighLight.setOnUnDoubleClick {
            showHighlightAreaDialog()
        }

        binding.btnAutoSwitchColor.setOnUnDoubleClick {
            toggleAutoSwitchColor()
        }

        binding.btnSwitchColor.setOnUnDoubleClick {
            toggleAutoSwitchColor()
        }

        binding.btnFillInAnim.setOnUnDoubleClick {
            toggleFillInAnimation()
        }

        binding.btnFillInAnimation.setOnUnDoubleClick {
            toggleFillInAnimation()
        }
    }

    private fun renderPaintSettings() {
        binding.btnSwitchColor.isSelected = SharedPrefManager.isAutoSwitchColor
        binding.btnFillInAnimation.isSelected = SharedPrefManager.isFillInAnimation
    }

    private fun toggleAutoSwitchColor() {
        SharedPrefManager.isAutoSwitchColor = !SharedPrefManager.isAutoSwitchColor
        renderPaintSettings()
    }

    private fun toggleFillInAnimation() {
        SharedPrefManager.isFillInAnimation = !SharedPrefManager.isFillInAnimation
        renderPaintSettings()
    }

    private fun showHighlightAreaDialog() {
        showDialogOnce(HighlightAreaDialog.TAG) {
            HighlightAreaDialog.newInstance(SharedPrefManager.highlightThemeId).apply {
                onThemeSelected = { themeId ->
                    SharedPrefManager.highlightThemeId = themeId
                }
            }
        }
    }

}
