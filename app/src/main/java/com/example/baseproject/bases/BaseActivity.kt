package com.example.baseproject.bases

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
import androidx.viewbinding.ViewBinding
import com.example.baseproject.dialog.LoadingDialog
import com.example.baseproject.dialog.NoInternetDialog
import com.example.baseproject.utils.Common
import com.example.baseproject.utils.isNetworkAvailable
import com.snake.squad.adslib.AdmobLib
import java.util.Locale

abstract class BaseActivity<viewBinding : ViewBinding>(val inflater: (LayoutInflater) -> viewBinding) :
    AppCompatActivity() {

    val binding: viewBinding by lazy { inflater(layoutInflater) }
    open val shouldMonitorNetwork: Boolean = false
    private var connectivityManager: ConnectivityManager? = null

    private val loadingDialog by lazy { LoadingDialog(this) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    showNoInternetDialog()
                }
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

    override fun onResume() {
        super.onResume()
        if (shouldMonitorNetwork) {
            if (!isNetworkAvailable()) {
                showNoInternetDialog()
            }
            registerNetworkCallback()
        }
    }

    override fun onPause() {
        super.onPause()
        if (shouldMonitorNetwork) {
            unregisterNetworkCallback()
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {

        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
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
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return

        val exist = supportFragmentManager.findFragmentByTag(NoInternetDialog.TAG)
        if (exist != null) return

        NoInternetDialog.newInstance().show(supportFragmentManager, NoInternetDialog.TAG)

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
