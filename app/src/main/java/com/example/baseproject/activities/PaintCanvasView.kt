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
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.caverock.androidsvg.SVG
import com.example.baseproject.data.AnimatedFiller
import com.example.baseproject.data.DetailRevealEngine
import com.example.baseproject.data.RegionData
import com.example.baseproject.highlight.HighlightRenderer
import com.example.baseproject.highlight.HighlightTheme
import com.example.baseproject.highlight.HighlightThemes

class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Tag debug tạm thời — xoá cùng các Log.d/w bên dưới sau khi xác định xong nguyên nhân.
        private const val DBG_TAG = "PBN_DBG_a91f"
        private const val CANVAS_BACKGROUND_COLOR = 0xFFF3F1F3.toInt()

        // Thumbnail phải nền TRẮNG, không dùng CANVAS_BACKGROUND_COLOR: nền xám trùng khít
        // màu panel của CurrentPictureDialog (@color/grey_50 = #F3F1F3) khiến card chìm vào
        // nền, chỉ còn bóng đổ làm ranh giới nên nhìn rất bẩn. Trắng cũng là phần tử đơn vị
        // của phép nhân nên nét vẽ (multiplyPaint) vẫn giữ nguyên độ sắc.
        private const val THUMBNAIL_BACKGROUND_COLOR = 0xFFFFFFFF.toInt()

        // Ngưỡng bán kính trên MÀN HÌNH (không phải trên bitmap) để quyết định có hiện số
        // hay không — giữ nguyên như trước, chỉ đổi nguồn region.radius (giờ chính xác hơn).
        private const val MIN_SCREEN_RADIUS_TO_SHOW_LABEL = 25f

        // Cỡ chữ MẶC ĐỊNH khi vùng đủ lớn, tính bằng px màn hình — KHÔNG nhân thêm
        // scaleFactor, để số giữ nguyên kích thước khi zoom ra/vào (giống app mẫu), thay vì
        // co giãn theo zoom như trước.
        private const val LABEL_TEXT_SIZE_PX = 30f
        private const val FILL_ANIMATION_DURATION_MS = 200f
        private const val FRAME_DURATION_MS = 16.67f

        // Vùng nhỏ hơn mức "thoải mái" vẫn phải hiện số (nếu đã qua ngưỡng ẩn/hiện ở trên),
        // nhưng chữ phải co lại theo đúng khoảng trống thật để không tràn ra ngoài — hệ số
        // này nhân với bán kính an toàn trên màn hình để ra cỡ chữ tối đa cho phép.
        private const val LABEL_SAFE_RADIUS_FACTOR = 1.3f

    }

    private var displayLineBitmap: Bitmap? = null
    private var displayLineSvg: SVG? = null
    private var useVectorDisplayLine: Boolean = true
    private var maskWidth: Int = 0
    private var maskHeight: Int = 0
    private var coloredBitmap: Bitmap? = null
    private var highlightBitmap: Bitmap? = null
    private var revealedDetailBitmap: Bitmap? = null

    // Coroutine Scope cho PaintCanvasView
    private val scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    // Arrays for fast processing
    private var maskPixelsArray: IntArray? = null
    private var linePixelsArray: IntArray? = null
    private var coloredPixelsArray: IntArray? = null
    private var hlPixelsArray: IntArray? = null
    private var detailSourcePixelsArray: IntArray? = null
    private var revealedDetailPixelsArray: IntArray? = null

    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val bitmapBounds = RectF()

    private val normalPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }

    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    var onRegionFilledListener: ((maskColor: Int) -> Unit)? = null

    private var regions: List<RegionData> = emptyList()
    private val labelPointBuffer = FloatArray(2)
    private var completedMaskColors: Set<Int> = emptySet()

    private var hideAllLabels: Boolean = false
    private var isInteractionLocked: Boolean = false
    private var fillInAnimationEnabled: Boolean = true

    private var currentValidMaskColors: Map<Int, Int> = emptyMap()
    private var highlightTheme: HighlightTheme = HighlightThemes.defaultChecker()
    private var highlightEnabled: Boolean = true
    private var currentHighlightTargets: IntArray = IntArray(0)

    data class TapEffect(val x: Float, val y: Float, val color: Int) {
        var progress: Float = 0f
    }

    private val activeEffects = mutableListOf<TapEffect>()
    private val activeFillers = mutableListOf<AnimatedFiller>()

    init {
        setupGestureDetectors()
    }

    //region SETUPSCALE
    private fun setupGestureDetectors() {
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val fitScale = Math.min(
                        width.toFloat() / (maskWidth.takeIf { it > 0 } ?: 1),
                        height.toFloat() / (maskHeight.takeIf { it > 0 } ?: 1)
                    )
                    val minScale = fitScale * 0.8f
                    val newScale =
                        Math.max(minScale, Math.min(scaleFactor * detector.scaleFactor, 20.0f))
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

    suspend fun setBitmapsSuspend(
        logicLine: Bitmap,
        displayLine: Bitmap,
        displayLineSvg: SVG?,
        mask: Bitmap,
        detail: Bitmap?,
        regionsData: List<RegionData>
    ) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val w = mask.width
            val h = mask.height

            val coloredBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val highlightBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val detailRevealBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            val maskPx = IntArray(w * h)
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)

            val detailPx = if (detail != null && detail.width == w && detail.height == h) {
                IntArray(w * h).also {
                    detail.getPixels(it, 0, w, 0, 0, w, h)
                }
            } else {
                null
            }

            val lp = if (logicLine.width == w && logicLine.height == h) {
                IntArray(w * h).also {
                    logicLine.getPixels(it, 0, w, 0, 0, w, h)
                }
            } else {
                IntArray(w * h)
            }

            // Giải phóng maskBitmap để tiết kiệm 4.6MB RAM
            mask.recycle()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                displayLineBitmap = displayLine
                this@PaintCanvasView.displayLineSvg = displayLineSvg
                useVectorDisplayLine = displayLineSvg != null
                maskWidth = w
                maskHeight = h
                regions = regionsData

                coloredBitmap = coloredBmp
                highlightBitmap = highlightBmp
                revealedDetailBitmap = detailRevealBmp

                maskPixelsArray = maskPx
                coloredPixelsArray = IntArray(w * h)
                linePixelsArray = lp
                hlPixelsArray = IntArray(w * h)
                detailSourcePixelsArray = detailPx
                revealedDetailPixelsArray = if (detailPx != null) IntArray(w * h) else null

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
            Log.w(
                DBG_TAG,
                "RESTORE_PROGRESS_CALLED mapSize=${completedMap.size} t=${System.currentTimeMillis()}",
                Exception("stack trace")
            )
            if (completedMap.isEmpty()) return@withContext

            val maskPx = maskPixelsArray ?: return@withContext
            val linePx = linePixelsArray ?: return@withContext
            val colPx = coloredPixelsArray ?: return@withContext
            val detailSrcPx = detailSourcePixelsArray
            val detailOutPx = revealedDetailPixelsArray
            val w = maskWidth
            val h = maskHeight
            if (detailOutPx != null) {
                java.util.Arrays.fill(detailOutPx, 0)
            }

            // Tối ưu hóa cực đại: Linear Probing Hash Map thuần mảng nguyên thủy (O(1) lookup)
            // Sức chứa phải luôn lớn hơn 2x số phần tử: linear probing lặp vô hạn nếu bảng đầy,
            // mà fillAllSuspend() nạp vào TOÀN BỘ region của level chứ không chỉ phần đã tô.
            var capacity = 4096 // Luôn là lũy thừa của 2
            while (capacity < completedMap.size * 2) capacity = capacity shl 1
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
                    if (detailSrcPx != null && detailOutPx != null) {
                        detailOutPx[i] = detailSrcPx[i]
                    }
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
                revealedDetailBitmap?.let { detailBmp ->
                    if (detailOutPx != null) {
                        detailBmp.setPixels(detailOutPx, 0, w, 0, 0, w, h)
                    }
                }
                invalidate()
                // ĐÃ XÓA vòng lặp onRegionFilledListener để tránh gọi 500 lần trên UI thread

                // DEBUG: đọc lại pixel tại tâm mỗi vùng vừa khôi phục, để so sánh trực tiếp với
                // các dòng PIXEL_WATCH[T*] ghi được lúc tô sống (trước khi back ra vào lại).
                for (maskColor in completedMap.keys) {
                    val r = regions.find { it.maskColorInt == maskColor } ?: continue
                    logPixelWatch("AFTER_RESTORE", r.number, maskColor, r.centerX.toInt(), r.centerY.toInt())
                }
            }
        }

    /**
     * Tô sẵn toàn bộ level bằng bảng màu đầy đủ để xem trước ngay trên canvas (có zoom/pan).
     *
     * Chỉ thay đổi lớp pixel đang hiển thị — không đụng tới tiến trình đã lưu, không phát
     * [onRegionFilledListener]. Muốn quay lại trạng thái thật thì gọi [resetProgress] rồi
     * [restoreProgressSuspend] với map tiến trình hiện có.
     */
    suspend fun fillAllSuspend(allMaskColorsToTargetColors: Map<Int, Int>) {
        restoreProgressSuspend(allMaskColorsToTargetColors)
    }

    /**
     * Ẩn toàn bộ số thứ tự và khoá thao tác chạm-để-tô — dùng khi canvas đang ở chế độ xem
     * trước đã tô đầy, lúc đó số hiện lên chỉ làm rối và một cú chạm sẽ ghi nhầm tiến trình
     * thật. Zoom và kéo vẫn hoạt động để soi kỹ từng vùng.
     */
    fun setPreviewFillMode(enabled: Boolean) {
        hideAllLabels = enabled
        isInteractionLocked = enabled
        invalidate()
    }

    fun setFillInAnimationEnabled(enabled: Boolean) {
        fillInAnimationEnabled = enabled
    }

    fun resetProgress() {
        Log.w(
            DBG_TAG,
            "RESET_PROGRESS_CALLED completedBefore=$completedMaskColors " +
                "activeFillersBefore=${activeFillers.map { it.maskColor }} t=${System.currentTimeMillis()}",
            Exception("stack trace")
        )
        completedMaskColors = emptySet()
        activeFillers.clear()
        val colArr = coloredPixelsArray ?: return
        for (i in colArr.indices) {
            colArr[i] = 0 // Transparent
        }
        revealedDetailPixelsArray?.let { java.util.Arrays.fill(it, 0) }
        val colBmp = coloredBitmap ?: return
        colBmp.setPixels(colArr, 0, colBmp.width, 0, 0, colBmp.width, colBmp.height)
        val detailBmp = revealedDetailBitmap
        val detailArr = revealedDetailPixelsArray
        if (detailBmp != null && detailArr != null) {
            detailBmp.setPixels(detailArr, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        }
        invalidate()
    }

    fun setActiveColors(maskToTargetColors: Map<Int, Int>) {
        currentValidMaskColors = maskToTargetColors
    }

    fun setHighlightTheme(theme: HighlightTheme) {
        highlightTheme = theme
        rerenderHighlight()
    }

    fun setHighlightEnabled(enabled: Boolean) {
        highlightEnabled = enabled
        if (!enabled) {
            clearHighlightImmediately()
        } else {
            rerenderHighlight()
        }
    }

    fun setCompletedRegions(completed: Set<Int>) {
        this.completedMaskColors = completed
        invalidate()
    }

    private fun completeRegionForMaskColor(maskColor: Int, targetColor: Int) {
        val maskPx = maskPixelsArray ?: return
        val colArr = coloredPixelsArray ?: return
        val colBmp = coloredBitmap ?: return
        val detailSrcPx = detailSourcePixelsArray
        val detailOutPx = revealedDetailPixelsArray
        val detailBmp = revealedDetailBitmap

        DetailRevealEngine.completeRegionForMaskColor(
            maskPixels = maskPx,
            coloredPixels = colArr,
            detailSourcePixels = detailSrcPx,
            revealedDetailPixels = detailOutPx,
            maskColor = maskColor,
            targetColor = targetColor,
        )
        colBmp.setPixels(colArr, 0, colBmp.width, 0, 0, colBmp.width, colBmp.height)
        if (detailBmp != null && detailOutPx != null) {
            detailBmp.setPixels(detailOutPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        }
    }

    // DEBUG: đọc lại đúng 1 pixel của vùng vừa tô xong, tại nhiều mốc thời gian sau đó, để
    // phát hiện xem có gì đó âm thầm ghi đè coloredBitmap/revealedDetailBitmap sau khi tô
    // xong hay không (nếu colored/detail đổi giá trị giữa các mốc log thì đó chính là thủ phạm).
    private fun logPixelWatch(label: String, regionNumber: Int?, maskColor: Int, x: Int, y: Int) {
        val colored = coloredBitmap ?: return
        val detail = revealedDetailBitmap
        if (x !in 0 until colored.width || y !in 0 until colored.height) return
        val coloredPx = colored.getPixel(x, y)
        val detailPx = detail?.getPixel(x, y) ?: 0
        val stillCompleted = completedMaskColors.contains(maskColor)
        Log.d(
            DBG_TAG,
            "PIXEL_WATCH[$label] region=$regionNumber mask=$maskColor(${Integer.toHexString(maskColor)}) " +
                "colored=${Integer.toHexString(coloredPx)} detail=${Integer.toHexString(detailPx)} " +
                "stillCompleted=$stillCompleted t=${System.currentTimeMillis()}"
        )
    }

    /**
     * Tạo một ảnh thu nhỏ (Thumbnail) thể hiện tiến trình tô màu hiện tại.
     * Ảnh sẽ được scale xuống thumbSize để tiết kiệm dung lượng.
     */
    fun generateThumbnail(thumbSize: Int): Bitmap? {
        val colored = coloredBitmap ?: return null
        val line = displayLineBitmap ?: return null
        val w = maskWidth
        val h = maskHeight
        if (w == 0 || h == 0) return null

        try {
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(THUMBNAIL_BACKGROUND_COLOR)
            canvas.drawBitmap(colored, 0f, 0f, null)
            revealedDetailBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            drawDisplayLineInMaskBounds(canvas, line)

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
        if (!highlightEnabled) {
            currentHighlightTargets = IntArray(0)
            clearHighlightImmediately()
            return
        }

        val animatingColors = activeFillers.map { it.maskColor }.toSet()
        val activeTargets = targetMaskColors.filter {
            !completedMaskColors.contains(it) && !animatingColors.contains(it)
        }.toIntArray()
        currentHighlightTargets = activeTargets
        Log.d(
            DBG_TAG,
            "HIGHLIGHT_REQ in=$targetMaskColors completed=$completedMaskColors " +
                "animating=$animatingColors -> active=${activeTargets.toList()} " +
                "t=${System.currentTimeMillis()}"
        )

        if (activeTargets.isEmpty()) {
            clearHighlightImmediately()
            return
        }

        rerenderHighlight()
    }

    private fun clearHighlightImmediately() {
        val hl = highlightBitmap ?: return
        val hlPixels = hlPixelsArray ?: return
        java.util.Arrays.fill(hlPixels, 0)
        hl.setPixels(hlPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        invalidate()
    }

    private fun rerenderHighlight() {
        val hl = highlightBitmap ?: return
        val hlPixels = hlPixelsArray ?: return
        val width = maskWidth
        val height = maskHeight
        if (width == 0 || height == 0) return
        val maskPixels = maskPixelsArray ?: return

        if (!highlightEnabled || currentHighlightTargets.isEmpty()) {
            java.util.Arrays.fill(hlPixels, 0)
        } else {
            HighlightRenderer.render(
                maskPixels = maskPixels,
                outputPixels = hlPixels,
                width = width,
                activeTargets = currentHighlightTargets.copyOf(),
                theme = highlightTheme,
                alphaFraction = 1f
            )
        }

        hl.setPixels(hlPixels, 0, width, 0, 0, width, height)
        Log.d(
            DBG_TAG,
            "HIGHLIGHT_RENDER targets=${currentHighlightTargets.toList()} t=${System.currentTimeMillis()}"
        )
        invalidate()
    }

    private fun updateMatrix() {
        val viewWidth = width.toFloat();
        val viewHeight = height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f || maskWidth == 0 || maskHeight == 0) return

        val scaledWidth = maskWidth * scaleFactor
        val scaledHeight = maskHeight * scaleFactor

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

    fun getDisplayedBitmapRectInView(): RectF? {
        if (width == 0 || height == 0 || maskWidth == 0 || maskHeight == 0) return null

        return RectF(
            translateX,
            translateY,
            translateX + maskWidth * scaleFactor,
            translateY + maskHeight * scaleFactor
        )
    }

    fun focusOnRegion(cx: Float, cy: Float) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val minScaleForView =
            Math.min(
                viewWidth / (maskWidth.takeIf { it > 0 } ?: 1),
                viewHeight / (maskHeight.takeIf { it > 0 } ?: 1)
            )

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
        if (isInteractionLocked) return
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

        if (!fillInAnimationEnabled) {
            completeInstantFill(clickedColor, targetColor)
            return
        }

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
            },
            detailPixels = detailSourcePixelsArray
        )

        // DEBUG: kiểm tra xem vùng vừa tap có chia sẻ pixel viền (bleed) với filler nào
        // đang chạy không — nếu có, pixel đó sẽ bị filler xong SAU ghi đè lên (last-write-wins).
        if (activeFillers.isNotEmpty()) {
            val newIndicesSet = filler.indices.toHashSet()
            for (other in activeFillers) {
                val overlap = other.indices.count { it in newIndicesSet }
                if (overlap > 0) {
                    Log.w(
                        DBG_TAG,
                        "BLEED_OVERLAP newMask=$clickedColor(${Integer.toHexString(clickedColor)}) " +
                            "vsActiveMask=${other.maskColor}(${Integer.toHexString(other.maskColor)}) " +
                            "overlapPixels=$overlap t=${System.currentTimeMillis()}"
                    )
                }
            }
        }
        Log.d(
            DBG_TAG,
            "TAP_START region=${region?.number} mask=$clickedColor(${Integer.toHexString(clickedColor)}) " +
                "indices=${filler.indices.size} activeFillersBefore=${activeFillers.map { it.maskColor }} " +
                "hasDetailData=${detailSourcePixelsArray != null} t=${System.currentTimeMillis()}"
        )
        activeFillers.add(filler)

        // Cập nhật ngay lập tức: Xóa highlight của mảng màu này để animation hiện rõ
        val hl = highlightBitmap
        val hlPx = hlPixelsArray
        if (hl != null && hlPx != null) {
            filler.clearHighlight(hlPx)
            hl.setPixels(hlPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            currentHighlightTargets =
                currentHighlightTargets.filter { it != clickedColor }.toIntArray()
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
                    Math.max(10f, filler.maxRadius / (FILL_ANIMATION_DURATION_MS / FRAME_DURATION_MS))

                val isRunning = filler.tick(speed)
                if (!isRunning) {
                    iterator.remove()
                    // Khi filler kết thúc: tick() đã tô màu phẳng cho cụm liên thông cục bộ
                    // (để có hiệu ứng loang), completeRegionForMaskColor() quét nốt toàn ảnh để
                    // tô/phủ chi tiết cho MỌI pixel còn lại cùng mã màu — kể cả ở cụm tách rời
                    // khác — rồi đẩy cả 2 lớp bitmap lên canvas trong 1 lần.
                    completeRegionForMaskColor(filler.maskColor, filler.targetColor)
                    completedMaskColors = completedMaskColors + filler.maskColor
                    val regionNumber = regions.find { it.maskColorInt == filler.maskColor }?.number
                    Log.d(
                        DBG_TAG,
                        "FILL_DONE region=$regionNumber mask=${filler.maskColor}(${Integer.toHexString(filler.maskColor)}) " +
                            "currentHighlightTargets=${currentHighlightTargets.toList()} " +
                            "t=${System.currentTimeMillis()}"
                    )
                    // Dùng tâm vùng (thay vì điểm chạm tay) để toạ độ trùng khớp với log
                    // AFTER_RESTORE trong restoreProgressSuspend() — so sánh đúng 1 pixel.
                    val watchRegion = regions.find { it.maskColorInt == filler.maskColor }
                    val watchX = watchRegion?.centerX?.toInt() ?: filler.startX
                    val watchY = watchRegion?.centerY?.toInt() ?: filler.startY
                    logPixelWatch("T0", regionNumber, filler.maskColor, watchX, watchY)
                    postDelayed({
                        logPixelWatch("T300", regionNumber, filler.maskColor, watchX, watchY)
                    }, 300)
                    postDelayed({
                        logPixelWatch("T1000", regionNumber, filler.maskColor, watchX, watchY)
                    }, 1000)
                    postDelayed({
                        logPixelWatch("T3000", regionNumber, filler.maskColor, watchX, watchY)
                    }, 3000)
                    filler.dispatchFinished()
                }
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun completeInstantFill(maskColor: Int, targetColor: Int) {
        completeRegionForMaskColor(maskColor, targetColor)
        completedMaskColors = completedMaskColors + maskColor
        clearHighlightForMaskColor(maskColor)
        onRegionFilledListener?.invoke(maskColor)
        invalidate()
    }

    private fun clearHighlightForMaskColor(maskColor: Int) {
        val maskPx = maskPixelsArray ?: return
        val hl = highlightBitmap ?: return
        val hlPx = hlPixelsArray ?: return
        var changed = false

        for (index in maskPx.indices) {
            if (maskPx[index] == maskColor && hlPx[index] != 0) {
                hlPx[index] = 0
                changed = true
            }
        }

        if (changed) {
            hl.setPixels(hlPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        }
        currentHighlightTargets =
            currentHighlightTargets.filter { it != maskColor }.toIntArray()
    }

    private val clipPath = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(CANVAS_BACKGROUND_COLOR)

        val colored = coloredBitmap ?: return
        val hl = highlightBitmap ?: return
        val line = displayLineBitmap ?: return

        canvas.save()
        canvas.concat(drawMatrix)
        bitmapBounds.set(0f, 0f, maskWidth.toFloat(), maskHeight.toFloat())
        canvas.drawRect(bitmapBounds, whitePaint)
        canvas.restore()

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
        revealedDetailBitmap?.let { canvas.drawBitmap(it, drawMatrix, normalPaint) }
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

        // Vẽ số ở toạ độ MÀN HÌNH (không canvas.concat(drawMatrix)) — cỡ chữ vì vậy KHÔNG bị
        // co/phình theo scaleFactor như khi vẽ bên trong canvas đã transform. Ẩn/hiện vẫn
        // dựa trên kích thước thật trên màn hình ở zoom hiện tại (screenRadius), giữ nguyên
        // hành vi cũ — chỉ cỡ chữ khi hiện là cố định thay vì co theo zoom.
        for (region in regions) {
            if (hideAllLabels) break
            if (completedMaskColors.contains(region.maskColorInt) || region.hideNumber) continue
            val screenRadius = region.radius * scaleFactor
            if (screenRadius < MIN_SCREEN_RADIUS_TO_SHOW_LABEL) continue

            labelPointBuffer[0] = region.labelX
            labelPointBuffer[1] = region.labelY
            drawMatrix.mapPoints(labelPointBuffer)

            textPaint.textSize = Math.min(LABEL_TEXT_SIZE_PX, screenRadius * LABEL_SAFE_RADIUS_FACTOR)
                .coerceAtLeast(8f)
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(
                region.number.toString(),
                labelPointBuffer[0],
                labelPointBuffer[1] - textOffset,
                textPaint
            )
        }

        canvas.save()
        canvas.concat(drawMatrix)
        drawDisplayLineInMaskBounds(canvas, line)
        canvas.restore()
    }

    private fun drawBitmapInMaskBounds(canvas: Canvas, bitmap: Bitmap, paint: Paint?) {
        bitmapBounds.set(0f, 0f, maskWidth.toFloat(), maskHeight.toFloat())
        canvas.drawBitmap(bitmap, null, bitmapBounds, paint)
    }

    private fun drawDisplayLineInMaskBounds(canvas: Canvas, fallbackBitmap: Bitmap) {
        val svg = displayLineSvg
        if (!useVectorDisplayLine || svg == null) {
            drawBitmapInMaskBounds(canvas, fallbackBitmap, multiplyPaint)
            return
        }

        bitmapBounds.set(0f, 0f, maskWidth.toFloat(), maskHeight.toFloat())
        try {
            svg.renderToCanvas(canvas, bitmapBounds)
        } catch (_: Exception) {
            drawBitmapInMaskBounds(canvas, fallbackBitmap, multiplyPaint)
        }
    }
}
