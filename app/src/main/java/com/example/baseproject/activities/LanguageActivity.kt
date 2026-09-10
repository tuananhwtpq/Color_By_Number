package com.example.baseproject.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.R
import com.example.baseproject.adapters.LanguageAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.databinding.ActivityLanguageBinding
import com.example.baseproject.ui.language.LanguageUiEvent
import com.example.baseproject.ui.language.LanguageViewModel
import com.example.baseproject.utils.Constants
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.gone
import com.example.baseproject.utils.visible
import com.example.baseproject.utils.SoundScene
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LanguageActivity : BaseActivity<ActivityLanguageBinding>(ActivityLanguageBinding::inflate) {
    override val soundScene = SoundScene.SILENT

    companion object {
        const val EXTRA_FROM_SPLASH = "EXTRA_FROM_SPLASH"
        private const val PREPARING_MIN_DURATION_MS = 900L
        private const val PREPARING_MAX_DURATION_MS = 5_500L
        private const val TAG = "LanguageActivity"
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
    private var preparationJob: Job? = null
    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    override fun initData() {
        isFromHome = !intent.getBooleanExtra(EXTRA_FROM_SPLASH, false) &&
                intent.getBooleanExtra(Constants.LANGUAGE_EXTRA, true)
        viewModel.initialize(isFromHome)
        if (!isFromHome && !SharedPrefManager.hasSeenLibraryPreparing) {
            appContainer.startupContentPreloader.start()
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
                    LanguageUiEvent.RequestNotificationPermission -> requestNotiPer()
                    is LanguageUiEvent.ShowToast -> Toast.makeText(
                        this@LanguageActivity,
                        getString(event.messageRes),
                        Toast.LENGTH_SHORT
                    ).show()

                    LanguageUiEvent.NavigateToIntro -> prepareThenOpenMain()

                    LanguageUiEvent.NavigateToMain -> openMain(skipPreparingOverlay = false)
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

    private fun prepareThenOpenMain() {
        if (isOpeningMain) return
        isOpeningMain = true
        binding.ivDone.isEnabled = false
        val preload = appContainer.startupContentPreloader.start()
        binding.contentPreparingOverlay.apply {
            alpha = 1f
            visibility = View.VISIBLE
            bringToFront()
        }

        preparationJob = lifecycleScope.launch {
            val shownAt = SystemClock.elapsedRealtime()
            val preloadResult = withTimeoutOrNull(PREPARING_MAX_DURATION_MS) {
                preload.await()
            }
            val remainingMinimumDuration =
                (PREPARING_MIN_DURATION_MS - (SystemClock.elapsedRealtime() - shownAt)).coerceAtLeast(0L)
            delay(remainingMinimumDuration)

            openMainAfterPreload(preloadResult)
        }
    }

    private fun openMainAfterPreload(preloadResult: Result<*>?) {
        when {
            preloadResult?.isSuccess == true -> {
                SharedPrefManager.hasSeenLibraryPreparing = true
                openMain(skipPreparingOverlay = true, seamless = true)
            }

            preloadResult == null -> {
                Log.w(TAG, "Initial library preload timed out; Library will keep loading in Main")
                openMain(skipPreparingOverlay = false)
            }

            else -> {
                val error = preloadResult.exceptionOrNull()
                Log.w(TAG, "Initial library preload failed; retrying from Main", error)
                Toast.makeText(
                    this@LanguageActivity,
                    R.string.failed_to_load_level,
                    Toast.LENGTH_SHORT
                ).show()
                appContainer.startupContentPreloader.retryAfterFailure()
                openMain(skipPreparingOverlay = false)
            }
        }
    }

    private fun openMain(skipPreparingOverlay: Boolean, seamless: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val intent = Intent(this@LanguageActivity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (skipPreparingOverlay) {
                putExtra(MainActivity.EXTRA_SKIP_PREPARING_OVERLAY, true)
            }
        }
        startActivity(intent)
        if (seamless) {
            // Keep the Preparing surface visually continuous until Main draws its first frame,
            // matching the previous Intro -> Main hand-off.
            overridePendingTransition(0, 0)
            finish()
        }
    }

    override fun onDestroy() {
        preparationJob?.cancel()
        super.onDestroy()
    }

}
