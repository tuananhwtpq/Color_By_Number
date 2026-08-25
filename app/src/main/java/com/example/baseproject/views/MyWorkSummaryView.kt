package com.example.baseproject.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.example.baseproject.R

class MyWorkSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val DEFAULT_WIDTH_DP = 116f
        const val DEFAULT_HEIGHT_DP = 36f
        const val ICON_SIZE_DP = 20f
    }

    private val bodyBounds = RectF()
    private val iconBounds = RectF()
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.white)
        alpha = 44
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.white)
        alpha = 36
    }
    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f.dp()
        color = ContextCompat.getColor(context, R.color.white)
        alpha = 145
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.white)
        textAlign = Paint.Align.CENTER
        textSize = 28f.sp()
    }

    private val iconBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_empty)
    private var countText = "0"

    fun setCount(count: Int) {
        val newText = count.toString()
        if (countText == newText) return
        countText = newText
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = DEFAULT_WIDTH_DP.dp().toInt()
        val desiredHeight = DEFAULT_HEIGHT_DP.dp().toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewHeight = height.toFloat()
        val radius = viewHeight / 2f
        val circleCenterX = radius
        val circleCenterY = radius

        bodyBounds.set(circleCenterX, 0f, width.toFloat(), viewHeight)
        canvas.drawRoundRect(bodyBounds, radius, radius, bodyPaint)
        canvas.drawCircle(circleCenterX, circleCenterY, radius, circlePaint)
        canvas.drawCircle(circleCenterX, circleCenterY, radius - circleStrokePaint.strokeWidth / 2f, circleStrokePaint)

        drawIcon(canvas, circleCenterX, circleCenterY)

        val countX = circleCenterX + (width - circleCenterX) * 0.55f
        val countY = viewHeight / 2f - (countPaint.descent() + countPaint.ascent()) / 2f
        canvas.drawText(countText, countX, countY, countPaint)
    }

    private fun drawIcon(canvas: Canvas, centerX: Float, centerY: Float) {
        val iconSize = ICON_SIZE_DP.dp()
        iconBounds.set(
            centerX - iconSize / 2f,
            centerY - iconSize / 2f,
            centerX + iconSize / 2f,
            centerY + iconSize / 2f
        )
        canvas.drawBitmap(iconBitmap, null, iconBounds, iconPaint)
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private fun Float.sp(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this,
        resources.displayMetrics
    )
}
