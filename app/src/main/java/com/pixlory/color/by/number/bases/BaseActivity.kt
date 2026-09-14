package com.pixlory.color.by.number.bases

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.pixlory.color.by.number.utils.ads.AdsManager
import com.pixlory.color.by.number.utils.ads.RemoteConfig
import com.pixlory.color.by.number.utils.gone
import com.pixlory.color.by.number.utils.visible
import com.snake.squad.adslib.AdmobLib
import com.snake.squad.adslib.utils.GoogleENative
import com.pixlory.color.by.number.R
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

    data class CollapsibleNativeHost(
        val collapsed: ViewGroup,
        val expanded: ViewGroup,
        val divider: View
    )

    private fun showInter(
        shouldShow: Boolean,
        model: com.snake.squad.adslib.models.AdmobInterModel,
        overlay: View?,
        onContinue: () -> Unit
    ) {
        if (!shouldShow) {
            onContinue()
            return
        }
        AdmobLib.showInterWithNativeAfter(
            mActivity = this,
            interModel = model,
            nativeModel = AdsManager.NATIVE_FULL_SCREEN_AFTER_INTER,
            vShowInterAds = overlay,
            isShowNativeAfter = AdsManager.isShowNativeFullScreen(),
            nativeLayout = R.layout.native_ads_full_screen,
            onInterCloseOrFailed = { shown -> if (shown) AdsManager.updateTime() },
            navAction = {
                overlay?.gone()
                onContinue()
            }
        )
    }

    fun showInterHome(overlay: View?, onContinue: () -> Unit) =
        showInter(AdsManager.isShowInterHome(), AdsManager.INTER_HOME, overlay, onContinue)

    fun showInterBackToHome(overlay: View?, onContinue: () -> Unit) =
        showInter(AdsManager.isShowInterBackHome(), AdsManager.INTER_BACK_TO_HOME, overlay, onContinue)

    fun showInterDone(overlay: View?, onContinue: () -> Unit) =
        showInter(AdsManager.isShowInterDone(), AdsManager.INTER_DONE, overlay, onContinue)

    fun renderCollapsibleNative(
        enabled: Boolean,
        model: com.snake.squad.adslib.models.AdmobNativeModel,
        host: CollapsibleNativeHost,
        onFinished: () -> Unit = {}
    ) {
        if (!enabled) {
            host.collapsed.gone(); host.expanded.gone(); host.divider.gone(); onFinished(); return
        }
        host.collapsed.visible()
        host.expanded.visible()
        AdmobLib.loadAndShowNativeCollapsibleSingle(
            activity = this,
            admobNativeModel = model,
            viewGroupExpanded = host.expanded,
            viewGroupCollapsed = host.collapsed,
            layoutExpanded = R.layout.native_ads_custom_medium_bottom,
            layoutCollapsed = R.layout.native_ads_custom_small_like_banner,
            onAdsLoaded = { host.divider.visible(); onFinished() },
            onAdsLoadFail = { host.divider.gone(); onFinished() }
        )
    }

    fun renderConfiguredCollapsibleNative(
        mode: Long,
        model: com.snake.squad.adslib.models.AdmobNativeModel,
        host: CollapsibleNativeHost,
        onFinished: () -> Unit = {}
    ) {
        when (mode) {
            1L -> {
                host.expanded.gone()
                host.collapsed.visible()
                AdmobLib.loadAndShowNative(
                    activity = this,
                    admobNativeModel = model,
                    viewGroup = host.collapsed,
                    size = GoogleENative.UNIFIED_SMALL_LIKE_BANNER,
                    layout = R.layout.native_ads_custom_small_like_banner,
                    onAdsLoaded = { host.divider.visible(); onFinished() },
                    onAdsLoadFail = { host.divider.gone(); onFinished() }
                )
            }
            2L -> renderCollapsibleNative(true, model, host, onFinished)
            else -> renderCollapsibleNative(false, model, host, onFinished)
        }
    }

    fun renderSettingNative(host: ViewGroup) {
        if (RemoteConfig.remoteNativeSetting != 1L) { host.gone(); return }
        host.visible()
        AdmobLib.loadAndShowNative(
            activity = this,
            admobNativeModel = AdsManager.NATIVE_SETTING,
            viewGroup = host,
            size = GoogleENative.UNIFIED_SMALL_LIKE_BANNER,
            layout = R.layout.native_ads_custom_small_like_banner,
            onAdsLoadFail = { host.gone() }
        )
    }

    fun showRewardUnlock(onEarned: () -> Unit) {
        if (RemoteConfig.remoteRewardUnlock != 1L) { onEarned(); return }
        AdmobLib.loadAndShowRewarded(
            activity = this,
            admobRewardedModel = AdsManager.REWARD_UNLOCK,
            isShowOnTestDevice = true,
            onAdsCloseOrFailed = { earned -> if (earned) onEarned() }
        )
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
