package com.example.baseproject.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

class AnimatedFiller(
    private val maskPixels: IntArray,
    private val linePixels: IntArray,
    private val coloredPixels: IntArray,
    private val width: Int,
    private val height: Int,
    val maskColor: Int,
    val targetColor: Int,
    val startX: Int,
    val startY: Int,
    maxQueueSize: Int,
    val onFinished: (Int) -> Unit
) {
    val localBitmap: Bitmap
    val left: Int
    val top: Int
    var currentRadius = 0f
    val maxRadius: Float
    private val indices: IntArray
    private var count = 0

    init {
        val safeSize = Math.max(width * height / 10, maxQueueSize * 5)
        indices = IntArray(safeSize)
        val qIdx = IntArray(safeSize)
        var qHead = 0
        var qTail = 0

        val startIdx = startY * width + startX
        if (maskPixels[startIdx] == maskColor) {
            maskPixels[startIdx] = maskPixels[startIdx].inv()
        }
        indices[count++] = startIdx
        qIdx[qTail++] = startIdx

        val dx = intArrayOf(-1, 1, 0, 0)
        val dy = intArrayOf(0, 0, -1, 1)

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        // 1. Quét BFS để tìm toàn bộ pixel và Bounding Box
        while (qHead < qTail) {
            val idx = qIdx[qHead++]
            val x = idx % width
            val y = idx / width

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            for (i in 0 until 4) {
                val nx = x + dx[i]
                val ny = y + dy[i]
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (maskPixels[nIdx] == maskColor) {
                        maskPixels[nIdx] = maskPixels[nIdx].inv()
                        if (count < safeSize) indices[count++] = nIdx
                        if (qTail < safeSize) qIdx[qTail++] = nIdx
                    } else if (maskPixels[nIdx] != maskColor.inv()) {
                        // Color Bleeding
                        val linePx = linePixels[nIdx]
                        val r = (linePx shr 16) and 0xFF
                        val g = (linePx shr 8) and 0xFF
                        val b = linePx and 0xFF
                        if ((r + g + b) / 3 < 240) {
                            if (count < safeSize) indices[count++] = nIdx
                        }
                    }
                }
            }
        }

        // Khôi phục mảng mask
        for (i in 0 until count) {
            val idx = indices[i]
            if (maskPixels[idx] == maskColor.inv()) {
                maskPixels[idx] = maskColor
            }
        }

        // Bounding box cần được mở rộng thêm 1 pixel mỗi chiều
        // để chứa các điểm ảnh viền đen (Color Bleeding) nằm ngoài vùng mask
        left = Math.max(0, minX - 1)
        top = Math.max(0, minY - 1)
        val right = Math.min(width - 1, maxX + 1)
        val bottom = Math.min(height - 1, maxY + 1)

        val bw = right - left + 1
        val bh = bottom - top + 1

        // Tính toán bán kính tối đa cần để loang hết bounding box
        val dx1 = (minX - startX).toDouble()
        val dx2 = (maxX - startX).toDouble()
        val dy1 = (minY - startY).toDouble()
        val dy2 = (maxY - startY).toDouble()
        val d1 = Math.sqrt(dx1 * dx1 + dy1 * dy1)
        val d2 = Math.sqrt(dx2 * dx2 + dy1 * dy1)
        val d3 = Math.sqrt(dx1 * dx1 + dy2 * dy2)
        val d4 = Math.sqrt(dx2 * dx2 + dy2 * dy2)
        maxRadius = Math.max(Math.max(d1, d2), Math.max(d3, d4)).toFloat() + 5f

        // 2. Tạo một Bitmap thu nhỏ chứa riêng mảng màu này
        val localPx = IntArray(bw * bh)
        for (i in 0 until count) {
            val idx = indices[i]
            val x = idx % width
            val y = idx / width
            localPx[(y - top) * bw + (x - left)] = targetColor
        }

        localBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        localBitmap.setPixels(localPx, 0, bw, 0, 0, bw, bh)
    }

    /**
     * Cập nhật bán kính loang màu
     */
    fun tick(speed: Float): Boolean {
        currentRadius += speed
        val isFinished = currentRadius >= maxRadius
        if (isFinished) {
            // Khi kết thúc, gán toàn bộ pixel vào mảng chính
            for (i in 0 until count) {
                coloredPixels[indices[i]] = targetColor
            }
            onFinished(maskColor)
        }
        return !isFinished
    }

    fun clearHighlight(hlPx: IntArray) {
        for (i in 0 until count) {
            hlPx[indices[i]] = 0
        }
    }
}
