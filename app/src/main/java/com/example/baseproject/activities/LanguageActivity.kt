package com.example.baseproject.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.adapters.LanguageAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityLanguageBinding
import com.example.baseproject.ui.language.LanguageUiEvent
import com.example.baseproject.ui.language.LanguageViewModel
import com.example.baseproject.utils.Constants
import com.example.baseproject.utils.gone
import com.example.baseproject.utils.visible
import kotlinx.coroutines.flow.collectLatest

class LanguageActivity : BaseActivity<ActivityLanguageBinding>(ActivityLanguageBinding::inflate) {

    private val viewModel: LanguageViewModel by viewModels {
        val appContainer = (application as MyApplication).appContainer
        SimpleViewModelFactory {
            LanguageViewModel(appContainer.settingsRepository)
        }
    }
    private var adapter: LanguageAdapter? = null
    private var isFromHome = true

    override fun initData() {
        isFromHome = intent.getBooleanExtra(Constants.LANGUAGE_EXTRA, true)
        viewModel.initialize(isFromHome)
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
                    LanguageUiEvent.RequestNotificationPermission -> requestNotiPer()
                    is LanguageUiEvent.ShowToast -> Toast.makeText(
                        this@LanguageActivity,
                        event.message,
                        Toast.LENGTH_SHORT
                    ).show()

                    LanguageUiEvent.NavigateToIntro -> {
                        val intent = Intent(this@LanguageActivity, IntroActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }

                    LanguageUiEvent.NavigateToMain -> {
                        val intent = Intent(this@LanguageActivity, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
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

}
