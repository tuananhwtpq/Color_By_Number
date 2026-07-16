package com.example.baseproject.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtils {

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun checkAllPermissionGranted(permission: ArrayList<String>, context: Context): Boolean {
        for (item in permission) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    item
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

     fun hasAllPermission(context: Context, permission: ArrayList<String>): Boolean {
        return checkAllPermissionGranted(permission, context)
    }
}