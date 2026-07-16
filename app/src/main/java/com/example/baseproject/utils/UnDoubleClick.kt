package com.example.baseproject.utils

import android.view.View

class UnDoubleClick(
    private val defaultInterval: Long = 500,
    private val onViewClick: (View?) -> Unit
) : View.OnClickListener {
    companion object {
        private var lastClick = 0L
    }

    override fun onClick(view: View?) {
        if (System.currentTimeMillis() - lastClick < defaultInterval) return

        lastClick = System.currentTimeMillis()
        onViewClick(view)
    }
}
