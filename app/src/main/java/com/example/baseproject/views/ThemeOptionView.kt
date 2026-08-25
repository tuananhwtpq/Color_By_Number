package com.example.baseproject.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.use
import androidx.core.graphics.toColorInt
import com.example.baseproject.R

class ThemeOptionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val TYPE_MIDNIGHT = 0
        const val TYPE_SUNSET = 1
        const val TYPE_SUNRISE = 2
    }

    private val cardBounds = RectF()
    private val previewBounds = RectF()
    private val footerBounds = RectF()
    private val previewClipPath = Path()
    private val cardClipPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f.dp()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ResourcesCompat.getColor(resources, R.color.grey_50, context.theme)
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.nunito_sans_bold)
        textSize = 20f.sp()
    }

    private var optionTitle = ""
    private var optionType = TYPE_MIDNIGHT
    private var thumbnailDrawable: Drawable? = null

    init {
        isClickable = true
        isFocusable = true

        context.obtainStyledAttributes(attrs, R.styleable.ThemeOptionView).use { typedArray ->
            optionTitle = typedArray.getString(R.styleable.ThemeOptionView_themeOptionTitle).orEmpty()
            optionType = typedArray.getInt(R.styleable.ThemeOptionView_themeOptionType, TYPE_MIDNIGHT)
            val thumbnailRes = typedArray.getResourceId(
                R.styleable.ThemeOptionView_themeOptionThumbnail,
                0
            )
            thumbnailDrawable = if (thumbnailRes != 0) {
                ResourcesCompat.getDrawable(resources, thumbnailRes, context.theme)
            } else {
                null
            }
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokeInset = strokePaint.strokeWidth / 2f
        val cornerRadius = 22f.dp()
        val footerHeight = 38f.dp()

        cardBounds.set(strokeInset, strokeInset, width - strokeInset, height - strokeInset)
        previewBounds.set(cardBounds.left, cardBounds.top, cardBounds.right, cardBounds.bottom - footerHeight)
        footerBounds.set(cardBounds.left, previewBounds.bottom, cardBounds.right, cardBounds.bottom)

        cardClipPath.reset()
        cardClipPath.addRoundRect(cardBounds, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(cardClipPath)
        drawCardBase(canvas, cornerRadius)
        drawPreview(canvas)
        drawFooter(canvas)
        drawTitle(canvas)
        canvas.restore()

        drawBorder(canvas)
    }

    private fun drawCardBase(canvas: Canvas, cornerRadius: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = if (isSelected) {
            LinearGradient(
                cardBounds.left,
                cardBounds.top,
                cardBounds.right,
                cardBounds.bottom,
                "#F47A61".toColorInt(),
                "#FFD43D".toColorInt(),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f,
                cardBounds.top,
                0f,
                cardBounds.bottom,
                "#80FFFFFF".toColorInt(),
                "#33FFFFFF".toColorInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(cardBounds, cornerRadius, cornerRadius, paint)
        paint.shader = null
    }

    private fun drawPreview(canvas: Canvas) {
        previewClipPath.reset()
        previewClipPath.addRoundRect(
            previewBounds,
            floatArrayOf(
                18f.dp(), 18f.dp(),
                18f.dp(), 18f.dp(),
                0f, 0f,
                0f, 0f
            ),
            Path.Direction.CW
        )

        canvas.save()
        canvas.clipPath(previewClipPath)

        val thumbnail = thumbnailDrawable
        if (thumbnail != null) {
            drawThumbnail(canvas, thumbnail)
        } else {
            when (optionType) {
                TYPE_SUNSET -> drawSunsetPreview(canvas)
                TYPE_SUNRISE -> drawSunrisePreview(canvas)
                else -> drawMidnightPreview(canvas)
            }
        }

        canvas.restore()
    }

    private fun drawThumbnail(canvas: Canvas, drawable: Drawable) {
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            drawable.setBounds(
                previewBounds.left.toInt(),
                previewBounds.top.toInt(),
                previewBounds.right.toInt(),
                previewBounds.bottom.toInt()
            )
            drawable.draw(canvas)
            return
        }

        val scale = maxOf(
            previewBounds.width() / intrinsicWidth.toFloat(),
            previewBounds.height() / intrinsicHeight.toFloat()
        )
        val dx = previewBounds.left + (previewBounds.width() - intrinsicWidth * scale) / 2f
        val dy = previewBounds.top + (previewBounds.height() - intrinsicHeight * scale) / 2f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        drawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        drawable.draw(canvas)
        canvas.restore()
    }

    private fun drawMidnightPreview(canvas: Canvas) {
        drawSkyGradient(canvas, "#342EEA", "#7B27E5", "#B986E9")
        drawClouds(canvas, "#92FFFFFF".toColorInt(), previewBounds.left + 16f.dp(), previewBounds.top + 26f.dp())
        drawHorizonBands(canvas)
        drawCloudLine(canvas)
        drawSun(canvas, previewBounds.centerX() + 4f.dp(), previewBounds.bottom - 2f.dp(), 31f.dp())
    }

    private fun drawSunsetPreview(canvas: Canvas) {
        drawSkyGradient(canvas, "#8E4DEE", "#D066C7", "#FFB04A")
        drawSun(canvas, previewBounds.centerX(), previewBounds.bottom - 20f.dp(), 26f.dp())
        drawMountains(canvas)
        drawBirds(canvas)
        drawClouds(canvas, "#40FFFFFF".toColorInt(), previewBounds.left + 14f.dp(), previewBounds.top + 72f.dp())
    }

    private fun drawSunrisePreview(canvas: Canvas) {
        drawSkyGradient(canvas, "#3470F2", "#3AB8F2", "#8AD86A")
        paint.shader = RadialGradient(
            previewBounds.centerX() - 6f.dp(),
            previewBounds.bottom - 14f.dp(),
            previewBounds.width() * 0.55f,
            intArrayOf("#FFE353".toColorInt(), "#82D965".toColorInt(), "#003AD8F2".toColorInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(previewBounds, paint)
        paint.shader = null
        drawSun(canvas, previewBounds.centerX() - 6f.dp(), previewBounds.bottom - 14f.dp(), 34f.dp())
        drawCloudLine(canvas)
        drawShootingStar(canvas)
    }

    private fun drawSkyGradient(canvas: Canvas, top: String, middle: String, bottom: String) {
        paint.shader = LinearGradient(
            0f,
            previewBounds.top,
            0f,
            previewBounds.bottom,
            intArrayOf(top.toColorInt(), middle.toColorInt(), bottom.toColorInt()),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(previewBounds, paint)
        paint.shader = null
    }

    private fun drawClouds(canvas: Canvas, color: Int, startX: Float, startY: Float) {
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawCircle(startX, startY + 12f.dp(), 36f.dp(), paint)
        canvas.drawCircle(startX + 28f.dp(), startY + 3f.dp(), 30f.dp(), paint)
        canvas.drawCircle(startX + 58f.dp(), startY + 17f.dp(), 24f.dp(), paint)
    }

    private fun drawHorizonBands(canvas: Canvas) {
        paint.color = "#55FFFFFF".toColorInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f.dp()
        canvas.drawArc(previewBounds.left - 40f.dp(), previewBounds.top + 58f.dp(), previewBounds.right + 58f.dp(), previewBounds.bottom - 20f.dp(), 76f, 84f, false, paint)
        paint.strokeWidth = 2f.dp()
        canvas.drawArc(previewBounds.left - 50f.dp(), previewBounds.top + 36f.dp(), previewBounds.right + 38f.dp(), previewBounds.bottom - 50f.dp(), 74f, 86f, false, paint)
    }

    private fun drawCloudLine(canvas: Canvas) {
        paint.color = "#DDFFFFFF".toColorInt()
        paint.style = Paint.Style.FILL
        val baseY = previewBounds.bottom - 16f.dp()
        var x = previewBounds.left - 8f.dp()
        while (x < previewBounds.right + 18f.dp()) {
            canvas.drawCircle(x, baseY, 21f.dp(), paint)
            x += 28f.dp()
        }
        canvas.drawRect(previewBounds.left, baseY, previewBounds.right, previewBounds.bottom, paint)
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.shader = RadialGradient(
            cx,
            cy,
            radius * 1.5f,
            intArrayOf("#FFF7A8".toColorInt(), "#FFE35F".toColorInt(), "#00FFE35F".toColorInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.5f, paint)
        paint.shader = null
    }

    private fun drawMountains(canvas: Canvas) {
        paint.color = "#6F358C".toColorInt()
        paint.style = Paint.Style.FILL
        val mountainPath = Path().apply {
            moveTo(previewBounds.left, previewBounds.bottom)
            lineTo(previewBounds.left + 42f.dp(), previewBounds.bottom - 38f.dp())
            lineTo(previewBounds.left + 76f.dp(), previewBounds.bottom - 12f.dp())
            lineTo(previewBounds.left + 118f.dp(), previewBounds.bottom - 46f.dp())
            lineTo(previewBounds.right, previewBounds.bottom)
            close()
        }
        canvas.drawPath(mountainPath, paint)
        paint.color = "#4B2478".toColorInt()
        canvas.drawRect(previewBounds.left, previewBounds.bottom - 12f.dp(), previewBounds.right, previewBounds.bottom, paint)
    }

    private fun drawBirds(canvas: Canvas) {
        paint.color = "#4C2478".toColorInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f.dp()
        val y = previewBounds.top + 38f.dp()
        drawBird(canvas, previewBounds.centerX() - 18f.dp(), y, 8f.dp())
        drawBird(canvas, previewBounds.centerX() + 18f.dp(), y + 18f.dp(), 11f.dp())
        drawBird(canvas, previewBounds.right - 34f.dp(), y + 5f.dp(), 7f.dp())
    }

    private fun drawBird(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        canvas.drawLine(cx - size, cy, cx, cy + size / 2f, paint)
        canvas.drawLine(cx, cy + size / 2f, cx + size, cy, paint)
    }

    private fun drawShootingStar(canvas: Canvas) {
        paint.color = "#80FFFFFF".toColorInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f.dp()
        canvas.drawLine(
            previewBounds.left + 48f.dp(),
            previewBounds.top + 18f.dp(),
            previewBounds.left + 84f.dp(),
            previewBounds.top + 8f.dp(),
            paint
        )
    }

    private fun drawFooter(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = if (isSelected) {
            LinearGradient(
                footerBounds.left,
                footerBounds.top,
                footerBounds.right,
                footerBounds.bottom,
                "#F47A61".toColorInt(),
                "#FFD43D".toColorInt(),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f,
                footerBounds.top,
                0f,
                footerBounds.bottom,
                "#66FFFFFF".toColorInt(),
                "#22FFFFFF".toColorInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(footerBounds, paint)
        paint.shader = null
    }

    private fun drawBorder(canvas: Canvas) {
        strokePaint.shader = if (isSelected) {
            LinearGradient(
                cardBounds.left,
                cardBounds.top,
                cardBounds.right,
                cardBounds.bottom,
                "#F47A61".toColorInt(),
                "#FFD43D".toColorInt(),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
        strokePaint.color = if (isSelected) Color.WHITE else "#80FFFFFF".toColorInt()

        canvas.drawPath(cardClipPath, strokePaint)
        strokePaint.shader = null
    }

    private fun drawTitle(canvas: Canvas) {
        val y = footerBounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(optionTitle, footerBounds.centerX(), y, textPaint)
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private fun Float.sp(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)
}
