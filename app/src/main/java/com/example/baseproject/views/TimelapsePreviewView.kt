package com.example.baseproject.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TimelapsePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var frameBitmap: Bitmap? = null

    fun setFrameBitmap(bitmap: Bitmap?) {
        frameBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frameBitmap ?: return
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        destination.set(left, top, left + side, top + side)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
    }
}
