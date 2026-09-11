package com.pixlory.color.by.number.bases

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.pixlory.color.by.number.dialog.LoadingDialog
import com.pixlory.color.by.number.dialog.NoInternetDialog
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.utils.Common
import com.pixlory.color.by.number.utils.isNetworkAvailable
import com.pixlory.color.by.number.utils.SoundScene
import com.snake.squad.adslib.AdmobLib
import kotlinx.coroutines.launch
import java.util.Locale

abstract class BaseActivity<viewBinding : ViewBinding>(val inflater: (LayoutInflater) -> viewBinding) :
    AppCompatActivity() {

    val binding: viewBinding by lazy { inflater(layoutInflater) }
    open val shouldMonitorNetwork: Boolean = false
    protected open val soundScene: SoundScene = SoundScene.HOME
    private var connectivityManager: ConnectivityManager? = null
    private var isNetworkCallbackRegistered = false
    private var isNetworkMonitoringActive = false

    private val loadingDialog by lazy { LoadingDialog(this) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            showNoInternetIfNeeded()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                showNoInternetIfNeeded()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSelectedLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        savedInstanceState?.let {
            AdmobLib.onRestoreInstanceState(it)
        }
        setContentView(binding.root)
        initData()
        initView()
        initActionView()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        AdmobLib.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        AdmobLib.onRestoreInstanceState(savedInstanceState)
        super.onRestoreInstanceState(savedInstanceState)
    }

    abstract fun initData()

    abstract fun initView()

    abstract fun initActionView()

    protected fun collectWithLifecycle(
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
        collector: suspend () -> Unit
    ) {
        lifecycleScope.launch {
            repeatOnLifecycle(minActiveState) {
                collector()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? MyApplication)?.soundManager?.onSceneResumed(soundScene)
        if (shouldMonitorNetwork) {
            isNetworkMonitoringActive = true
            registerNetworkCallback()
            showNoInternetIfNeeded()
        }
    }

    override fun onPause() {
        (application as? MyApplication)?.soundManager?.onScenePaused(soundScene)
        isNetworkMonitoringActive = false
        super.onPause()
        if (shouldMonitorNetwork) {
            unregisterNetworkCallback()
        }
    }

    private fun registerNetworkCallback() {
        if (isNetworkCallbackRegistered) return

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            isNetworkCallbackRegistered = connectivityManager != null
        } catch (e: Exception) {
            isNetworkCallbackRegistered = false
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isNetworkCallbackRegistered) return

        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
        } finally {
            isNetworkCallbackRegistered = false
        }
    }

    private fun showNoInternetIfNeeded() {
        runOnUiThread {
            if (
                shouldMonitorNetwork &&
                isNetworkMonitoringActive &&
                !isFinishing &&
                !isDestroyed &&
                !isNetworkAvailable()
            ) {
                showNoInternetDialog()
            }
        }
    }

    fun showLoading(isShow: Boolean) {
        if (!isShow && loadingDialog.isShowing) {
            loadingDialog.dismiss()
        } else if (isShow && !loadingDialog.isShowing) {
            loadingDialog.show()
        }
    }

    private fun showNoInternetDialog() {
        showDialogOnce(NoInternetDialog.TAG) { NoInternetDialog.newInstance() }
    }

    // Chỉ cho phép đúng một dialog hiển thị tại một thời điểm: nếu đang có bất kỳ
    // DialogFragment nào trên màn hình thì bỏ qua yêu cầu mở dialog mới.
    protected fun showDialogOnce(tag: String, create: () -> DialogFragment) {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return

        val hasDialogShowing = supportFragmentManager.fragments.any {
            it is DialogFragment && it.isAdded
        }
        if (hasDialogShowing) return

        create().show(supportFragmentManager, tag)
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun Context.withSelectedLocale(): Context {
        val language = Common.getSelectedLanguage()
        val locale = Locale.forLanguageTag(language.key)
        Locale.setDefault(locale)
        val configuration = resources.configuration
        configuration.setLocale(locale)
        return createConfigurationContext(configuration)
    }

}
