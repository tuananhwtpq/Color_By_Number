package com.example.baseproject.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt

class HighlightOptionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewClipPath = Path()
    private val selectedStartColor = "#F07962".toColorInt()
    private val selectedEndColor = "#FFD43D".toColorInt()
    private val unselectedColor = "#D8D0DA".toColorInt()
    private val strokeWidthPx = 5f.dp()
    private val previewDiameterPx = 56f.dp()
    private val checkerCellPx = 12f.dp()
    private var previewPrimaryColor = "#E7E4E7".toColorInt()
    private var previewSecondaryColor = "#9E92A0".toColorInt()
    private var previewStyle = PreviewStyle.CHECKER
    var useImagePreview: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
        clipToPadding = false
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (shouldDrawFallbackPreview()) {
            drawPreview(canvas)
        }
        drawRing(canvas)
        super.dispatchDraw(canvas)
    }

    fun setCheckerPreview(primaryColor: Int, secondaryColor: Int) {
        previewStyle = PreviewStyle.CHECKER
        previewPrimaryColor = primaryColor
        previewSecondaryColor = secondaryColor
        invalidate()
    }

    fun setSolidPreview(color: Int) {
        previewStyle = PreviewStyle.SOLID
        previewPrimaryColor = color
        previewSecondaryColor = color
        invalidate()
    }

    private fun shouldDrawFallbackPreview(): Boolean {
        if (!useImagePreview) return true
        for (index in 0 until childCount) {
            val child = getChildAt(index) as? android.widget.ImageView ?: continue
            if (child.drawable != null) return false
        }
        return true
    }

    private fun drawPreview(canvas: Canvas) {
        val radius = previewDiameterPx / 2f
        val left = width / 2f - radius
        val top = height / 2f - radius
        val right = width / 2f + radius
        val bottom = height / 2f + radius

        previewClipPath.reset()
        previewClipPath.addCircle(width / 2f, height / 2f, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(previewClipPath)
        when (previewStyle) {
            PreviewStyle.CHECKER -> drawCheckerPreview(canvas, left, top, right, bottom)
            PreviewStyle.SOLID -> {
                previewPaint.color = previewPrimaryColor
                canvas.drawRect(left, top, right, bottom, previewPaint)
            }
        }
        canvas.restore()
    }

    private fun drawCheckerPreview(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        var y = top
        var row = 0
        while (y < bottom) {
            var x = left
            var column = 0
            while (x < right) {
                previewPaint.color = if ((row + column) % 2 == 0) {
                    previewPrimaryColor
                } else {
                    previewSecondaryColor
                }
                canvas.drawRect(
                    x,
                    y,
                    minOf(x + checkerCellPx, right),
                    minOf(y + checkerCellPx, bottom),
                    previewPaint
                )
                x += checkerCellPx
                column++
            }
            y += checkerCellPx
            row++
        }
    }

    private fun drawRing(canvas: Canvas) {
        val diameter = minOf(width, height).toFloat()
        if (diameter <= 0f) return

        ringPaint.strokeWidth = strokeWidthPx
        ringPaint.color = unselectedColor
        ringPaint.alpha = if (isSelected) 255 else 180
        ringPaint.shader = if (isSelected) {
            LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                selectedStartColor,
                selectedEndColor,
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }

        val radius = diameter / 2f - strokeWidthPx / 2f
        canvas.drawCircle(width / 2f, height / 2f, radius, ringPaint)
        ringPaint.shader = null
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private enum class PreviewStyle {
        CHECKER,
        SOLID
    }
}
