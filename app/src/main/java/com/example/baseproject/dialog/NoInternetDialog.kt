package com.example.baseproject.dialog

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentNoInternetDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

class NoInternetDialog : BaseDialog<FragmentNoInternetDialogBinding>(FragmentNoInternetDialogBinding::inflate) {

    companion object{
        const val TAG = "NoInternetDialog"

        fun newInstance(): NoInternetDialog {
            val args = Bundle()
            val fragment = NoInternetDialog()
            fragment.arguments = args
            return fragment
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet) {
                dismissAllowingStateLoss()
            }
        }
    }

    override fun initView() {
        isCancelable = false
        binding.btnGoToSetting.isSelected = true

    }

    override fun initActionView() {
        binding.btnGoToSetting.setOnUnDoubleClick {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (e: java.lang.Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectivityManager = requireContext().getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback)
    }

    override fun onStop() {
        super.onStop()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
        }
    }

}