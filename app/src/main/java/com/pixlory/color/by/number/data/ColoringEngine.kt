package com.pixlory.color.by.number.data

import android.graphics.Bitmap

object MaskRegionHitTester {
    fun maskColorAt(
        maskPixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Int? {
        if (width <= 0 || height <= 0) return null
        if (x !in 0 until width || y !in 0 until height) return null
        val index = y * width + x
        if (index !in maskPixels.indices) return null
        return maskPixels[index]
    }

    fun isTouchableMaskColor(
        maskColor: Int,
        activeMaskColors: Set<Int>,
        completedMaskColors: Set<Int>
    ): Boolean {
        return activeMaskColors.contains(maskColor) && !completedMaskColors.contains(maskColor)
    }
}

internal object FillColorComposer {
    fun colorWithOptionalDetail(
        isMaskPixel: Boolean,
        targetColor: Int,
        detailColor: Int?
    ): Int {
        if (!isMaskPixel || detailColor == null) return targetColor

        val alpha = (detailColor ushr 24) and 0xFF
        if (alpha == 0) return targetColor

        val inverse = 255 - alpha
        val r = (((detailColor shr 16) and 0xFF) * alpha + ((targetColor shr 16) and 0xFF) * inverse) / 255
        val g = (((detailColor shr 8) and 0xFF) * alpha + ((targetColor shr 8) and 0xFF) * inverse) / 255
        val b = ((detailColor and 0xFF) * alpha + (targetColor and 0xFF) * inverse) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

object EdgeUnderpaintEngine {
    fun applyForMaskColor(
        maskPixels: IntArray,
        coloredPixels: IntArray,
        lineLumaPixels: IntArray?,
        width: Int,
        height: Int,
        maskColor: Int,
        targetColor: Int,
        fillCoveragePixels: IntArray? = null,
        detailSourcePixels: IntArray? = null,
        revealedDetailPixels: IntArray? = null,
        radius: Int = 1,
        lineProximityRadius: Int = 1,
        edgeDetailSuppressionRadius: Int = 2,
        inkThreshold: Int = 245,
        linePixelThreshold: Int = 252
    ) {
        if (lineLumaPixels == null || width <= 0 || height <= 0) return
        if (maskPixels.size != coloredPixels.size || lineLumaPixels.size != maskPixels.size) return

        val additions = GrowingIntArray(256)
        for (idx in maskPixels.indices) {
            if (!isRegionPixel(idx, maskPixels, fillCoveragePixels, maskColor)) continue
            val x = idx % width
            val y = idx / width
            for (dy in -radius..radius) {
                val ny = y + dy
                if (ny !in 0 until height) continue
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx !in 0 until width) continue
                    val nIdx = ny * width + nx
                    if (coloredPixels[nIdx] != 0) continue
                    if (isRegionPixel(nIdx, maskPixels, fillCoveragePixels, maskColor)) continue
                    if (lineLumaPixels[nIdx] >= linePixelThreshold) continue
                    if (!isNearInk(nIdx, lineLumaPixels, width, height, lineProximityRadius, inkThreshold)) continue
                    additions.add(nIdx)
                }
            }
        }

        suppressBrightDetailNearInk(
            maskPixels = maskPixels,
            fillCoveragePixels = fillCoveragePixels,
            detailSourcePixels = detailSourcePixels,
            revealedDetailPixels = revealedDetailPixels,
            lineLumaPixels = lineLumaPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            radius = edgeDetailSuppressionRadius,
            inkThreshold = inkThreshold
        )

        for (i in 0 until additions.size) {
            val idx = additions[i]
            coloredPixels[idx] = targetColor
            if (detailSourcePixels != null &&
                revealedDetailPixels != null &&
                idx in detailSourcePixels.indices &&
                idx in revealedDetailPixels.indices
            ) {
                val detailColor = detailSourcePixels[idx]
                revealedDetailPixels[idx] = if (isBrightDetail(detailColor)) 0 else detailColor
            }
        }
    }

    private fun isRegionPixel(
        idx: Int,
        maskPixels: IntArray,
        fillCoveragePixels: IntArray?,
        maskColor: Int
    ): Boolean =
        maskPixels[idx] == maskColor || fillCoveragePixels?.getOrNull(idx) == maskColor

    private fun isNearInk(
        idx: Int,
        lineLumaPixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        threshold: Int
    ): Boolean {
        val x = idx % width
        val y = idx / width
        for (dy in -radius..radius) {
            val ny = y + dy
            if (ny !in 0 until height) continue
            for (dx in -radius..radius) {
                val nx = x + dx
                if (nx !in 0 until width) continue
                if (lineLumaPixels[ny * width + nx] < threshold) return true
            }
        }
        return false
    }

    private fun suppressBrightDetailNearInk(
        maskPixels: IntArray,
        fillCoveragePixels: IntArray?,
        detailSourcePixels: IntArray?,
        revealedDetailPixels: IntArray?,
        lineLumaPixels: IntArray,
        width: Int,
        height: Int,
        maskColor: Int,
        radius: Int,
        inkThreshold: Int
    ) {
        if (detailSourcePixels == null || revealedDetailPixels == null) return
        if (detailSourcePixels.size != maskPixels.size || revealedDetailPixels.size != maskPixels.size) return

        for (idx in maskPixels.indices) {
            if (!isRegionPixel(idx, maskPixels, fillCoveragePixels, maskColor)) continue
            if (!isNearInk(idx, lineLumaPixels, width, height, radius, inkThreshold)) continue
            if (isBrightDetail(detailSourcePixels[idx])) {
                revealedDetailPixels[idx] = 0
            }
        }
    }

    private fun isBrightDetail(pixel: Int): Boolean {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha == 0) return true
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = (r * 299 + g * 587 + b * 114) / 1000
        return alpha < 32 || luma > 220
    }
}

internal data class FillRegionPixels(
    val indices: IntArray,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
)

internal object FillRegionCollector {
    fun collect(
        maskPixels: IntArray,
        width: Int,
        height: Int,
        maskColor: Int,
        startX: Int,
        startY: Int,
        expectedRegionArea: Int
    ): FillRegionPixels {
        val startIdx = startY * width + startX
        if (startIdx !in maskPixels.indices || maskPixels[startIdx] != maskColor) {
            return FillRegionPixels(IntArray(0), startX, startX, startY, startY)
        }

        val initialCapacity = expectedRegionArea
            .coerceAtLeast(256)
            .coerceAtMost(maskPixels.size)
        val indices = GrowingIntArray(initialCapacity)
        val queue = GrowingIntArray(initialCapacity)
        val visited = BooleanArray(maskPixels.size)

        var qHead = 0
        visited[startIdx] = true
        indices.add(startIdx)
        queue.add(startIdx)

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        // Nối 8 hướng (kể cả 4 đường chéo): hình dạng mảnh/cong (mắt, chi tiết nhỏ) có thể có
        // các pixel cùng mã màu chỉ dính nhau qua đường chéo. Vùng animation chỉ gồm pixel mask
        // thật để tránh tô lan sang line/nền khi line hiển thị không trùng line logic.
        val dx = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        val dy = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)

        while (qHead < queue.size) {
            val idx = queue[qHead++]
            val x = idx % width
            val y = idx / width

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            for (i in dx.indices) {
                val nx = x + dx[i]
                val ny = y + dy[i]
                if (nx !in 0 until width || ny !in 0 until height) continue

                val nIdx = ny * width + nx
                if (visited[nIdx]) continue

                if (maskPixels[nIdx] != maskColor) continue

                visited[nIdx] = true
                indices.add(nIdx)
                queue.add(nIdx)
            }
        }

        return FillRegionPixels(
            indices = indices.toIntArray(),
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY
        )
    }
}

internal object FillCoverageCollector {
    fun includeConnectedCoverage(
        region: FillRegionPixels,
        maskPixels: IntArray,
        fillCoveragePixels: IntArray?,
        width: Int,
        height: Int,
        maskColor: Int
    ): FillRegionPixels {
        if (region.indices.isEmpty() || fillCoveragePixels == null) return region

        val visited = BooleanArray(maskPixels.size)
        val indices = GrowingIntArray(region.indices.size + 256)
        val queue = GrowingIntArray(region.indices.size + 256)

        var minX = region.minX
        var maxX = region.maxX
        var minY = region.minY
        var maxY = region.maxY

        for (idx in region.indices) {
            if (idx !in maskPixels.indices || visited[idx]) continue
            visited[idx] = true
            indices.add(idx)
            queue.add(idx)
        }

        val dx = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        val dy = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
        var qHead = 0

        while (qHead < queue.size) {
            val idx = queue[qHead++]
            val x = idx % width
            val y = idx / width

            for (i in dx.indices) {
                val nx = x + dx[i]
                val ny = y + dy[i]
                if (nx !in 0 until width || ny !in 0 until height) continue

                val nIdx = ny * width + nx
                if (visited[nIdx]) continue
                if (fillCoveragePixels.getOrNull(nIdx) != maskColor) continue
                if (maskPixels[nIdx] == maskColor) continue

                visited[nIdx] = true
                indices.add(nIdx)
                queue.add(nIdx)

                if (nx < minX) minX = nx
                if (nx > maxX) maxX = nx
                if (ny < minY) minY = ny
                if (ny > maxY) maxY = ny
            }
        }

        return FillRegionPixels(
            indices = indices.toIntArray(),
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY
        )
    }
}

internal data class AnimationFillFrame(
    val pixels: IntArray,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

internal object AnimationFillFrameComposer {
    fun compose(
        region: FillRegionPixels,
        maskPixels: IntArray,
        coloredPixels: IntArray,
        detailPixels: IntArray?,
        fillCoveragePixels: IntArray?,
        lineLumaPixels: IntArray?,
        imageWidth: Int,
        imageHeight: Int,
        maskColor: Int,
        targetColor: Int,
        underpaintRadius: Int = 1,
        edgeDetailSuppressionRadius: Int = 2,
        inkThreshold: Int = 245,
        linePixelThreshold: Int = 252
    ): AnimationFillFrame {
        val pad = if (lineLumaPixels != null) underpaintRadius.coerceAtLeast(0) else 0
        val left = (region.minX - pad).coerceAtLeast(0)
        val top = (region.minY - pad).coerceAtLeast(0)
        val right = (region.maxX + pad).coerceAtMost(imageWidth - 1)
        val bottom = (region.maxY + pad).coerceAtMost(imageHeight - 1)
        val frameWidth = right - left + 1
        val frameHeight = bottom - top + 1
        val localPx = IntArray(frameWidth * frameHeight)

        for (idx in region.indices) {
            val x = idx % imageWidth
            val y = idx / imageWidth
            localPx[(y - top) * frameWidth + (x - left)] = colorForAnimation(
                index = idx,
                maskPixels = maskPixels,
                detailPixels = detailPixels,
                lineLumaPixels = lineLumaPixels,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                maskColor = maskColor,
                targetColor = targetColor,
                edgeDetailSuppressionRadius = edgeDetailSuppressionRadius,
                inkThreshold = inkThreshold
            )
        }

        if (lineLumaPixels != null) {
            applyLineUnderpaint(
                region = region,
                maskPixels = maskPixels,
                coloredPixels = coloredPixels,
                fillCoveragePixels = fillCoveragePixels,
                lineLumaPixels = lineLumaPixels,
                localPixels = localPx,
                localLeft = left,
                localTop = top,
                localWidth = frameWidth,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                maskColor = maskColor,
                targetColor = targetColor,
                radius = underpaintRadius,
                linePixelThreshold = linePixelThreshold
            )
        }

        return AnimationFillFrame(
            pixels = localPx,
            left = left,
            top = top,
            width = frameWidth,
            height = frameHeight
        )
    }

    private fun colorForAnimation(
        index: Int,
        maskPixels: IntArray,
        detailPixels: IntArray?,
        lineLumaPixels: IntArray?,
        imageWidth: Int,
        imageHeight: Int,
        maskColor: Int,
        targetColor: Int,
        edgeDetailSuppressionRadius: Int,
        inkThreshold: Int
    ): Int {
        val detailColor = detailPixels?.getOrNull(index)
        val shouldSuppressDetail = detailColor != null &&
            lineLumaPixels != null &&
            isBrightDetail(detailColor) &&
            isNearInk(index, lineLumaPixels, imageWidth, imageHeight, edgeDetailSuppressionRadius, inkThreshold)
        return FillColorComposer.colorWithOptionalDetail(
            isMaskPixel = maskPixels[index] == maskColor && !shouldSuppressDetail,
            targetColor = targetColor,
            detailColor = detailColor,
        )
    }

    private fun applyLineUnderpaint(
        region: FillRegionPixels,
        maskPixels: IntArray,
        coloredPixels: IntArray,
        fillCoveragePixels: IntArray?,
        lineLumaPixels: IntArray,
        localPixels: IntArray,
        localLeft: Int,
        localTop: Int,
        localWidth: Int,
        imageWidth: Int,
        imageHeight: Int,
        maskColor: Int,
        targetColor: Int,
        radius: Int,
        linePixelThreshold: Int
    ) {
        for (idx in region.indices) {
            val x = idx % imageWidth
            val y = idx / imageWidth
            for (dy in -radius..radius) {
                val ny = y + dy
                if (ny !in 0 until imageHeight) continue
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx !in 0 until imageWidth) continue
                    val nIdx = ny * imageWidth + nx
                    if (coloredPixels[nIdx] != 0) continue
                    if (isRegionPixel(nIdx, maskPixels, fillCoveragePixels, maskColor)) continue
                    if (lineLumaPixels[nIdx] >= linePixelThreshold) continue
                    localPixels[(ny - localTop) * localWidth + (nx - localLeft)] = targetColor
                }
            }
        }
    }

    private fun isRegionPixel(
        idx: Int,
        maskPixels: IntArray,
        fillCoveragePixels: IntArray?,
        maskColor: Int
    ): Boolean =
        maskPixels[idx] == maskColor || fillCoveragePixels?.getOrNull(idx) == maskColor

    private fun isNearInk(
        idx: Int,
        lineLumaPixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        threshold: Int
    ): Boolean {
        val x = idx % width
        val y = idx / width
        for (dy in -radius..radius) {
            val ny = y + dy
            if (ny !in 0 until height) continue
            for (dx in -radius..radius) {
                val nx = x + dx
                if (nx !in 0 until width) continue
                if (lineLumaPixels[ny * width + nx] < threshold) return true
            }
        }
        return false
    }

    private fun isBrightDetail(pixel: Int): Boolean {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha == 0) return true
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = (r * 299 + g * 587 + b * 114) / 1000
        return alpha < 32 || luma > 220
    }
}

internal class GrowingIntArray(initialCapacity: Int) {
    private var values = IntArray(initialCapacity.coerceAtLeast(1))
    var size = 0
        private set

    operator fun get(index: Int): Int = values[index]

    fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(values.size * 2)
        }
        values[size++] = value
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

internal object FillAnimationTiming {
    private const val MIN_DURATION_MS = 120f
    private const val MAX_DURATION_MS = 260f
    private const val MS_PER_SCREEN_RADIUS_PX = 0.12f

    /**
     * Tính thời lượng từ bán kính reveal sau khi đã được transform lên màn hình.
     *
     * Cùng một vùng sẽ reveal lâu hơn khi người dùng zoom vào, thay vì dùng số pixel bitmap
     * gốc (vốn không phản ánh kích thước mà mắt người dùng đang thấy).
     */
    fun durationMs(screenRevealRadiusPx: Float): Float {
        return (MIN_DURATION_MS + screenRevealRadiusPx.coerceAtLeast(0f) * MS_PER_SCREEN_RADIUS_PX)
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    fun easedProgress(elapsedMs: Float, durationMs: Float): Float {
        if (durationMs <= 0f) return 1f
        val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
        val remaining = 1f - linear
        return 1f - remaining * remaining * remaining
    }
}

class AnimatedFiller(
    private val maskPixels: IntArray,
    private val coloredPixels: IntArray,
    private val width: Int,
    private val height: Int,
    val maskColor: Int,
    val targetColor: Int,
    val startX: Int,
    val startY: Int,
    animationScale: Float,
    maxQueueSize: Int,
    val onFinished: (Int) -> Unit,
    // Lớp detail (RGBA) kéo màu phẳng của bảng màu về gần màu ảnh gốc. Không truyền vào thì
    // lúc loang chỉ thấy màu phẳng rồi mới "nhảy" sang màu đúng khi animation kết thúc — đo
    // trên data: lệch so với màu gốc 24.1 lúc đang loang so với 4.9 sau khi xong (Art/09).
    private val detailPixels: IntArray? = null,
    private val fillCoveragePixels: IntArray? = null,
    private val lineLumaPixels: IntArray? = null
) {
    val localBitmap: Bitmap
    val left: Int
    val top: Int
    var currentRadius = 0f
    val maxRadius: Float
    private var elapsedMs = 0f
    private val durationMs: Float
    private val indices: IntArray

    init {
        val maskRegion = FillRegionCollector.collect(
            maskPixels = maskPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            startX = startX,
            startY = startY,
            expectedRegionArea = maxQueueSize
        )
        val region = FillCoverageCollector.includeConnectedCoverage(
            region = maskRegion,
            maskPixels = maskPixels,
            fillCoveragePixels = fillCoveragePixels,
            width = width,
            height = height,
            maskColor = maskColor
        )
        indices = region.indices

        val frame = AnimationFillFrameComposer.compose(
            region = region,
            maskPixels = maskPixels,
            coloredPixels = coloredPixels,
            detailPixels = detailPixels,
            fillCoveragePixels = fillCoveragePixels,
            lineLumaPixels = lineLumaPixels,
            imageWidth = width,
            imageHeight = height,
            maskColor = maskColor,
            targetColor = targetColor
        )

        left = frame.left
        top = frame.top
        // Tính toán bán kính tối đa cần để loang hết bounding box
        val dx1 = (region.minX - startX).toDouble()
        val dx2 = (region.maxX - startX).toDouble()
        val dy1 = (region.minY - startY).toDouble()
        val dy2 = (region.maxY - startY).toDouble()
        val d1 = Math.sqrt(dx1 * dx1 + dy1 * dy1)
        val d2 = Math.sqrt(dx2 * dx2 + dy1 * dy1)
        val d3 = Math.sqrt(dx1 * dx1 + dy2 * dy2)
        val d4 = Math.sqrt(dx2 * dx2 + dy2 * dy2)
        maxRadius = Math.max(Math.max(d1, d2), Math.max(d3, d4)).toFloat() + 5f
        durationMs = FillAnimationTiming.durationMs(maxRadius * animationScale.coerceAtLeast(0f))

        localBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        localBitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    }

    /**
     * Màu hiển thị khi đang loang.
     *
     * Pixel thuộc mask thật dùng cùng công thức alpha-over với revealedDetailBitmap.
     */
    private fun colorWithDetail(index: Int): Int {
        return FillColorComposer.colorWithOptionalDetail(
            isMaskPixel = maskPixels[index] == maskColor,
            targetColor = targetColor,
            detailColor = detailPixels?.getOrNull(index),
        )
    }

    /**
     * Cập nhật bán kính loang màu
     */
    fun tick(deltaMs: Float): Boolean {
        elapsedMs += deltaMs.coerceAtLeast(0f)
        currentRadius = maxRadius * FillAnimationTiming.easedProgress(elapsedMs, durationMs)
        val isFinished = elapsedMs >= durationMs
        if (isFinished) {
            // Giữ frame cuối giống hệt màu đang animation: pixel mask thật có detail,
            // pixel coverage quanh line chỉ lấy màu nền để không tạo "flash" màu phẳng.
            for (idx in indices) {
                coloredPixels[idx] = colorWithDetail(idx)
            }
        }
        return !isFinished
    }

    fun dispatchFinished() {
        onFinished(maskColor)
    }

    fun recycle() {
        localBitmap.recycle()
    }
}
