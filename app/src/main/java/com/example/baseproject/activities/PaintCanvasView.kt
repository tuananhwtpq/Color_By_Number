package com.example.baseproject.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.baseproject.data.AnimatedFiller
import com.example.baseproject.data.RegionData

class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lineBitmap: Bitmap? = null
    private var maskWidth: Int = 0
    private var maskHeight: Int = 0
    private var coloredBitmap: Bitmap? = null
    private var highlightBitmap: Bitmap? = null

    // Coroutine Scope cho PaintCanvasView
    private val scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    // Arrays for fast processing
    private var maskPixelsArray: IntArray? = null
    private var linePixelsArray: IntArray? = null
    private var coloredPixelsArray: IntArray? = null
    private var hlPixelsArray: IntArray? = null

    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val normalPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }

    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkerLight = Color.parseColor("#F5F2F8")
    private val checkerDark = Color.parseColor("#CFC7D8")
    private val checkerCellSizePx = 18

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    var onRegionFilledListener: ((maskColor: Int) -> Unit)? = null

    private var regions: List<RegionData> = emptyList()
    private var completedMaskColors: Set<Int> = emptySet()

    private var currentValidMaskColors: Map<Int, Int> = emptyMap()

    data class TapEffect(val x: Float, val y: Float, val color: Int) {
        var progress: Float = 0f
    }

    private val activeEffects = mutableListOf<TapEffect>()
    private val activeFillers = mutableListOf<AnimatedFiller>()

    init {
        setupGestureDetectors()
    }

    private fun setupGestureDetectors() {
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val newScale =
                        Math.max(0.1f, Math.min(scaleFactor * detector.scaleFactor, 20.0f))
                    val scaleRatio = newScale / scaleFactor
                    translateX = detector.focusX - (detector.focusX - translateX) * scaleRatio
                    translateY = detector.focusY - (detector.focusY - translateY) * scaleRatio
                    scaleFactor = newScale
                    updateMatrix()
                    return true
                }
            })

        gestureDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    translateX -= distanceX
                    translateY -= distanceY
                    updateMatrix()
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    handleTap(e.x, e.y)
                    return true
                }
            })
    }

    suspend fun setBitmapsSuspend(line: Bitmap, mask: Bitmap, regionsData: List<RegionData>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val w = line.width
            val h = line.height

            val coloredBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val highlightBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            val maskPx = IntArray(w * h)
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)

            val blurredLine = fastBlur(line)
            val lp = IntArray(w * h)
            blurredLine.getPixels(lp, 0, w, 0, 0, w, h)

            // Giải phóng maskBitmap để tiết kiệm 4.6MB RAM
            mask.recycle()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                lineBitmap = blurredLine
                maskWidth = w
                maskHeight = h
                regions = regionsData

                coloredBitmap = coloredBmp
                highlightBitmap = highlightBmp

                maskPixelsArray = maskPx
                coloredPixelsArray = IntArray(w * h)
                linePixelsArray = lp
                hlPixelsArray = IntArray(w * h)

                scaleFactor = 1.0f
                translateX = 0f
                translateY = 0f

                val viewWidth = width.toFloat()
                val viewHeight = height.toFloat()
                if (viewWidth > 0 && viewHeight > 0) {
                    val scaleX = viewWidth / w
                    val scaleY = viewHeight / h
                    scaleFactor = Math.min(scaleX, scaleY)
                    translateX = (viewWidth - w * scaleFactor) / 2f
                    translateY = (viewHeight - h * scaleFactor) / 2f
                    updateMatrix()
                }
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val lw = maskWidth
        val lh = maskHeight
        if (lw > 0 && lh > 0 && w > 0 && h > 0 && scaleFactor == 1.0f) {
            val scaleX = w.toFloat() / lw
            val scaleY = h.toFloat() / lh
            scaleFactor = Math.min(scaleX, scaleY)
            translateX = (w.toFloat() - lw * scaleFactor) / 2f
            translateY = (h.toFloat() - lh * scaleFactor) / 2f
            updateMatrix()
        }
    }

    suspend fun restoreProgressSuspend(completedMap: Map<Int, Int>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (completedMap.isEmpty()) return@withContext

            val maskPx = maskPixelsArray ?: return@withContext
            val linePx = linePixelsArray ?: return@withContext
            val colPx = coloredPixelsArray ?: return@withContext
            val w = maskWidth
            val h = maskHeight

            // Tối ưu hóa cực đại: Linear Probing Hash Map thuần mảng nguyên thủy (O(1) lookup)
            val capacity = 4096 // Đủ lớn và là lũy thừa của 2
            val mask = capacity - 1
            val keys = IntArray(capacity)
            val vals = IntArray(capacity)
            for ((k, v) in completedMap) {
                var idx = k.hashCode() and mask
                while (keys[idx] != 0 && keys[idx] != k) {
                    idx = (idx + 1) and mask
                }
                keys[idx] = k
                vals[idx] = v
            }

            // Quét đổ màu trực tiếp với 1.1 triệu pixel
            for (i in maskPx.indices) {
                val maskC = maskPx[i]

                // Tìm trong HashMap
                var targetC = 0
                if (maskC != 0) {
                    var idxM = maskC.hashCode() and mask
                    while (true) {
                        val k = keys[idxM]
                        if (k == maskC) {
                            targetC = vals[idxM]; break
                        }
                        if (k == 0) {
                            break
                        }
                        idxM = (idxM + 1) and mask
                    }
                }

                if (targetC != 0) {
                    colPx[i] = targetC
                } else if (maskC != -1) { // maskC != White
                    // Color Bleeding cho những pixel viền đen
                    val lineC = linePx[i]
                    if (lineC != 0) {
                        val r = (lineC shr 16) and 0xFF
                        val g = (lineC shr 8) and 0xFF
                        val b = lineC and 0xFF
                        if ((r + g + b) / 3 < 240) {
                            val x = i % w
                            val y = i / w
                            var bleedColor = 0

                            // Hàm helper để check bleed
                            fun checkBleed(nIdx: Int): Int {
                                val nMaskC = maskPx[nIdx]
                                if (nMaskC == 0) return 0
                                var idxM = nMaskC.hashCode() and mask
                                while (true) {
                                    val k = keys[idxM]
                                    if (k == nMaskC) return vals[idxM]
                                    if (k == 0) return 0
                                    idxM = (idxM + 1) and mask
                                }
                            }

                            if (x > 0) bleedColor = checkBleed(i - 1)
                            if (bleedColor == 0 && x < w - 1) bleedColor = checkBleed(i + 1)
                            if (bleedColor == 0 && y > 0) bleedColor = checkBleed(i - w)
                            if (bleedColor == 0 && y < h - 1) bleedColor = checkBleed(i + w)

                            if (bleedColor != 0) {
                                colPx[i] = bleedColor
                            }
                        }
                    }
                }
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                coloredBitmap?.setPixels(colPx, 0, w, 0, 0, w, h)
                invalidate()
                // ĐÃ XÓA vòng lặp onRegionFilledListener để tránh gọi 500 lần trên UI thread
            }
        }

    fun resetProgress() {
        completedMaskColors = emptySet()
        activeFillers.clear()
        val colArr = coloredPixelsArray ?: return
        for (i in colArr.indices) {
            colArr[i] = 0 // Transparent
        }
        val colBmp = coloredBitmap ?: return
        colBmp.setPixels(colArr, 0, colBmp.width, 0, 0, colBmp.width, colBmp.height)
        invalidate()
    }

    private fun fastBlur(bitmap: Bitmap, radius: Int = 1): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        var pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        for (pass in 0 until 2) {
            val out = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0;
                    var count = 0
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx in 0 until w) {
                            sum += (pix[y * w + nx] and 0xFF); count++
                        }
                    }
                    val c = sum / count
                    out[y * w + x] = Color.argb(0xFF, c, c, c)
                }
            }
            val out2 = IntArray(w * h)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var sum = 0;
                    var count = 0
                    for (dy in -radius..radius) {
                        val ny = y + dy
                        if (ny in 0 until h) {
                            sum += (out[ny * w + x] and 0xFF); count++
                        }
                    }
                    val c = sum / count
                    out2[y * w + x] = Color.argb(0xFF, c, c, c)
                }
            }
            pix = out2
        }
        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return outBitmap
    }

    fun setActiveColors(maskToTargetColors: Map<Int, Int>) {
        currentValidMaskColors = maskToTargetColors
    }

    fun setCompletedRegions(completed: Set<Int>) {
        this.completedMaskColors = completed
        invalidate()
    }

    /**
     * Tạo một ảnh thu nhỏ (Thumbnail) thể hiện tiến trình tô màu hiện tại.
     * Ảnh sẽ được scale xuống thumbSize để tiết kiệm dung lượng.
     */
    fun generateThumbnail(thumbSize: Int): Bitmap? {
        val colored = coloredBitmap ?: return null
        val line = lineBitmap ?: return null
        val w = maskWidth
        val h = maskHeight
        if (w == 0 || h == 0) return null

        try {
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(colored, 0f, 0f, null)
            canvas.drawBitmap(line, 0f, 0f, multiplyPaint)

            val scaled = Bitmap.createScaledBitmap(result, thumbSize, thumbSize, true)
            if (scaled != result) {
                result.recycle()
            }
            return scaled
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun highlightNumber(targetMaskColors: List<Int>) {
        val hl = highlightBitmap ?: return
        val width = maskWidth;
        val height = maskHeight
        if (width == 0 || height == 0) return
        val maskPixels = maskPixelsArray ?: return
        val hlPixels = hlPixelsArray ?: return

        // Tính toán đồng bộ (Synchronous) siêu tốc trên Main Thread (~2-4ms)
        // Loại bỏ hoàn toàn Coroutines để ngăn chặn lỗi nhấp nháy (Blinking) và độ trễ (Delay) do Race Condition
        java.util.Arrays.fill(hlPixels, 0) // Clear array siêu tốc

        // Bỏ qua các mảng màu đã hoàn thành hoặc đang được animation
        val animatingColors = activeFillers.map { it.maskColor }.toSet()
        val activeTargets = targetMaskColors.filter {
            !completedMaskColors.contains(it) && !animatingColors.contains(it)
        }.toIntArray()

        if (activeTargets.isNotEmpty()) {
            activeTargets.sort()
            val len = width * height

            // Duyệt 1.1 triệu pixel với Binary Search nguyên thủy (Zero Object Allocation)
            for (i in 0 until len) {
                val c = maskPixels[i]
                if (c != 0 && java.util.Arrays.binarySearch(activeTargets, c) >= 0) {
                    val x = i % width
                    val y = i / width
                    val checkerX = x / checkerCellSizePx
                    val checkerY = y / checkerCellSizePx
                    hlPixels[i] = if ((checkerX + checkerY) % 2 == 0) {
                        checkerLight
                    } else {
                        checkerDark
                    }
                }
            }
        }

        hl.setPixels(hlPixels, 0, width, 0, 0, width, height)
        invalidate()
    }

    private fun updateMatrix() {
        val line = lineBitmap ?: return
        val viewWidth = width.toFloat();
        val viewHeight = height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        val scaledWidth = line.width * scaleFactor
        val scaledHeight = line.height * scaleFactor

        if (scaledWidth < viewWidth) {
            translateX = (viewWidth - scaledWidth) / 2f
        } else {
            translateX = Math.max(viewWidth - scaledWidth, Math.min(0f, translateX))
        }

        if (scaledHeight < viewHeight) {
            translateY = (viewHeight - scaledHeight) / 2f
        } else {
            translateY = Math.max(viewHeight - scaledHeight, Math.min(0f, translateY))
        }

        drawMatrix.reset()
        drawMatrix.postScale(scaleFactor, scaleFactor)
        drawMatrix.postTranslate(translateX, translateY)
        drawMatrix.invert(inverseMatrix)
        invalidate()
    }

    fun focusOnRegionByMaskColor(maskColor: Int) {
        val region = regions.find { it.maskColorInt == maskColor } ?: return
        focusOnRegion(region.labelX, region.labelY)
    }

    fun focusOnRegion(cx: Float, cy: Float) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val minScaleForView =
            Math.min(viewWidth / (lineBitmap?.width ?: 1), viewHeight / (lineBitmap?.height ?: 1))

        // Target scale is around 8x the fit scale
        val targetScale = Math.min(20f, Math.max(scaleFactor, minScaleForView * 8f))

        // We want (cx * targetScale + targetTranslateX) = viewWidth / 2
        val targetTranslateX = viewWidth / 2f - cx * targetScale
        val targetTranslateY = viewHeight / 2f - cy * targetScale

        val startScale = scaleFactor
        val startTranslateX = translateX
        val startTranslateY = translateY

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 400
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val p = anim.animatedValue as Float
            scaleFactor = startScale + (targetScale - startScale) * p
            translateX = startTranslateX + (targetTranslateX - startTranslateX) * p
            translateY = startTranslateY + (targetTranslateY - startTranslateY) * p
            updateMatrix()
        }
        animator.start()

        // Thêm hiệu ứng chớp nhá/ripple tại vùng hint để thu hút chú ý
        val targetColor =
            if (currentValidMaskColors.isNotEmpty()) currentValidMaskColors.values.first() else Color.RED
        val effect = TapEffect(cx, cy, targetColor)
        activeEffects.add(effect)
        val effectAnimator = ValueAnimator.ofFloat(0f, 1f)
        effectAnimator.duration = 1000
        effectAnimator.repeatCount = 1
        effectAnimator.repeatMode = ValueAnimator.REVERSE
        effectAnimator.addUpdateListener { anim ->
            effect.progress = anim.animatedValue as Float
            invalidate()
        }
        effectAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activeEffects.remove(effect)
                invalidate()
            }
        })
        effectAnimator.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        if (maskWidth == 0 || maskHeight == 0) return
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        val bX = pts[0].toInt()
        val bY = pts[1].toInt()

        val forgivingPos = getForgivingPos(bX, bY) ?: return
        val startX = forgivingPos.first
        val startY = forgivingPos.second
        val clickedColor = maskPixelsArray!![startY * maskWidth + startX]

        val maskPx = maskPixelsArray ?: return
        val linePx = linePixelsArray ?: return
        val colPx = coloredPixelsArray ?: return

        val targetColor = currentValidMaskColors[clickedColor] ?: return

        // Tránh trùng lặp fill
        if (activeFillers.any { it.maskColor == clickedColor }) return

        val region = regions.find { it.maskColorInt == clickedColor }
        val maxQueueSize = (region?.area ?: (maskWidth * maskHeight / 10)) + 1000

        val filler = AnimatedFiller(
            maskPixels = maskPx,
            linePixels = linePx,
            coloredPixels = colPx,
            width = maskWidth,
            height = maskHeight,
            maskColor = clickedColor,
            targetColor = targetColor,
            startX = startX,
            startY = startY,
            maxQueueSize = maxQueueSize,
            onFinished = {
                onRegionFilledListener?.invoke(it)
            }
        )
        activeFillers.add(filler)

        // Cập nhật ngay lập tức: Xóa highlight của mảng màu này để animation hiện rõ
        val hl = highlightBitmap
        val hlPx = hlPixelsArray
        if (hl != null && hlPx != null) {
            filler.clearHighlight(hlPx)
            hl.setPixels(hlPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        }

        if (region != null) {
            val effect = TapEffect(region.centerX, region.centerY, targetColor)
            activeEffects.add(effect)
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 600
            animator.addUpdateListener { anim ->
                effect.progress = anim.animatedValue as Float
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeEffects.remove(effect)
                }
            })
            animator.start()
        }
        startAnimationLoop()
    }

    private fun getForgivingPos(tapX: Int, tapY: Int): Pair<Int, Int>? {
        val width = maskWidth
        val height = maskHeight
        if (width == 0 || height == 0) return null
        val maskPx = maskPixelsArray ?: return null

        if (tapX in 0 until width && tapY in 0 until height) {
            val c = maskPx[tapY * width + tapX]
            if (currentValidMaskColors.containsKey(c) && !completedMaskColors.contains(c)) {
                return Pair(tapX, tapY)
            }
        }

        val r = 25
        val scanStartX = Math.max(0, tapX - r)
        val scanStartY = Math.max(0, tapY - r)
        val scanEndX = Math.min(width - 1, tapX + r)
        val scanEndY = Math.min(height - 1, tapY + r)

        for (rad in 1..r) {
            for (dx in -rad..rad) {
                val dyMax = Math.sqrt((rad * rad - dx * dx).toDouble()).toInt()
                for (dy in -dyMax..dyMax) {
                    val nx = tapX + dx
                    val ny = tapY + dy
                    if (nx in scanStartX..scanEndX && ny in scanStartY..scanEndY) {
                        val c = maskPx[ny * width + nx]
                        if (currentValidMaskColors.containsKey(c) && !completedMaskColors.contains(c)) {
                            return Pair(nx, ny)
                        }
                    }
                }
            }
        }
        return null
    }

    private var isAnimatingLoop = false
    private fun startAnimationLoop() {
        if (isAnimatingLoop) return
        isAnimatingLoop = true
        postOnAnimation(animationRunnable)
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            if (activeFillers.isEmpty() && activeEffects.isEmpty()) {
                isAnimatingLoop = false
                return
            }
            var changed = false
            val iterator = activeFillers.iterator()
            while (iterator.hasNext()) {
                val filler = iterator.next()

                // Tốc độ loang màu (pixel/frame)
                val speed =
                    Math.max(10f, filler.maxRadius / 15f) // Hoàn thành mượt mà trong ~15 frames

                val isRunning = filler.tick(speed)
                if (!isRunning) {
                    iterator.remove()
                    // Khi filler kết thúc, cập nhật mảng pixel thực tế lên Bitmap
                    val colBmp = coloredBitmap
                    val colArr = coloredPixelsArray
                    if (colBmp != null && colArr != null) {
                        colBmp.setPixels(colArr, 0, colBmp.width, 0, 0, colBmp.width, colBmp.height)
                    }
                }
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private val clipPath = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        val colored = coloredBitmap ?: return
        val hl = highlightBitmap ?: return
        val line = lineBitmap ?: return

        canvas.drawBitmap(colored, drawMatrix, normalPaint)

        // Vẽ các mảng màu đang được animation loang ra (Hardware Accelerated)
        for (filler in activeFillers) {
            canvas.save()
            canvas.concat(drawMatrix)

            // Cắt một vòng tròn hoàn hảo lan rộng dần ra từ điểm chạm
            clipPath.reset()
            clipPath.addCircle(
                filler.startX.toFloat(),
                filler.startY.toFloat(),
                filler.currentRadius,
                android.graphics.Path.Direction.CW
            )
            canvas.clipPath(clipPath)

            // Vẽ bitmap nhỏ chứa sẵn mảng màu tĩnh
            canvas.drawBitmap(filler.localBitmap, filler.left.toFloat(), filler.top.toFloat(), null)
            canvas.restore()
        }
        canvas.drawBitmap(hl, drawMatrix, normalPaint)
        canvas.save()
        canvas.concat(drawMatrix)
        for (effect in activeEffects) {
            val p = effect.progress
            effectPaint.color = effect.color
            effectPaint.alpha = ((1f - p) * 200).toInt()
            effectPaint.strokeWidth = 1f + (1f - p) * 3f
            val rad = p * 40f / scaleFactor
            canvas.drawCircle(effect.x, effect.y, rad, effectPaint)

            particlePaint.color = effect.color
            particlePaint.alpha = ((1f - p) * 255).toInt()
            val particleRadius = (1f - p) * 3f / scaleFactor
            for (i in 0 until 8) {
                val angle = i * Math.PI / 4.0
                val distance = 5f / scaleFactor + p * 50f / scaleFactor
                val px = effect.x + Math.cos(angle).toFloat() * distance
                val py = effect.y + Math.sin(angle).toFloat() * distance
                canvas.drawCircle(px, py, particleRadius, particlePaint)
            }
        }
        canvas.restore()

        canvas.save()
        canvas.concat(drawMatrix)
        for (region in regions) {
            if (!completedMaskColors.contains(region.maskColorInt) && !region.hideNumber) {
                val screenRadius = region.radius * scaleFactor
                if (screenRadius >= 25f) {
                    textPaint.textSize = Math.max(8f, Math.min(region.radius * 0.7f, 60f))
                    val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
                    canvas.drawText(
                        region.number.toString(),
                        region.labelX,
                        region.labelY - textOffset,
                        textPaint
                    )
                }
            }
        }
        canvas.restore()

        canvas.drawBitmap(line, drawMatrix, multiplyPaint)
    }
}
