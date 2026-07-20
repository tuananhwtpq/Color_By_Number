package com.example.baseproject.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.baseproject.R

class PaletteRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MODE_NONE = 0
        const val MODE_SELECTED = 1
        const val MODE_PROGRESS = 2
    }

    private val ringBounds = RectF()
    private val strokeWidth = 4f.dp()
    private val backgroundRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@PaletteRingView.strokeWidth
        color = ContextCompat.getColor(context, R.color.grey_400)
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@PaletteRingView.strokeWidth
        color = ContextCompat.getColor(context, R.color.grey_400)
    }
    private val progressRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@PaletteRingView.strokeWidth
    }

    private var ringMode: Int = MODE_NONE
    private var progressFraction: Float = 0f

    fun setRingState(mode: Int, progress: Float = 0f) {
        val sanitizedProgress = progress.coerceIn(0f, 1f)
        if (ringMode == mode && progressFraction == sanitizedProgress) return
        ringMode = mode
        progressFraction = sanitizedProgress
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfStroke = strokeWidth / 2f
        ringBounds.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        updateGradient()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (ringMode) {
            MODE_SELECTED -> {
                canvas.drawOval(ringBounds, selectedRingPaint)
            }

            MODE_PROGRESS -> {
                canvas.drawOval(ringBounds, backgroundRingPaint)
                canvas.save()
                canvas.rotate(-90f, width / 2f, height / 2f)
                canvas.drawArc(ringBounds, 0f, progressFraction * 360f, false, progressRingPaint)
                canvas.restore()
            }
        }
    }

    private fun updateGradient() {
        val gradient = SweepGradient(
            width / 2f,
            height / 2f,
            intArrayOf(
                0xFFFFD633.toInt(),
                0xFFEE935D.toInt(),
                0xFFE27D69.toInt()
            ),
            floatArrayOf(0f, 0.63f, 1f)
        )
        progressRingPaint.shader = gradient
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density
}
