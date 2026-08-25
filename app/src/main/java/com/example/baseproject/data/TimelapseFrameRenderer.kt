package com.example.baseproject.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.example.baseproject.data.repository.LevelBundle

class TimelapseFrameRenderer(
    bundle: LevelBundle,
    paintHistory: List<Int>,
) {
    private val width = bundle.maskBitmap.width
    private val height = bundle.maskBitmap.height
    private val displayLineBitmap = bundle.displayLineBitmap
    private val detailBitmap = bundle.detailBitmap
    private val targetColorsByMaskColor = bundle.config.toRegionPaletteItems()
        .associate { it.getMaskColorInt() to it.getTargetColorInt() }
    private val orderedMaskColors = paintHistory.distinct()
        .filter { it in targetColorsByMaskColor }

    private val maskPixels = IntArray(width * height).also {
        bundle.maskBitmap.getPixels(it, 0, width, 0, 0, width, height)
    }
    private val pixelIndicesByMaskColor = buildPixelIndicesByMaskColor(maskPixels, orderedMaskColors)
    private val coloredPixels = IntArray(width * height)
    private val detailSourcePixels = detailBitmap
        ?.takeIf { it.width == width && it.height == height }
        ?.let { bitmap ->
            IntArray(width * height).also {
                bitmap.getPixels(it, 0, width, 0, 0, width, height)
            }
        }
    private val revealedDetailPixels = detailSourcePixels?.let { IntArray(width * height) }

    private val coloredBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val revealedDetailBitmap = if (revealedDetailPixels != null) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } else {
        null
    }
    val frameBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private val frameCanvas = Canvas(frameBitmap)
    private val lineBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
    private val normalPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    private var appliedCount = 0

    val stepCount: Int
        get() = orderedMaskColors.size

    fun renderStep(targetCount: Int): Bitmap {
        val safeTarget = targetCount.coerceIn(0, orderedMaskColors.size)
        if (safeTarget < appliedCount) {
            reset()
        }

        while (appliedCount < safeTarget) {
            val maskColor = orderedMaskColors[appliedCount]
            val targetColor = targetColorsByMaskColor[maskColor]
            if (targetColor != null) {
                applyRegion(maskColor, targetColor)
            }
            appliedCount++
        }

        coloredBitmap.setPixels(coloredPixels, 0, width, 0, 0, width, height)
        revealedDetailBitmap?.let { bitmap ->
            val pixels = revealedDetailPixels
            if (pixels != null) {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }

        frameCanvas.drawColor(BACKGROUND_COLOR)
        frameCanvas.drawBitmap(coloredBitmap, 0f, 0f, normalPaint)
        revealedDetailBitmap?.let {
            frameCanvas.drawBitmap(it, 0f, 0f, normalPaint)
        }
        frameCanvas.drawBitmap(displayLineBitmap, null, lineBounds, multiplyPaint)
        return frameBitmap
    }

    fun recycle() {
        coloredBitmap.recycle()
        revealedDetailBitmap?.recycle()
        frameBitmap.recycle()
    }

    private fun reset() {
        java.util.Arrays.fill(coloredPixels, 0)
        revealedDetailPixels?.let { java.util.Arrays.fill(it, 0) }
        appliedCount = 0
    }

    private fun applyRegion(maskColor: Int, targetColor: Int) {
        val indices = pixelIndicesByMaskColor[maskColor] ?: return
        val detailSource = detailSourcePixels
        val detailOutput = revealedDetailPixels
        for (index in indices) {
            coloredPixels[index] = targetColor
            if (detailSource != null && detailOutput != null) {
                detailOutput[index] = detailSource[index]
            }
        }
    }

    private fun buildPixelIndicesByMaskColor(
        maskPixels: IntArray,
        maskColors: List<Int>,
    ): Map<Int, IntArray> {
        val targetColors = maskColors.toHashSet()
        val counts = HashMap<Int, Int>(targetColors.size)
        for (pixel in maskPixels) {
            if (pixel in targetColors) {
                counts[pixel] = (counts[pixel] ?: 0) + 1
            }
        }

        val result = counts.mapValues { IntArray(it.value) }
        val positions = HashMap<Int, Int>(counts.size)
        for (index in maskPixels.indices) {
            val pixel = maskPixels[index]
            val indices = result[pixel] ?: continue
            val position = positions[pixel] ?: 0
            indices[position] = index
            positions[pixel] = position + 1
        }
        return result
    }

    private companion object {
        const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()
    }
}
