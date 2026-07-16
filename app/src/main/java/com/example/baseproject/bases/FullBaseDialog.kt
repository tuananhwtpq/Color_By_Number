package com.example.baseproject.bases


import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.viewbinding.ViewBinding

abstract class FullBaseDialog<B : ViewBinding>(
    val bindingFactory: (LayoutInflater) -> B,
    context: Context,
    private val cancelable: Boolean = false
) : Dialog(context) {
    protected val binding: B by lazy { bindingFactory(layoutInflater) }
    private val backCallback = OnBackInvokedCallback {
        handleBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setCancelable(cancelable)
        setCanceledOnTouchOutside(false)
        setupBackHandling()
        applyImmersiveMode()
        initView()
        initData()
        initActionView()
        showSmooth()
        layoutContainer.setOnClickListener {
            if (cancelable) dismiss()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback
            )
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
        super.onStop()
    }

    private fun handleBackPressed() {
        if (cancelable) dismiss()
    }

    private fun setupBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handleBackPressed()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun applyImmersiveMode() {
        val dialogWindow = window ?: return
        WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
        WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    protected fun showSmooth() {
        layoutContainer.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    protected abstract fun initView()
    protected abstract fun initData()
    protected abstract fun initActionView()
    protected abstract val layoutContainer: View
}
