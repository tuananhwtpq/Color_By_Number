package com.pixlory.color.by.number.activities

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
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.caverock.androidsvg.SVG
import com.pixlory.color.by.number.BuildConfig
import com.pixlory.color.by.number.data.AnimatedFiller
import com.pixlory.color.by.number.data.DetailRevealEngine
import com.pixlory.color.by.number.data.EdgeUnderpaintEngine
import com.pixlory.color.by.number.data.FillAssetPrewarmPolicy
import com.pixlory.color.by.number.data.FillAnimationAssetFactory
import com.pixlory.color.by.number.data.MaskColorPixelIndex
import com.pixlory.color.by.number.data.MaskColorPixelRegion
import com.pixlory.color.by.number.data.PreparedFillAsset
import com.pixlory.color.by.number.data.RegionData
import com.pixlory.color.by.number.highlight.HighlightRenderer
import com.pixlory.color.by.number.highlight.HighlightTheme
import com.pixlory.color.by.number.highlight.HighlightThemes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val CANVAS_BACKGROUND_COLOR = 0xFFF3F1F3.toInt()

        // Thumbnail phải nền TRẮNG, không dùng CANVAS_BACKGROUND_COLOR: nền xám trùng khít
        // màu panel của CurrentPictureDialog (@color/grey_50 = #F3F1F3) khiến card chìm vào
        // nền, chỉ còn bóng đổ làm ranh giới nên nhìn rất bẩn. Trắng cũng là phần tử đơn vị
        // của phép nhân nên nét vẽ (multiplyPaint) vẫn giữ nguyên độ sắc.
        private const val THUMBNAIL_BACKGROUND_COLOR = 0xFFFFFFFF.toInt()
        private const val WARM_PAPER_BACKGROUND_COLOR = 0xFFFAF7F1.toInt()

        // Cỡ chữ MẶC ĐỊNH khi vùng đủ lớn, tính bằng px màn hình — KHÔNG nhân thêm
        // scaleFactor, để số giữ nguyên kích thước khi zoom ra/vào (giống app mẫu), thay vì
        // co giãn theo zoom như trước.
        private const val LABEL_TEXT_SIZE_PX = 30f
        private const val COMPLETION_FIT_ANIMATION_DURATION_MS = 420L
        private const val DEFAULT_FRAME_DURATION_MS = 16.67f
        private const val FILL_TAP_EFFECT_DURATION_MS = 220L
        private const val HINT_TAP_EFFECT_DURATION_MS = 1_000L
        private const val FILL_ASSET_PREWARM_BUDGET_BYTES = 16 * 1024 * 1024
        private const val INVALID_POINTER_ID = -1
        private const val FREE_PAN_VISIBLE_EDGE_DP = 48f

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
    private var scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    // Arrays for fast processing
    private var maskPixelsArray: IntArray? = null
    private var fillCoveragePixelsArray: IntArray? = null
    private var displayLineLumaPixelsArray: IntArray? = null
    private var coloredPixelsArray: IntArray? = null
    private var hlPixelsArray: IntArray? = null
    private var detailSourcePixelsArray: IntArray? = null
    private var revealedDetailPixelsArray: IntArray? = null
    private var maskColorPixelRegions: Map<Int, MaskColorPixelRegion> = emptyMap()

    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val bitmapBounds = RectF()

    private val normalPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (BuildConfig.USE_WARM_PAPER_CANVAS) WARM_PAPER_BACKGROUND_COLOR else Color.WHITE
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

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val freePanVisibleEdgePx = FREE_PAN_VISIBLE_EDGE_DP * resources.displayMetrics.density
    private var activePanPointerId = INVALID_POINTER_ID
    private var panStartX = 0f
    private var panStartY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isSingleFingerPanning = false
    private var isScalingGesture = false
    private var hasViewportGesture = false

    var onRegionFilledListener: ((maskColor: Int) -> Unit)? = null
    var onViewportInitialStateChangedListener: ((isAtInitialViewport: Boolean) -> Unit)? = null
        set(value) {
            field = value
            lastReportedInitialViewportState = null
            notifyViewportInitialStateIfChanged()
        }
    private var lastReportedInitialViewportState: Boolean? = null

    private var regions: List<RegionData> = emptyList()
    private val labelPointBuffer = FloatArray(2)
    private val labelCollisionBounds = ArrayList<RectF>()
    private val labelVisibilityByMaskColor = mutableMapOf<Int, Boolean>()
    private var completedMaskColors: Set<Int> = emptySet()

    private var hideAllLabels: Boolean = false
    private var isInteractionLocked: Boolean = false
    private var isViewportAnimationLocked: Boolean = false
    private var fillInAnimationEnabled: Boolean = true

    private var currentValidMaskColors: Map<Int, Int> = emptyMap()
    private var highlightTheme: HighlightTheme = HighlightThemes.defaultChecker(context)
    private var highlightEnabled: Boolean = true
    private var currentHighlightTargets: IntArray = IntArray(0)
    private var renderedHighlightTargets: IntArray = IntArray(0)
    private var scheduledHighlightTargets: IntArray = IntArray(0)
    private var highlightRenderJob: Job? = null
    private var highlightRenderGeneration = 0
    private var highlightFadeAnimator: ValueAnimator? = null
    private var highlightFadeAlpha = 255
    private val preparingFillColors = mutableSetOf<Int>()
    private val preparedFillAssets = mutableMapOf<Int, PreparedFillAsset>()
    private var fillAssetPrewarmJob: Job? = null
    private var fillAssetPrewarmGeneration = 0

    private data class TapEffect(
        val x: Float,
        val y: Float,
        val color: Int,
        val durationMs: Long,
        val reverse: Boolean = false,
        val repeatCount: Int = 0,
        val startedAtMs: Long = SystemClock.uptimeMillis(),
    ) {
        var progress: Float = 0f
    }

    private val activeEffects = mutableListOf<TapEffect>()
    private val activeFillers = mutableListOf<AnimatedFiller>()
    private var viewportAnimator: ValueAnimator? = null
    private var lastAnimationFrameUptimeMs = 0L

    init {
        setupScaleDetector()
    }

    //region SETUPSCALE
    private fun setupScaleDetector() {
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (maskWidth == 0 || maskHeight == 0) return false

                    isScalingGesture = true
                    isSingleFingerPanning = false
                    hasViewportGesture = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

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

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScalingGesture = false
                }
            })
    }

    suspend fun setBitmapsSuspend(
        logicLine: Bitmap,
        displayLine: Bitmap,
        displayLineSvg: SVG?,
        mask: Bitmap,
        detail: Bitmap?,
        fillCoverage: Bitmap?,
        regionsData: List<RegionData>
    ) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val w = mask.width
            val h = mask.height

            val coloredBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val highlightBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val detailRevealBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val displayLineLumaPx = if (BuildConfig.USE_EDGE_UNDERPAINT) {
                createDisplayLineLumaPixels(displayLineSvg, displayLine, w, h)
            } else {
                null
            }

            val maskPx = IntArray(w * h)
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)

            val coveragePx = if (fillCoverage != null && fillCoverage.width == w && fillCoverage.height == h) {
                IntArray(w * h).also {
                    fillCoverage.getPixels(it, 0, w, 0, 0, w, h)
                }
            } else {
                null
            }

            val detailPx = if (detail != null && detail.width == w && detail.height == h) {
                IntArray(w * h).also {
                    detail.getPixels(it, 0, w, 0, 0, w, h)
                }
            } else {
                null
            }

            val pixelRegions = MaskColorPixelIndex.build(
                maskPixels = maskPx,
                fillCoveragePixels = coveragePx,
                width = w,
                height = h,
                targetMaskColors = regionsData.mapTo(HashSet(regionsData.size)) { it.maskColorInt },
            )

            // Giải phóng maskBitmap để tiết kiệm 4.6MB RAM
            mask.recycle()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                displayLineBitmap = displayLine
                this@PaintCanvasView.displayLineSvg = displayLineSvg
                useVectorDisplayLine = displayLineSvg != null
                maskWidth = w
                maskHeight = h
                regions = regionsData
                labelVisibilityByMaskColor.clear()
                activeFillers.forEach { it.recycle() }
                activeFillers.clear()
                preparingFillColors.clear()
                clearPreparedFillAssets()
                currentValidMaskColors = emptyMap()
                cancelHighlightRendering()
                renderedHighlightTargets = IntArray(0)

                coloredBitmap = coloredBmp
                highlightBitmap = highlightBmp
                revealedDetailBitmap = detailRevealBmp

                maskPixelsArray = maskPx
                fillCoveragePixelsArray = coveragePx
                displayLineLumaPixelsArray = displayLineLumaPx
                coloredPixelsArray = IntArray(w * h)
                hlPixelsArray = IntArray(w * h)
                detailSourcePixelsArray = detailPx
                revealedDetailPixelsArray = if (detailPx != null) IntArray(w * h) else null
                maskColorPixelRegions = pixelRegions

                viewportAnimator?.cancel()
                scaleFactor = 1.0f
                translateX = 0f
                translateY = 0f

                ViewportTransformPolicy.fitCenter(
                    viewportWidth = width.toFloat(),
                    viewportHeight = height.toFloat(),
                    artworkWidth = w,
                    artworkHeight = h,
                )?.let { fitTransform ->
                    scaleFactor = fitTransform.scale
                    translateX = fitTransform.translationX
                    translateY = fitTransform.translationY
                    updateMatrix()
                }
                notifyViewportInitialStateIfChanged()
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val lw = maskWidth
        val lh = maskHeight
        val wasAtInitialViewport = ViewportTransformPolicy.isAtFitCenter(
            scale = scaleFactor,
            translationX = translateX,
            translationY = translateY,
            viewportWidth = oldw.toFloat(),
            viewportHeight = oldh.toFloat(),
            artworkWidth = lw,
            artworkHeight = lh,
        )
        if (lw > 0 && lh > 0 && w > 0 && h > 0 && wasAtInitialViewport) {
            ViewportTransformPolicy.fitCenter(
                viewportWidth = w.toFloat(),
                viewportHeight = h.toFloat(),
                artworkWidth = lw,
                artworkHeight = lh,
            )?.let { fitTransform ->
                scaleFactor = fitTransform.scale
                translateX = fitTransform.translationX
                translateY = fitTransform.translationY
                updateMatrix()
            }
        }
        notifyViewportInitialStateIfChanged()
    }

    suspend fun restoreProgressSuspend(completedMap: Map<Int, Int>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (completedMap.isEmpty()) return@withContext

            val maskPx = maskPixelsArray ?: return@withContext
            val colPx = coloredPixelsArray ?: return@withContext
            val detailSrcPx = detailSourcePixelsArray
            val detailOutPx = revealedDetailPixelsArray
            val w = maskWidth
            val h = maskHeight
            if (detailOutPx != null) {
                java.util.Arrays.fill(detailOutPx, 0)
            }
            val coveragePx = fillCoveragePixelsArray

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

            fun targetColorForMask(maskColor: Int): Int {
                if (maskColor == 0) return 0
                var idxM = maskColor.hashCode() and mask
                while (true) {
                    val k = keys[idxM]
                    if (k == maskColor) return vals[idxM]
                    if (k == 0) return 0
                    idxM = (idxM + 1) and mask
                }
            }

            // Quét đổ màu trực tiếp với 1.1 triệu pixel
            for (i in maskPx.indices) {
                val maskC = maskPx[i]

                val targetFromMask = targetColorForMask(maskC)
                val isMaskPixel = targetFromMask != 0
                val targetC = if (isMaskPixel) {
                    targetFromMask
                } else {
                    targetColorForMask(coveragePx?.getOrNull(i) ?: 0)
                }

                if (targetC != 0) {
                    colPx[i] = targetC
                    if (isMaskPixel && detailSrcPx != null && detailOutPx != null) {
                        detailOutPx[i] = detailSrcPx[i]
                    }
                }
            }
            if (BuildConfig.USE_EDGE_UNDERPAINT) {
                for ((maskColor, targetColor) in completedMap) {
                    EdgeUnderpaintEngine.applyForMaskColor(
                        maskPixels = maskPx,
                        coloredPixels = colPx,
                        lineLumaPixels = displayLineLumaPixelsArray,
                        width = w,
                        height = h,
                        maskColor = maskColor,
                        targetColor = targetColor,
                        fillCoveragePixels = coveragePx,
                        detailSourcePixels = detailSrcPx,
                        revealedDetailPixels = detailOutPx,
                    )
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
        if (fillInAnimationEnabled == enabled) return
        fillInAnimationEnabled = enabled
        if (enabled) {
            scheduleFillAssetPrewarm()
        } else {
            clearPreparedFillAssets()
        }
    }

    fun resetProgress() {
        completedMaskColors = emptySet()
        labelVisibilityByMaskColor.clear()
        activeFillers.forEach { it.recycle() }
        activeFillers.clear()
        preparingFillColors.clear()
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
        scheduleFillAssetPrewarm()
        invalidate()
    }

    fun setActiveColors(maskToTargetColors: Map<Int, Int>) {
        if (currentValidMaskColors == maskToTargetColors) return
        currentValidMaskColors = maskToTargetColors
        scheduleFillAssetPrewarm()
    }

    private fun scheduleFillAssetPrewarm() {
        fillAssetPrewarmGeneration++
        val generation = fillAssetPrewarmGeneration
        fillAssetPrewarmJob?.cancel()
        fillAssetPrewarmJob = null
        preparedFillAssets.values.forEach { it.recycle() }
        preparedFillAssets.clear()

        if (!fillInAnimationEnabled || currentValidMaskColors.isEmpty()) return
        val maskPx = maskPixelsArray ?: return
        val colPx = coloredPixelsArray ?: return
        val widthSnapshot = maskWidth
        val heightSnapshot = maskHeight
        val detailPx = detailSourcePixelsArray
        val coveragePx = fillCoveragePixelsArray
        val lineLumaPx = if (BuildConfig.USE_EDGE_UNDERPAINT) displayLineLumaPixelsArray else null
        val colorsToPrepare = FillAssetPrewarmPolicy.selectMaskColors(
            activeMaskColors = currentValidMaskColors.keys - completedMaskColors,
            regions = maskColorPixelRegions,
            bitmapBudgetBytes = FILL_ASSET_PREWARM_BUDGET_BYTES,
        )
        val targetColors = currentValidMaskColors.toMap()

        fillAssetPrewarmJob = scope.launch {
            for (maskColor in colorsToPrepare) {
                val region = maskColorPixelRegions[maskColor] ?: continue
                val targetColor = targetColors[maskColor] ?: continue
                val asset = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val built = FillAnimationAssetFactory.create(
                        region = region,
                        maskPixels = maskPx,
                        coloredPixels = colPx,
                        detailPixels = detailPx,
                        fillCoveragePixels = coveragePx,
                        lineLumaPixels = lineLumaPx,
                        imageWidth = widthSnapshot,
                        imageHeight = heightSnapshot,
                        maskColor = maskColor,
                        targetColor = targetColor,
                    )
                    if (!isActive) {
                        built.recycle()
                        null
                    } else {
                        built
                    }
                } ?: return@launch

                if (
                    generation != fillAssetPrewarmGeneration ||
                    maskPixelsArray !== maskPx ||
                    coloredPixelsArray !== colPx ||
                    completedMaskColors.contains(maskColor) ||
                    currentValidMaskColors[maskColor] != targetColor
                ) {
                    asset.recycle()
                    return@launch
                }
                preparedFillAssets.put(maskColor, asset)?.recycle()
            }
        }
    }

    private fun clearPreparedFillAssets() {
        fillAssetPrewarmGeneration++
        fillAssetPrewarmJob?.cancel()
        fillAssetPrewarmJob = null
        preparedFillAssets.values.forEach { it.recycle() }
        preparedFillAssets.clear()
    }

    fun setHighlightTheme(theme: HighlightTheme) {
        highlightTheme = theme
        rerenderHighlight(force = true)
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
        completed.forEach { maskColor ->
            preparedFillAssets.remove(maskColor)?.recycle()
        }
        invalidate()
    }

    private fun completeRegionForMaskColor(maskColor: Int, targetColor: Int) {
        val maskPx = maskPixelsArray ?: return
        val colArr = coloredPixelsArray ?: return
        val colBmp = coloredBitmap ?: return
        val detailSrcPx = detailSourcePixelsArray
        val detailOutPx = revealedDetailPixelsArray
        val detailBmp = revealedDetailBitmap

        val indexedRegion = maskColorPixelRegions[maskColor]
        if (indexedRegion != null) {
            DetailRevealEngine.completeRegionAtIndices(
                region = indexedRegion,
                maskPixels = maskPx,
                coloredPixels = colArr,
                detailSourcePixels = detailSrcPx,
                revealedDetailPixels = detailOutPx,
                maskColor = maskColor,
                targetColor = targetColor,
                fillCoveragePixels = fillCoveragePixelsArray,
            )
        } else {
            DetailRevealEngine.completeRegionForMaskColor(
                maskPixels = maskPx,
                coloredPixels = colArr,
                detailSourcePixels = detailSrcPx,
                revealedDetailPixels = detailOutPx,
                maskColor = maskColor,
                targetColor = targetColor,
                fillCoveragePixels = fillCoveragePixelsArray,
            )
        }
        if (BuildConfig.USE_EDGE_UNDERPAINT) {
            EdgeUnderpaintEngine.applyForMaskColor(
                maskPixels = maskPx,
                coloredPixels = colArr,
                lineLumaPixels = displayLineLumaPixelsArray,
                width = maskWidth,
                height = maskHeight,
                maskColor = maskColor,
                targetColor = targetColor,
                fillCoveragePixels = fillCoveragePixelsArray,
                detailSourcePixels = detailSrcPx,
                revealedDetailPixels = detailOutPx,
            )
        }
        if (indexedRegion != null) {
            val dirtyBounds = if (BuildConfig.USE_EDGE_UNDERPAINT) {
                EdgeUnderpaintEngine.dirtyBounds(indexedRegion, maskWidth, maskHeight)
            } else {
                com.pixlory.color.by.number.data.PixelBounds(
                    left = indexedRegion.minX,
                    top = indexedRegion.minY,
                    right = indexedRegion.maxX,
                    bottom = indexedRegion.maxY,
                )
            }
            colBmp.setPixels(
                colArr,
                dirtyBounds.top * maskWidth + dirtyBounds.left,
                maskWidth,
                dirtyBounds.left,
                dirtyBounds.top,
                dirtyBounds.width,
                dirtyBounds.height,
            )
            if (detailBmp != null && detailOutPx != null) {
                detailBmp.setPixels(
                    detailOutPx,
                    dirtyBounds.top * maskWidth + dirtyBounds.left,
                    maskWidth,
                    dirtyBounds.left,
                    dirtyBounds.top,
                    dirtyBounds.width,
                    dirtyBounds.height,
                )
            }
        } else {
            colBmp.setPixels(colArr, 0, colBmp.width, 0, 0, colBmp.width, colBmp.height)
            if (detailBmp != null && detailOutPx != null) {
                detailBmp.setPixels(detailOutPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            }
        }
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
        }.distinct().sorted().toIntArray()
        currentHighlightTargets = activeTargets

        if (activeTargets.isEmpty()) {
            clearHighlightImmediately()
            return
        }

        rerenderHighlight()
    }

    private fun clearHighlightImmediately() {
        cancelHighlightRendering()
        currentHighlightTargets = IntArray(0)
        renderedHighlightTargets = IntArray(0)
        scheduledHighlightTargets = IntArray(0)
        highlightFadeAnimator?.cancel()
        highlightFadeAlpha = 255
        applyHighlightOpacity()
        val hl = highlightBitmap ?: return
        val hlPixels = hlPixelsArray ?: return
        java.util.Arrays.fill(hlPixels, 0)
        hl.setPixels(hlPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        invalidate()
    }

    private fun rerenderHighlight(force: Boolean = false) {
        if (!highlightEnabled || currentHighlightTargets.isEmpty()) {
            clearHighlightImmediately()
            return
        }

        val hl = highlightBitmap ?: return
        val width = maskWidth
        val height = maskHeight
        if (width == 0 || height == 0) return
        val maskPixels = maskPixelsArray ?: return
        val targets = currentHighlightTargets.copyOf()
        if (!force && renderedHighlightTargets.contentEquals(targets)) return
        if (!force && highlightRenderJob?.isActive == true && scheduledHighlightTargets.contentEquals(targets)) {
            return
        }

        cancelHighlightRendering()
        scheduledHighlightTargets = targets
        val theme = highlightTheme
        val generation = highlightRenderGeneration
        highlightRenderJob = scope.launch {
            val renderedPixels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                IntArray(maskPixels.size).also { output ->
                    HighlightRenderer.render(
                        maskPixels = maskPixels,
                        outputPixels = output,
                        width = width,
                        activeTargets = targets,
                        theme = theme,
                        alphaFraction = 1f,
                    )
                }
            }

            if (
                generation != highlightRenderGeneration ||
                maskPixelsArray !== maskPixels ||
                highlightBitmap !== hl ||
                !currentHighlightTargets.contentEquals(targets)
            ) {
                return@launch
            }

            hlPixelsArray = renderedPixels
            hl.setPixels(renderedPixels, 0, width, 0, 0, width, height)
            renderedHighlightTargets = targets
            scheduledHighlightTargets = IntArray(0)
            highlightRenderJob = null
            startHighlightFade()
        }
    }

    private fun cancelHighlightRendering() {
        highlightRenderJob?.cancel()
        highlightRenderJob = null
        highlightRenderGeneration++
        scheduledHighlightTargets = IntArray(0)
    }

    private fun startHighlightFade() {
        highlightFadeAnimator?.cancel()
        val animator = ValueAnimator.ofInt(0, 255).apply {
            duration = highlightTheme.fadeInDurationMs
            interpolator = highlightTheme.interpolator
            addUpdateListener {
                highlightFadeAlpha = it.animatedValue as Int
                applyHighlightOpacity()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (highlightFadeAnimator === animation) highlightFadeAnimator = null
                    highlightFadeAlpha = 255
                    applyHighlightOpacity()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (highlightFadeAnimator === animation) highlightFadeAnimator = null
                }
            })
        }
        highlightFadeAnimator = animator
        animator.start()
    }

    private fun updateMatrix() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f || maskWidth == 0 || maskHeight == 0) return

        val scaledWidth = maskWidth * scaleFactor
        val scaledHeight = maskHeight * scaleFactor

        translateX = clampFreePanTranslation(translateX, viewWidth, scaledWidth)
        translateY = clampFreePanTranslation(translateY, viewHeight, scaledHeight)

        drawMatrix.reset()
        drawMatrix.postScale(scaleFactor, scaleFactor)
        drawMatrix.postTranslate(translateX, translateY)
        drawMatrix.invert(inverseMatrix)
        applyHighlightOpacity()
        notifyViewportInitialStateIfChanged()
        invalidate()
    }

    private fun notifyViewportInitialStateIfChanged() {
        val isAtInitialViewport = ViewportTransformPolicy.isAtFitCenter(
            scale = scaleFactor,
            translationX = translateX,
            translationY = translateY,
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            artworkWidth = maskWidth,
            artworkHeight = maskHeight,
        )
        if (lastReportedInitialViewportState == isAtInitialViewport) return

        lastReportedInitialViewportState = isAtInitialViewport
        onViewportInitialStateChangedListener?.invoke(isAtInitialViewport)
    }

    /**
     * Matches the reference app's free-pan feel: users may move the artwork beyond every edge,
     * including while it is smaller than the viewport. A narrow strip must remain visible so a
     * picture cannot be lost completely off-screen.
     */
    private fun clampFreePanTranslation(
        translation: Float,
        viewportSize: Float,
        scaledArtworkSize: Float,
    ): Float {
        val minimumVisibleArtwork = minOf(scaledArtworkSize, freePanVisibleEdgePx)
        val minTranslation = minimumVisibleArtwork - scaledArtworkSize
        val maxTranslation = viewportSize - minimumVisibleArtwork
        return translation.coerceIn(minTranslation, maxTranslation)
    }

    /**
     * Checker cần bớt đậm khi vùng đã được zoom lớn: lúc này người dùng cần đọc nét và số,
     * không cần một lớp phủ mạnh như ở góc nhìn tổng quan. Target được highlight không đổi.
     */
    private fun applyHighlightOpacity() {
        val fitScale = Math.min(
            width.toFloat() / (maskWidth.takeIf { it > 0 } ?: 1),
            height.toFloat() / (maskHeight.takeIf { it > 0 } ?: 1)
        ).coerceAtLeast(0.0001f)
        val zoomRatio = scaleFactor / fitScale
        val zoomReduction = ((zoomRatio - 1f) / 5f).coerceIn(0f, 1f) * 0.28f
        highlightPaint.alpha = (highlightFadeAlpha * (1f - zoomReduction)).toInt().coerceIn(0, 255)
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

    suspend fun animateToFitScreen() {
        val target = ViewportTransformPolicy.fitCenter(
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            artworkWidth = maskWidth,
            artworkHeight = maskHeight,
        ) ?: return

        if (
            ViewportTransformPolicy.isAtFitCenter(
                scale = scaleFactor,
                translationX = translateX,
                translationY = translateY,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                artworkWidth = maskWidth,
                artworkHeight = maskHeight,
            )
        ) {
            scaleFactor = target.scale
            translateX = target.translationX
            translateY = target.translationY
            updateMatrix()
            return
        }

        suspendCancellableCoroutine { continuation ->
            viewportAnimator?.cancel()
            val startScale = scaleFactor
            val startTranslateX = translateX
            val startTranslateY = translateY
            isViewportAnimationLocked = true

            val animator = ValueAnimator.ofFloat(0f, 1f)
            viewportAnimator = animator
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finish()
                }

                private fun finish() {
                    if (viewportAnimator === animator) {
                        viewportAnimator = null
                        isViewportAnimationLocked = false
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            continuation.invokeOnCancellation {
                animator.removeAllListeners()
                animator.cancel()
                if (viewportAnimator === animator) {
                    viewportAnimator = null
                    isViewportAnimationLocked = false
                }
            }

            animator.duration = COMPLETION_FIT_ANIMATION_DURATION_MS
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                scaleFactor = startScale + (target.scale - startScale) * p
                translateX = startTranslateX + (target.translationX - startTranslateX) * p
                translateY = startTranslateY + (target.translationY - startTranslateY) * p
                updateMatrix()
            }
            animator.addListener(listener)
            animator.start()
        }
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

        viewportAnimator?.cancel()
        isViewportAnimationLocked = true
        val animator = ValueAnimator.ofFloat(0f, 1f)
        viewportAnimator = animator
        animator.duration = 400
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val p = anim.animatedValue as Float
            scaleFactor = startScale + (targetScale - startScale) * p
            translateX = startTranslateX + (targetTranslateX - startTranslateX) * p
            translateY = startTranslateY + (targetTranslateY - startTranslateY) * p
            updateMatrix()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finishViewportAnimation()
            }

            override fun onAnimationCancel(animation: Animator) {
                finishViewportAnimation()
            }

            private fun finishViewportAnimation() {
                if (viewportAnimator === animator) {
                    viewportAnimator = null
                    isViewportAnimationLocked = false
                }
            }
        })
        animator.start()

        // Thêm hiệu ứng chớp nhá/ripple tại vùng hint để thu hút chú ý
        val targetColor =
            if (currentValidMaskColors.isNotEmpty()) currentValidMaskColors.values.first() else Color.RED
        activeEffects.add(
            TapEffect(
                x = cx,
                y = cy,
                color = targetColor,
                durationMs = HINT_TAP_EFFECT_DURATION_MS,
                reverse = true,
                repeatCount = 1,
            )
        )
        startAnimationLoop()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isViewportAnimationLocked) return true

        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginSingleFingerTouch(event)

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasViewportGesture = true
                isSingleFingerPanning = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !isScalingGesture) {
                    panWithSingleFinger(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> continueAfterPointerUp(event)

            MotionEvent.ACTION_UP -> {
                if (!hasViewportGesture) {
                    handleTap(event.x, event.y)
                }
                resetTouchTracking()
            }

            MotionEvent.ACTION_CANCEL -> resetTouchTracking()
        }
        return true
    }

    private fun beginSingleFingerTouch(event: MotionEvent) {
        activePanPointerId = event.getPointerId(0)
        panStartX = event.x
        panStartY = event.y
        lastPanX = event.x
        lastPanY = event.y
        isSingleFingerPanning = false
        isScalingGesture = false
        hasViewportGesture = false
    }

    private fun panWithSingleFinger(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePanPointerId)
        if (pointerIndex == -1) return

        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (!isSingleFingerPanning) {
            val totalDeltaX = x - panStartX
            val totalDeltaY = y - panStartY
            if (totalDeltaX * totalDeltaX + totalDeltaY * totalDeltaY >= touchSlop * touchSlop) {
                isSingleFingerPanning = true
                hasViewportGesture = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        if (isSingleFingerPanning) {
            translateX += x - lastPanX
            translateY += y - lastPanY
            updateMatrix()
        }
        lastPanX = x
        lastPanY = y
    }

    private fun continueAfterPointerUp(event: MotionEvent) {
        hasViewportGesture = true
        val liftedPointerIndex = event.actionIndex
        val remainingPointerIndex = (0 until event.pointerCount).firstOrNull {
            it != liftedPointerIndex
        }
        if (remainingPointerIndex == null) {
            activePanPointerId = INVALID_POINTER_ID
            return
        }

        activePanPointerId = event.getPointerId(remainingPointerIndex)
        lastPanX = event.getX(remainingPointerIndex)
        lastPanY = event.getY(remainingPointerIndex)
        panStartX = lastPanX
        panStartY = lastPanY
        isSingleFingerPanning = event.pointerCount - 1 == 1
    }

    private fun resetTouchTracking() {
        activePanPointerId = INVALID_POINTER_ID
        isSingleFingerPanning = false
        isScalingGesture = false
        hasViewportGesture = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope =
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        }
        scheduleFillAssetPrewarm()
    }

    override fun onDetachedFromWindow() {
        viewportAnimator?.cancel()
        viewportAnimator = null
        highlightFadeAnimator?.cancel()
        highlightFadeAnimator = null
        cancelHighlightRendering()
        removeCallbacks(animationRunnable)
        activeFillers.forEach { it.recycle() }
        activeFillers.clear()
        activeEffects.clear()
        preparingFillColors.clear()
        clearPreparedFillAssets()
        isAnimatingLoop = false
        lastAnimationFrameUptimeMs = 0L
        isViewportAnimationLocked = false
        scope.cancel()
        super.onDetachedFromWindow()
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
        val colPx = coloredPixelsArray ?: return

        val targetColor = currentValidMaskColors[clickedColor] ?: return

        // Tránh trùng lặp fill
        if (activeFillers.any { it.maskColor == clickedColor }) return
        if (preparingFillColors.contains(clickedColor)) return

        if (!fillInAnimationEnabled) {
            completeInstantFill(clickedColor, targetColor)
            return
        }

        val indexedRegion = maskColorPixelRegions[clickedColor] ?: return
        val widthSnapshot = maskWidth
        val heightSnapshot = maskHeight
        val detailPx = detailSourcePixelsArray
        val coveragePx = fillCoveragePixelsArray
        val lineLumaPx = if (BuildConfig.USE_EDGE_UNDERPAINT) displayLineLumaPixelsArray else null
        val animationScale = scaleFactor
        val maxVisibleRevealRadius = Math.hypot(width.toDouble(), height.toDouble()).toFloat() /
            animationScale.coerceAtLeast(0.0001f)

        activeEffects.add(
            TapEffect(
                x = startX.toFloat(),
                y = startY.toFloat(),
                color = targetColor,
                durationMs = FILL_TAP_EFFECT_DURATION_MS,
            )
        )
        startAnimationLoop()

        val cachedAsset = preparedFillAssets.remove(clickedColor)
        if (cachedAsset != null && cachedAsset.targetColor == targetColor) {
            activeFillers.add(
                AnimatedFiller(
                    preparedAsset = cachedAsset,
                    startX = startX,
                    startY = startY,
                    animationScale = animationScale,
                    maxVisibleRevealRadius = maxVisibleRevealRadius,
                    onFinished = { onRegionFilledListener?.invoke(it) },
                )
            )
            clearHighlightForMaskColor(clickedColor)
            startAnimationLoop()
            return
        }
        cachedAsset?.recycle()

        preparingFillColors.add(clickedColor)
        scope.launch {
            var preparedAsset: PreparedFillAsset? = null
            try {
                preparedAsset = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val built = FillAnimationAssetFactory.create(
                        region = indexedRegion,
                        maskPixels = maskPx,
                        coloredPixels = colPx,
                        detailPixels = detailPx,
                        fillCoveragePixels = coveragePx,
                        lineLumaPixels = lineLumaPx,
                        imageWidth = widthSnapshot,
                        imageHeight = heightSnapshot,
                        maskColor = clickedColor,
                        targetColor = targetColor,
                    )
                    if (!isActive) {
                        built.recycle()
                        null
                    } else {
                        built
                    }
                } ?: return@launch

                if (
                    maskPixelsArray !== maskPx ||
                    coloredPixelsArray !== colPx ||
                    maskWidth != widthSnapshot ||
                    maskHeight != heightSnapshot ||
                    completedMaskColors.contains(clickedColor) ||
                    currentValidMaskColors[clickedColor] != targetColor ||
                    activeFillers.any { it.maskColor == clickedColor }
                ) {
                    return@launch
                }

                val filler = AnimatedFiller(
                    preparedAsset = preparedAsset,
                    startX = startX,
                    startY = startY,
                    animationScale = animationScale,
                    maxVisibleRevealRadius = maxVisibleRevealRadius,
                    onFinished = { onRegionFilledListener?.invoke(it) },
                )
                preparedAsset = null
                activeFillers.add(filler)

                // Cập nhật ngay lập tức: Xóa highlight của mảng màu này để animation hiện rõ
                clearHighlightForMaskColor(clickedColor)
                startAnimationLoop()
            } finally {
                preparedAsset?.recycle()
                preparingFillColors.remove(clickedColor)
            }
        }
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
        lastAnimationFrameUptimeMs = SystemClock.uptimeMillis()
        postOnAnimation(animationRunnable)
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            if (activeFillers.isEmpty() && activeEffects.isEmpty()) {
                isAnimatingLoop = false
                lastAnimationFrameUptimeMs = 0L
                return
            }
            val now = SystemClock.uptimeMillis()
            val deltaMs = (now - lastAnimationFrameUptimeMs).toFloat()
                .takeIf { it > 0f } ?: DEFAULT_FRAME_DURATION_MS
            lastAnimationFrameUptimeMs = now

            val iterator = activeFillers.iterator()
            while (iterator.hasNext()) {
                val filler = iterator.next()
                val isRunning = filler.tick(deltaMs)
                if (!isRunning) {
                    iterator.remove()
                    // Khi filler kết thúc: tick() đã ghi đúng màu cuối của cụm liên thông cục bộ.
                    // completeRegionForMaskColor() chỉ quét nốt toàn ảnh để phủ các cụm tách rời
                    // cùng mã màu và đồng bộ lớp detail/coverage với buffer chính.
                    completeRegionForMaskColor(filler.maskColor, filler.targetColor)
                    completedMaskColors = completedMaskColors + filler.maskColor
                    preparedFillAssets.remove(filler.maskColor)?.recycle()
                    filler.dispatchFinished()
                    filler.recycle()
                }
            }

            val effectIterator = activeEffects.iterator()
            while (effectIterator.hasNext()) {
                val effect = effectIterator.next()
                val elapsedMs = (now - effect.startedAtMs).coerceAtLeast(0L)
                val segment = (elapsedMs / effect.durationMs).toInt()
                if (segment > effect.repeatCount) {
                    effectIterator.remove()
                    continue
                }

                val segmentProgress = (elapsedMs % effect.durationMs).toFloat() / effect.durationMs
                effect.progress = if (effect.reverse && segment % 2 == 1) {
                    1f - segmentProgress
                } else {
                    segmentProgress
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
        cancelHighlightRendering()
        val hl = highlightBitmap ?: return
        val hlPx = hlPixelsArray ?: return
        val region = maskColorPixelRegions[maskColor]
        var changed = false

        val maskPx = maskPixelsArray ?: return
        fun clearAt(index: Int) {
            if (index in hlPx.indices && hlPx[index] != 0) {
                hlPx[index] = 0
                changed = true
            }
        }
        if (region != null) {
            for (index in region.indices) clearAt(index)
        } else {
            for (index in maskPx.indices) {
                if (maskPx[index] == maskColor) clearAt(index)
            }
        }

        if (changed) {
            if (region != null) {
                hl.setPixels(
                    hlPx,
                    region.minY * maskWidth + region.minX,
                    maskWidth,
                    region.minX,
                    region.minY,
                    region.width,
                    region.height,
                )
            } else {
                hl.setPixels(hlPx, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            }
        }
        currentHighlightTargets =
            currentHighlightTargets.filter { it != maskColor }.toIntArray()
        renderedHighlightTargets =
            renderedHighlightTargets.filter { it != maskColor }.toIntArray()
        if (currentHighlightTargets.isEmpty() && renderedHighlightTargets.isNotEmpty()) {
            clearHighlightImmediately()
        } else if (!currentHighlightTargets.contentEquals(renderedHighlightTargets)) {
            rerenderHighlight()
        }
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
            canvas.drawBitmap(filler.localBitmap, filler.left.toFloat(), filler.top.toFloat(), normalPaint)
            canvas.restore()
        }
        revealedDetailBitmap?.let { canvas.drawBitmap(it, drawMatrix, normalPaint) }
        canvas.drawBitmap(hl, drawMatrix, highlightPaint)
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
            for (i in 0 until 4) {
                val angle = i * Math.PI / 2.0
                val distance = 5f / scaleFactor + p * 50f / scaleFactor
                val px = effect.x + Math.cos(angle).toFloat() * distance
                val py = effect.y + Math.sin(angle).toFloat() * distance
                canvas.drawCircle(px, py, particleRadius, particlePaint)
            }
        }
        canvas.restore()

        drawAdaptiveLabels(canvas)

        canvas.save()
        canvas.concat(drawMatrix)
        drawDisplayLineInMaskBounds(canvas, line)
        canvas.restore()
    }

    /** Draw selected-colour labels first so they survive collision culling. */
    private fun drawAdaptiveLabels(canvas: Canvas) {
        if (hideAllLabels) return
        labelCollisionBounds.clear()
        drawAdaptiveLabels(canvas, selectedColourFirst = true)
        drawAdaptiveLabels(canvas, selectedColourFirst = false)
    }

    private fun drawAdaptiveLabels(canvas: Canvas, selectedColourFirst: Boolean) {
        for (region in regions) {
            if (completedMaskColors.contains(region.maskColorInt) || region.hideNumber) continue
            val isSelectedColour = currentValidMaskColors.containsKey(region.maskColorInt)
            if (isSelectedColour != selectedColourFirst) continue

            val screenRadius = region.radius * scaleFactor
            val wasVisible = labelVisibilityByMaskColor[region.maskColorInt] ?: false
            val isVisible = LabelVisibilityPolicy.shouldShow(wasVisible, screenRadius)
            labelVisibilityByMaskColor[region.maskColorInt] = isVisible
            if (!isVisible) continue

            labelPointBuffer[0] = region.labelX
            labelPointBuffer[1] = region.labelY
            drawMatrix.mapPoints(labelPointBuffer)
            if (labelPointBuffer[0] !in 0f..width.toFloat() || labelPointBuffer[1] !in 0f..height.toFloat()) {
                continue
            }

            textPaint.textSize = Math.min(LABEL_TEXT_SIZE_PX, screenRadius * LABEL_SAFE_RADIUS_FACTOR)
                .coerceAtLeast(8f)
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f
            val baseline = labelPointBuffer[1] - textOffset
            val textWidth = textPaint.measureText(region.number.toString())
            val candidate = RectF(
                labelPointBuffer[0] - textWidth / 2f - 2f,
                baseline + textPaint.ascent() - 2f,
                labelPointBuffer[0] + textWidth / 2f + 2f,
                baseline + textPaint.descent() + 2f,
            )
            if (labelCollisionBounds.any { RectF.intersects(it, candidate) }) continue
            labelCollisionBounds.add(candidate)
            canvas.drawText(
                region.number.toString(),
                labelPointBuffer[0],
                baseline,
                textPaint
            )
        }
    }

    private fun drawBitmapInMaskBounds(canvas: Canvas, bitmap: Bitmap, paint: Paint?) {
        bitmapBounds.set(0f, 0f, maskWidth.toFloat(), maskHeight.toFloat())
        canvas.drawBitmap(bitmap, null, bitmapBounds, paint)
    }

    private fun createDisplayLineLumaPixels(
        svg: SVG?,
        fallbackBitmap: Bitmap,
        width: Int,
        height: Int
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        val source = if (svg != null) {
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.TRANSPARENT)
                    svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()))
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        } ?: fallbackBitmap.takeIf { it.width == width && it.height == height } ?: return null

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        if (source !== fallbackBitmap) {
            source.recycle()
        }

        return IntArray(pixels.size) { i ->
            val pixel = pixels[i]
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) {
                255
            } else {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val blendedR = (r * alpha + 255 * (255 - alpha)) / 255
                val blendedG = (g * alpha + 255 * (255 - alpha)) / 255
                val blendedB = (b * alpha + 255 * (255 - alpha)) / 255
                (blendedR * 299 + blendedG * 587 + blendedB * 114) / 1000
            }
        }
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
