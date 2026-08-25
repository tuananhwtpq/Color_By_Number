package com.example.baseproject.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.baseproject.R

/**
 * Thanh tiến độ của một achievement: nền trắng 20%, viền 1dp gradient trắng (20% → 70% → 20%)
 * và phần đã đạt tô gradient vàng → cam → đỏ. Chữ trạng thái ("3/9") nằm giữa thanh.
 *
 * Phải tự vẽ chứ không dùng SeekBar/ProgressBar được: viền gradient không làm được bằng
 * shape drawable (thẻ <stroke> chỉ nhận màu đặc), còn mẹo "lồng hai view" thì hỏng vì nền
 * thanh trong suốt 20% sẽ để lộ lớp gradient nằm dưới.
 */
class AchievementProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private companion object {
        const val ANIM_DURATION_MS = 250L
        const val BORDER_WIDTH_DP = 1f
    }

    private val borderWidth = BORDER_WIDTH_DP * resources.displayMetrics.density

    private val trackBounds = RectF()
    private val fillBounds = RectF()
    private val borderBounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.white_20)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    private val fillGradientColors = intArrayOf(
        ContextCompat.getColor(context, R.color.yellow_400),
        ContextCompat.getColor(context, R.color.orange350),
        ContextCompat.getColor(context, R.color.red_350)
    )
    private val borderGradientColors = intArrayOf(
        ContextCompat.getColor(context, R.color.white_20),
        ContextCompat.getColor(context, R.color.white_70),
        ContextCompat.getColor(context, R.color.white_20)
    )
    private val gradientPositions = floatArrayOf(0f, 0.5f, 1f)

    private val tvStatus: TextView

    private var progressFraction: Float = 0f
    private var progressAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        LayoutInflater.from(context).inflate(R.layout.view_achievement_progress, this, true)
        tvStatus = findViewById(R.id.tvProgressStatus)
    }

    /**
     * Cập nhật cả thanh lẫn chữ từ một chỗ để hai thứ không bao giờ lệch nhau.
     * [total] <= 0 được coi như chưa có mục tiêu nên thanh để trống.
     */
    fun setProgress(current: Int, total: Int, animate: Boolean = true) {
        val safeTotal = total.coerceAtLeast(0)
        val safeCurrent = current.coerceIn(0, safeTotal)
        tvStatus.text = context.getString(R.string.achievement_progress_format, safeCurrent, safeTotal)
        setProgressFraction(
            if (safeTotal == 0) 0f else safeCurrent.toFloat() / safeTotal.toFloat(),
            animate
        )
    }

    fun setProgressFraction(fraction: Float, animate: Boolean = true) {
        val target = fraction.coerceIn(0f, 1f)
        progressAnimator?.cancel()

        if (!animate) {
            progressFraction = target
            invalidate()
            return
        }

        progressAnimator = ValueAnimator.ofFloat(progressFraction, target).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progressFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        borderBounds.set(
            borderWidth / 2f,
            borderWidth / 2f,
            w - borderWidth / 2f,
            h - borderWidth / 2f
        )
        if (w <= 0) return

        // Cả hai gradient đều trải theo chiều ngang của toàn bộ thanh: progress thấp thì chỉ
        // thấy phần vàng, càng đầy càng ngả sang đỏ.
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            fillGradientColors, gradientPositions, Shader.TileMode.CLAMP
        )
        borderPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            borderGradientColors, gradientPositions, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f

        canvas.drawRoundRect(trackBounds, radius, radius, trackPaint)

        if (progressFraction > 0f) {
            // Chặn dưới bằng chiều cao thanh để tiến độ rất nhỏ vẫn ra hình viên thuốc gọn
            // gàng thay vì một vệt dẹt méo mó.
            val fillWidth = (width * progressFraction).coerceAtLeast(height.toFloat())
            fillBounds.set(0f, 0f, fillWidth, height.toFloat())
            canvas.drawRoundRect(fillBounds, radius, radius, fillPaint)
        }

        // Viền vẽ sau cùng để nằm trên cả phần đã đạt, và lùi vào trong nửa nét cho khớp
        // "inner alignment" của design.
        canvas.drawRoundRect(
            borderBounds,
            radius - borderWidth / 2f,
            radius - borderWidth / 2f,
            borderPaint
        )
    }
}
