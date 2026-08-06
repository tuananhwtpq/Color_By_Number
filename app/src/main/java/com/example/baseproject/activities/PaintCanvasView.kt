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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
        private const val CANVAS_BACKGROUND_COLOR = 0xFFF3F1F3.toInt()

        // Ngưỡng bán kính trên MÀN HÌNH (không phải trên bitmap) để quyết định có hiện số
        // hay không — giữ nguyên như trước, chỉ đổi nguồn region.radius (giờ chính xác hơn).
        private const val MIN_SCREEN_RADIUS_TO_SHOW_LABEL = 25f

        // Cỡ chữ MẶC ĐỊNH khi vùng đủ lớn, tính bằng px màn hình — KHÔNG nhân thêm
        // scaleFactor, để số giữ nguyên kích thước khi zoom ra/vào (giống app mẫu), thay vì
        // co giãn theo zoom như trước.
        private const val LABEL_TEXT_SIZE_PX = 30f

        // Vùng nhỏ hơn mức "thoải mái" vẫn phải hiện số (nếu đã qua ngưỡng ẩn/hiện ở trên),
        // nhưng chữ phải co lại theo đúng khoảng trống thật để không tràn ra ngoài — hệ số
        // này nhân với bán kính an toàn trên màn hình để ra cỡ chữ tối đa cho phép.
        private const val LABEL_SAFE_RADIUS_FACTOR = 1.3f

        // Đường ranh giữa hai ô được tách ra từ cùng một mảng lớn. line.png vốn CHỈ có 0
        // (mực) và 255 (giấy), nên 235 là giá trị trung gian duy nhất — nhận ra pixel seam
        // ngay từ chính line.png, khỏi cần asset riêng hay thêm bộ nhớ. Phải khớp với
        // CELL_SEAM_LINE_VALUE trong tools/generate_level.py (ghi lại trong config ở khoá
        // generation.cell_seam_line_value).
        private const val SEAM_LINE_VALUE = 235
        private const val SEAM_LINE_COLOR = 0xFFEBEBEB.toInt()
        private const val SEAM_LINE_HIDDEN_COLOR = 0xFFFFFFFF.toInt()

        // mask.png tô nền đen cho mọi pixel KHÔNG thuộc vùng nào (nét mực). getPixels trả về
        // ARGB nên giá trị đó là 0xFF000000 chứ không phải 0 — nhầm chỗ này thì mọi pixel
        // seam nằm sát viền mực đều bị coi là "còn hàng xóm chưa tô" và không bao giờ ẩn.
        private const val MASK_BACKGROUND_COLOR = 0xFF000000.toInt()
    }

    private var lineBitmap: Bitmap? = null
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

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    var onRegionFilledListener: ((maskColor: Int) -> Unit)? = null

    private var regions: List<RegionData> = emptyList()
    private val labelPointBuffer = FloatArray(2)
    private var completedMaskColors: Set<Int> = emptySet()

    // Vị trí các pixel seam, chụp lại một lần lúc nạp ảnh. Vài nghìn phần tử (đo trên data:
    // ~8900 trên canvas 1024) nên rẻ hơn hẳn việc quét lại cả triệu pixel mỗi lần đổi trạng
    // thái, và cần thiết vì khi ẩn seam ta ghi đè giá trị 235 nên mất dấu vị trí gốc.
    private var seamPixelIndices: IntArray = IntArray(0)

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
                        width.toFloat() / (lineBitmap?.width ?: 1),
                        height.toFloat() / (lineBitmap?.height ?: 1)
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
        line: Bitmap,
        mask: Bitmap,
        detail: Bitmap?,
        regionsData: List<RegionData>
    ) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val w = line.width
            val h = line.height

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

            val lp = IntArray(w * h)
            line.getPixels(lp, 0, w, 0, 0, w, h)

            // Ghi lại vị trí seam để sau này bật/tắt được. Chỉ level có mảng lớn bị chia ô
            // mới có seam; level không có thì mảng rỗng và toàn bộ phần này không tốn gì.
            var seamCount = 0
            for (pixel in lp) {
                if (isSeamPixelValue(pixel)) seamCount++
            }
            val seams = IntArray(seamCount)
            var seamCursor = 0
            for (i in lp.indices) {
                if (isSeamPixelValue(lp[i])) seams[seamCursor++] = i
            }

            // Chỉ cần bitmap ghi được khi thật sự có seam để bật/tắt. Không copy vô ích cho
            // level thường, và KHÔNG recycle bản gốc vì nó còn được dùng chỗ khác
            // (ensureFullPreviewBitmap trong PaintActivity dùng chung đúng bitmap này).
            val drawableLine = if (seamCount > 0 && !line.isMutable) {
                line.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                line
            }

            // Giải phóng maskBitmap để tiết kiệm 4.6MB RAM
            mask.recycle()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                seamPixelIndices = seams
                lineBitmap = drawableLine
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
            }
        }

    fun resetProgress() {
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
        val changed = this.completedMaskColors != completed
        this.completedMaskColors = completed
        if (changed) {
            updateSeamVisibility()
        }
        invalidate()
    }

    private fun isSeamPixelValue(pixel: Int): Boolean =
        (pixel shr 16 and 0xFF) == SEAM_LINE_VALUE &&
            (pixel shr 8 and 0xFF) == SEAM_LINE_VALUE &&
            (pixel and 0xFF) == SEAM_LINE_VALUE

    /**
     * Ẩn đường ranh giữa hai ô khi CẢ HAI đã được tô, hiện lại khi còn ít nhất một ô chưa tô.
     *
     * Hai ô tách ra từ cùng một mảng lớn mang cùng một màu, nên khi tô xong đường ranh chỉ
     * còn là vết bẩn trên tranh — ẩn đi thì bức tranh hoàn thành đúng bằng màu gốc. Ngược
     * lại, khi còn ô chưa tô thì đường ranh là thứ DUY NHẤT cho user biết ô tiếp theo nằm
     * đâu (hai ô cùng màu, chưa tô thì đều trắng), nên phải giữ.
     *
     * Chỉ quét đúng các pixel seam đã ghi nhận sẵn nên chi phí không đáng kể; phần tốn nhất
     * là một lần setPixels, và nó chỉ chạy khi tập vùng đã tô thật sự đổi.
     */
    private fun updateSeamVisibility() {
        if (seamPixelIndices.isEmpty()) return
        val linePx = linePixelsArray ?: return
        val maskPx = maskPixelsArray ?: return
        val bitmap = lineBitmap ?: return
        if (!bitmap.isMutable) return
        val w = maskWidth
        val h = maskHeight

        var dirty = false
        for (index in seamPixelIndices) {
            val ownMask = maskPx[index]
            var visible = !isRegionMask(ownMask) || !completedMaskColors.contains(ownMask)

            if (!visible) {
                val x = index % w
                val y = index / w
                if (x > 0) visible = isUnfilledNeighbour(maskPx[index - 1], ownMask)
                if (!visible && x < w - 1) visible = isUnfilledNeighbour(maskPx[index + 1], ownMask)
                if (!visible && y > 0) visible = isUnfilledNeighbour(maskPx[index - w], ownMask)
                if (!visible && y < h - 1) visible = isUnfilledNeighbour(maskPx[index + w], ownMask)
            }

            val wanted = if (visible) SEAM_LINE_COLOR else SEAM_LINE_HIDDEN_COLOR
            if (linePx[index] != wanted) {
                linePx[index] = wanted
                dirty = true
            }
        }

        if (dirty) {
            bitmap.setPixels(linePx, 0, w, 0, 0, w, h)
        }
    }

    private fun isRegionMask(maskColor: Int): Boolean =
        maskColor != 0 && maskColor != MASK_BACKGROUND_COLOR

    /**
     * Hàng xóm là một Ô KHÁC và ô đó chưa được tô. Pixel nền/nét mực không tính — chúng
     * không bao giờ được "tô" nên nếu tính vào thì seam sát viền sẽ hiện vĩnh viễn.
     */
    private fun isUnfilledNeighbour(neighbourMask: Int, ownMask: Int): Boolean =
        isRegionMask(neighbourMask) &&
            neighbourMask != ownMask &&
            !completedMaskColors.contains(neighbourMask)

    private fun revealDetailForMaskColor(maskColor: Int) {
        val maskPx = maskPixelsArray ?: return
        val detailSrcPx = detailSourcePixelsArray ?: return
        val detailOutPx = revealedDetailPixelsArray ?: return
        val detailBmp = revealedDetailBitmap ?: return

        DetailRevealEngine.revealDetailForMaskColor(
            maskPixels = maskPx,
            detailSourcePixels = detailSrcPx,
            revealedDetailPixels = detailOutPx,
            maskColor = maskColor,
        )
        detailBmp.setPixels(detailOutPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
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
            canvas.drawColor(CANVAS_BACKGROUND_COLOR)
            canvas.drawBitmap(colored, 0f, 0f, null)
            revealedDetailBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
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

    fun getDisplayedBitmapRectInView(): RectF? {
        val line = lineBitmap ?: return null
        if (width == 0 || height == 0) return null

        return RectF(
            translateX,
            translateY,
            translateX + line.width * scaleFactor,
            translateY + line.height * scaleFactor
        )
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
                    revealDetailForMaskColor(filler.maskColor)
                }
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private val clipPath = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(CANVAS_BACKGROUND_COLOR)

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

        canvas.drawBitmap(line, drawMatrix, multiplyPaint)
    }
}
