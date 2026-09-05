package com.example.baseproject.data

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

private class GrowingIntArray(initialCapacity: Int) {
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

class AnimatedFiller(
    private val maskPixels: IntArray,
    private val coloredPixels: IntArray,
    private val width: Int,
    private val height: Int,
    val maskColor: Int,
    val targetColor: Int,
    val startX: Int,
    val startY: Int,
    maxQueueSize: Int,
    val onFinished: (Int) -> Unit,
    // Lớp detail (RGBA) kéo màu phẳng của bảng màu về gần màu ảnh gốc. Không truyền vào thì
    // lúc loang chỉ thấy màu phẳng rồi mới "nhảy" sang màu đúng khi animation kết thúc — đo
    // trên data: lệch so với màu gốc 24.1 lúc đang loang so với 4.9 sau khi xong (Art/09).
    private val detailPixels: IntArray? = null
) {
    val localBitmap: Bitmap
    val left: Int
    val top: Int
    var currentRadius = 0f
    val maxRadius: Float
    // internal để PaintCanvasView có thể clear highlight theo đúng vùng animation.
    internal val indices: IntArray

    init {
        val region = FillRegionCollector.collect(
            maskPixels = maskPixels,
            width = width,
            height = height,
            maskColor = maskColor,
            startX = startX,
            startY = startY,
            expectedRegionArea = maxQueueSize
        )
        indices = region.indices

        left = region.minX
        top = region.minY
        val right = region.maxX
        val bottom = region.maxY

        val bw = right - left + 1
        val bh = bottom - top + 1

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

        // 2. Tạo một Bitmap thu nhỏ chứa riêng mảng màu này
        val localPx = IntArray(bw * bh)
        for (idx in indices) {
            val x = idx % width
            val y = idx / width
            localPx[(y - top) * bw + (x - left)] = colorWithDetail(idx)
        }

        localBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        localBitmap.setPixels(localPx, 0, bw, 0, 0, bw, bh)
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
    fun tick(speed: Float): Boolean {
        currentRadius += speed
        val isFinished = currentRadius >= maxRadius
        if (isFinished) {
            // Khi kết thúc, gán toàn bộ pixel vào mảng chính
            for (idx in indices) {
                coloredPixels[idx] = targetColor
            }
        }
        return !isFinished
    }

    fun dispatchFinished() {
        onFinished(maskColor)
    }

    fun clearHighlight(hlPx: IntArray) {
        for (idx in indices) {
            hlPx[idx] = 0
        }
    }
}
