package com.pixlory.color.by.number.fragments

import android.content.Intent
import android.view.View
import com.pixlory.color.by.number.activities.AppInfoActivity
import com.pixlory.color.by.number.activities.LanguageActivity
import com.pixlory.color.by.number.activities.ThemeActivity
import com.pixlory.color.by.number.bases.BaseFragment
import com.pixlory.color.by.number.databinding.ActivitySettingBinding
import com.pixlory.color.by.number.dialog.HighlightAreaDialog
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.Common
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.utils.soundManagerOrNull

class SettingFragment : BaseFragment<ActivitySettingBinding>(ActivitySettingBinding::inflate) {

    override fun initData() {
    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)
        renderPaintSettings()
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.main)
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
            startActivity(Intent(requireActivity(), AppInfoActivity::class.java))
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

        binding.btnBackgroundMusic.setOnUnDoubleClick {
            toggleBackgroundMusic()
        }

        binding.btnBackgroundMusicSwitch.setOnUnDoubleClick {
            toggleBackgroundMusic()
        }

        binding.btnSoundEffect.setOnUnDoubleClick {
            toggleSoundEffects()
        }

        binding.btnSoundEffectSwitch.setOnUnDoubleClick {
            toggleSoundEffects()
        }
    }

    private fun renderPaintSettings() {
        binding.btnSwitchColor.isSelected = SharedPrefManager.isAutoSwitchColor
        binding.btnFillInAnimation.isSelected = SharedPrefManager.isFillInAnimation
        binding.btnBackgroundMusicSwitch.isSelected = SharedPrefManager.isBackgroundMusicEnabled
        binding.btnSoundEffectSwitch.isSelected = SharedPrefManager.isSoundEffectsEnabled
    }

    private fun toggleAutoSwitchColor() {
        SharedPrefManager.isAutoSwitchColor = !SharedPrefManager.isAutoSwitchColor
        renderPaintSettings()
    }

    private fun toggleFillInAnimation() {
        SharedPrefManager.isFillInAnimation = !SharedPrefManager.isFillInAnimation
        renderPaintSettings()
    }

    private fun toggleBackgroundMusic() {
        requireContext().soundManagerOrNull()?.setBackgroundMusicEnabled(
            !SharedPrefManager.isBackgroundMusicEnabled
        )
        renderPaintSettings()
    }

    private fun toggleSoundEffects() {
        requireContext().soundManagerOrNull()?.setSoundEffectsEnabled(
            !SharedPrefManager.isSoundEffectsEnabled
        )
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
