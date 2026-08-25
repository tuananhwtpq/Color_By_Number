package com.example.baseproject.fragments

import android.content.Intent
import android.view.View
import com.example.baseproject.activities.LanguageActivity
import com.example.baseproject.activities.ThemeActivity
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.databinding.ActivitySettingBinding
import com.example.baseproject.dialog.HighlightAreaDialog
import com.example.baseproject.utils.Common
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.setOnUnDoubleClick

class SettingFragment : BaseFragment<ActivitySettingBinding>(ActivitySettingBinding::inflate) {

    override fun initData() {
    }

    override fun initView() {
        binding.btnBack.visibility = View.GONE
        renderPaintSettings()
    }

    override fun initActionView() {
        binding.btnLanguage.setOnUnDoubleClick {
            startActivity(Intent(requireContext(), LanguageActivity::class.java))
        }

        binding.btnTheme.setOnUnDoubleClick {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }

        binding.btnFeedback.setOnUnDoubleClick {
            Common.feedbackApp(requireContext())
        }

        binding.btnShareApp.setOnUnDoubleClick {
            Common.shareApp(requireContext())
        }

        binding.btnPrivacy.setOnUnDoubleClick {
            Common.gotoPrivacyPolicy(requireContext())
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
        if (parentFragmentManager.isStateSaved) return
        val hasDialogShowing = parentFragmentManager.fragments.any {
            it is androidx.fragment.app.DialogFragment && it.isAdded
        }
        if (hasDialogShowing) return

        HighlightAreaDialog.newInstance(SharedPrefManager.highlightThemeId).apply {
            onThemeSelected = { themeId ->
                SharedPrefManager.highlightThemeId = themeId
            }
        }.show(parentFragmentManager, HighlightAreaDialog.TAG)
    }
}
