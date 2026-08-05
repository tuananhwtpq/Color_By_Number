package com.example.baseproject.activities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class SpotlightShape {
        RoundRect,
        Oval
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 0x36, 0x2F, 0x37)
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val spotlightRect = RectF()
    private var hasSpotlight = false
    private var spotlightShape = SpotlightShape.RoundRect
    private var cornerRadius = 0f

    init {
        setWillNotDraw(false)
    }

    fun setSpotlight(
        rect: RectF,
        shape: SpotlightShape,
        radius: Float = 0f
    ) {
        spotlightRect.set(rect)
        spotlightShape = shape
        cornerRadius = radius
        hasSpotlight = true
        invalidate()
    }

    fun clearSpotlight() {
        hasSpotlight = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

        if (hasSpotlight) {
            when (spotlightShape) {
                SpotlightShape.RoundRect -> canvas.drawRoundRect(
                    spotlightRect,
                    cornerRadius,
                    cornerRadius,
                    clearPaint
                )

                SpotlightShape.Oval -> canvas.drawOval(spotlightRect, clearPaint)
            }
        }

        canvas.restoreToCount(checkpoint)
    }
}
