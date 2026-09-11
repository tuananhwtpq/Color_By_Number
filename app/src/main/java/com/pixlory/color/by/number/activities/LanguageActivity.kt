package com.pixlory.color.by.number.activities

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.adapters.LanguageAdapter
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.databinding.ActivityLanguageBinding
import com.pixlory.color.by.number.ui.language.LanguageUiEvent
import com.pixlory.color.by.number.ui.language.LanguageViewModel
import com.pixlory.color.by.number.utils.Constants
import com.pixlory.color.by.number.utils.SoundScene
import com.pixlory.color.by.number.utils.gone
import com.pixlory.color.by.number.utils.visible
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LanguageActivity : BaseActivity<ActivityLanguageBinding>(ActivityLanguageBinding::inflate) {
    override val soundScene = SoundScene.SILENT

    companion object {
        const val EXTRA_FROM_SPLASH = "EXTRA_FROM_SPLASH"
    }

    private val viewModel: LanguageViewModel by viewModels {
        val appContainer = (application as MyApplication).appContainer
        SimpleViewModelFactory {
            LanguageViewModel(appContainer.settingsRepository)
        }
    }
    private var adapter: LanguageAdapter? = null
    private var isFromHome = true
    private var isOpeningMain = false

    override fun initData() {
        isFromHome = !intent.getBooleanExtra(EXTRA_FROM_SPLASH, false) &&
                intent.getBooleanExtra(Constants.LANGUAGE_EXTRA, true)
        viewModel.initialize(isFromHome)
        if (!isFromHome) {
            lifecycleScope.launch {
                requestNotiPer()
            }
        }
    }

    override fun initView() {
        binding.rcvLanguage.layoutManager = LinearLayoutManager(this@LanguageActivity)

        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                if (adapter == null && state.languages.isNotEmpty()) {
                    adapter = LanguageAdapter(state.languages) {}
                    binding.rcvLanguage.adapter = adapter
                }
                state.selectedLanguage?.let { adapter?.setSelectedPositionLanguage(it) }
            }
        }

        collectWithLifecycle {
            viewModel.events.collectLatest { event ->
                when (event) {
                    is LanguageUiEvent.ShowToast -> Toast.makeText(
                        this@LanguageActivity,
                        getString(event.messageRes),
                        Toast.LENGTH_SHORT
                    ).show()

                    LanguageUiEvent.NavigateToMainWithPreparing -> openMain(
                        showPreparingOverlay = true
                    )

                    LanguageUiEvent.NavigateToMain -> openMain(showPreparingOverlay = false)
                }
            }
        }
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
            viewModel.applyLanguage(adapter?.getSelectedPositionLanguage())
        }
    }

    private fun requestNotiPer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1000)
        }
    }

    private fun openMain(showPreparingOverlay: Boolean) {
        if (isOpeningMain) return
        isOpeningMain = true
        binding.ivDone.isEnabled = false

        if (showPreparingOverlay) {
            binding.contentPreparingOverlay.apply {
                alpha = 1f
                visibility = View.VISIBLE
                bringToFront()
                doOnPreDraw {
                    postOnAnimation { launchMain(showPreparingOverlay = true) }
                }
            }
        } else {
            launchMain(showPreparingOverlay = false)
        }
    }

    private fun launchMain(showPreparingOverlay: Boolean) {
        val intent = if (showPreparingOverlay) {
            Intent().apply {
                component = ComponentName(
                    packageName,
                    "$packageName.activities.MainPreparingEntry"
                )
                putExtra(MainActivity.EXTRA_SHOW_LIBRARY_PREPARING, true)
            }
        } else {
            Intent(this@LanguageActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }

}
