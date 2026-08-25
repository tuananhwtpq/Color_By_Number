package com.example.baseproject.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.baseproject.R

class LevelProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val ANIM_DURATION_MS = 250L
    }

    private val trackBounds = RectF()
    private val fillBounds = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.grey_400)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillGradientColors = intArrayOf(
        ContextCompat.getColor(context, R.color.yellow_400),
        ContextCompat.getColor(context, R.color.orange350),
        ContextCompat.getColor(context, R.color.red_350)
    )
    private val fillGradientPositions = floatArrayOf(0f, 0.63f, 1f)

    private var progressFraction: Float = 0f
    private var progressAnimator: ValueAnimator? = null

    fun setProgress(fraction: Float, animate: Boolean = true) {
        val sanitizedFraction = fraction.coerceIn(0f, 1f)
        progressAnimator?.cancel()

        if (!animate) {
            progressFraction = sanitizedFraction
            invalidate()
            return
        }

        progressAnimator = ValueAnimator.ofFloat(progressFraction, sanitizedFraction).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progressFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        if (w > 0) {
            fillPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                fillGradientColors,
                fillGradientPositions,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(trackBounds, trackPaint)

        if (progressFraction <= 0f) return
        fillBounds.set(0f, 0f, width * progressFraction, height.toFloat())
        canvas.drawRect(fillBounds, fillPaint)
    }
}
