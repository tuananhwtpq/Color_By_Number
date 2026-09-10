package com.example.baseproject.drawables

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.example.baseproject.R
import org.xmlpull.v1.XmlPullParser

/**
 * Scalable unselected-tab background matching the design's translucent white fill and inset
 * white gradient border. It has no intrinsic size, so the view that owns it controls its bounds.
 */
class UnselectedTabBackgroundDrawable : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillBounds = RectF()
    private val borderBounds = RectF()

    private var cornerRadius = 0f
    private var borderWidth = 0f
    private var fillColor = 0
    private var borderColors = intArrayOf()
    private var drawableAlpha = 255

    override fun inflate(
        resources: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        super.inflate(resources, parser, attrs, theme)
        cornerRadius = 16f * resources.displayMetrics.density
        borderWidth = resources.displayMetrics.density
        fillColor = resources.getColor(R.color.white_20, theme)
        borderColors = intArrayOf(
            resources.getColor(R.color.white_20, theme),
            resources.getColor(R.color.white_70, theme),
            resources.getColor(R.color.white_20, theme)
        )
        borderPaint.strokeWidth = borderWidth
        applyAlpha()
        updateGeometry(bounds)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateGeometry(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (fillBounds.isEmpty) return

        canvas.drawRoundRect(fillBounds, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(
            borderBounds,
            (cornerRadius - borderWidth / 2f).coerceAtLeast(0f),
            (cornerRadius - borderWidth / 2f).coerceAtLeast(0f),
            borderPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        if (drawableAlpha == alpha) return
        drawableAlpha = alpha
        applyAlpha()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (fillPaint.colorFilter == colorFilter && borderPaint.colorFilter == colorFilter) return
        fillPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1

    override fun getIntrinsicHeight(): Int = -1

    override fun getOutline(outline: Outline) {
        if (fillBounds.isEmpty) return
        outline.setRoundRect(bounds, cornerRadius)
        outline.alpha = Color.alpha(fillColor) / 255f * (drawableAlpha / 255f)
    }

    private fun updateGeometry(bounds: Rect) {
        fillBounds.set(bounds)
        val halfBorder = borderWidth / 2f
        borderBounds.set(
            bounds.left + halfBorder,
            bounds.top + halfBorder,
            bounds.right - halfBorder,
            bounds.bottom - halfBorder
        )

        borderPaint.shader = if (bounds.width() > 0 && borderColors.isNotEmpty()) {
            LinearGradient(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.top.toFloat(),
                borderColors,
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }

    private fun applyAlpha() {
        val fillAlpha = Color.alpha(fillColor) * drawableAlpha / 255
        fillPaint.color = Color.argb(
            fillAlpha,
            Color.red(fillColor),
            Color.green(fillColor),
            Color.blue(fillColor)
        )
        borderPaint.alpha = drawableAlpha
    }
}
