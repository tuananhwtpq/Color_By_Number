package com.example.baseproject.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment

fun View.gone() {
    visibility = View.GONE
}

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun View.setOnUnDoubleClick(interval: Long = 500L, onViewClick: (View?) -> Unit) {
    setOnClickListener(UnDoubleClick(defaultInterval = interval, onViewClick = onViewClick))
}

fun Context.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

fun Fragment.showToast(message: String) = requireContext().showToast(message)

fun Context.checkPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.isNetworkAvailable(): Boolean {

    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false

    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

    return when {
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        else -> false
    }
}

fun Context.navToAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.fromParts("package", packageName, null)
    startActivity(intent)
}

fun TextView.runText() {
    apply {
        isDuplicateParentStateEnabled = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        setHorizontallyScrolling(true)
        isSelected = true
    }
}

fun TextView.enableMarquee() {
    apply {
        isDuplicateParentStateEnabled = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        setHorizontallyScrolling(true)
        isSelected = true
    }
}

//Set text gradient
fun TextView.setGradientText() {
    //Two color
    val colorStart = "#3780FE".toColorInt()
    val colorEnd = "#FFFFFF".toColorInt()

    val textWidth = this.paint.measureText(this.text.toString())

    val shader = LinearGradient(
        0f, 0f, textWidth, 0f,
        intArrayOf(colorStart, colorEnd),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP
    )


    this.paint.shader = shader
    this.invalidate()
}

fun Activity.hideSoftKeyBoard() {
    val inputMethod = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethod.hideSoftInputFromWindow(this.window.decorView.rootView.windowToken, 0)
    currentFocus?.clearFocus()
}

fun getIsFirstTime(): Boolean {
    return SharedPrefManager.getBoolean("is_first_time_open_app", true)
}

fun setIsFirstTime(b: Boolean) {
    SharedPrefManager.putBoolean("is_first_time_open_app", b)
}

fun setRequireShowRate(b: Boolean) {
    SharedPrefManager.putBoolean("is_require_show_rate", b)
}

fun getRequireShowRate(): Boolean {
    return SharedPrefManager.getBoolean("is_require_show_rate", false)
}

fun View.pulse() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

fun View.applyRippleEffect(borderless: Boolean = false) {
    val typedValue = TypedValue()
    val attr = if (borderless) {
        android.R.attr.selectableItemBackgroundBorderless
    } else {
        android.R.attr.selectableItemBackground
    }
    context.theme.resolveAttribute(attr, typedValue, true)
    foreground = AppCompatResources.getDrawable(context, typedValue.resourceId)
}
