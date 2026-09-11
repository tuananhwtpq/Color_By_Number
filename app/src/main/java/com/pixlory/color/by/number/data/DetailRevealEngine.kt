package com.pixlory.color.by.number.data

internal data class MaskColorPixelRegion(
    val indices: IntArray,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
}

internal object MaskColorPixelIndex {
    fun build(
        maskPixels: IntArray,
        fillCoveragePixels: IntArray?,
        width: Int,
        height: Int,
        targetMaskColors: Set<Int>,
    ): Map<Int, MaskColorPixelRegion> {
        if (width <= 0 || height <= 0 || maskPixels.size != width * height) return emptyMap()
        if (targetMaskColors.isEmpty()) return emptyMap()

        val builders = HashMap<Int, RegionBuilder>(targetMaskColors.size)

        fun add(maskColor: Int, index: Int) {
            if (maskColor !in targetMaskColors) return
            builders.getOrPut(maskColor) { RegionBuilder() }.add(index, width)
        }

        for (index in maskPixels.indices) {
            val maskColor = maskPixels[index]
            add(maskColor, index)

            val coverageColor = fillCoveragePixels?.getOrNull(index) ?: continue
            if (coverageColor != maskColor) {
                add(coverageColor, index)
            }
        }

        return builders.mapValues { (_, builder) -> builder.build() }
    }

    private class RegionBuilder {
        private val indices = GrowingIntArray(256)
        private var minX = Int.MAX_VALUE
        private var minY = Int.MAX_VALUE
        private var maxX = Int.MIN_VALUE
        private var maxY = Int.MIN_VALUE

        fun add(index: Int, imageWidth: Int) {
            indices.add(index)
            val x = index % imageWidth
            val y = index / imageWidth
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }

        fun build() = MaskColorPixelRegion(
            indices = indices.toIntArray(),
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
        )
    }
}

object DetailRevealEngine {
    /**
     * Quét toàn ảnh theo đúng [maskColor], gán cả màu phẳng lẫn lớp chi tiết cho MỌI pixel
     * trùng mã màu — không giới hạn theo vùng liên thông. CentroidCalculator coi toàn bộ pixel
     * cùng 1 mã màu là 1 vùng logic duy nhất (kể cả khi chúng nằm ở nhiều cụm tách rời trên
     * ảnh), nên animation loang cục bộ (flood-fill từ điểm chạm) chỉ chạm tới MỘT cụm — hàm
     * này chạy ngay sau đó để tô nốt các cụm còn lại, tránh phải đợi tới lúc mở lại tranh
     * (restoreProgressSuspend) mới đúng màu.
     */
    fun completeRegionForMaskColor(
        maskPixels: IntArray,
        coloredPixels: IntArray,
        detailSourcePixels: IntArray?,
        revealedDetailPixels: IntArray?,
        maskColor: Int,
        targetColor: Int,
        fillCoveragePixels: IntArray? = null
    ) {
        for (idx in maskPixels.indices) {
            val isMaskPixel = maskPixels[idx] == maskColor
            val isCoveragePixel = !isMaskPixel && fillCoveragePixels?.getOrNull(idx) == maskColor
            if (!isMaskPixel && !isCoveragePixel) continue
            coloredPixels[idx] = targetColor
            if (isMaskPixel && detailSourcePixels != null && revealedDetailPixels != null) {
                revealedDetailPixels[idx] = detailSourcePixels[idx]
            }
        }
    }

    internal fun completeRegionAtIndices(
        region: MaskColorPixelRegion,
        maskPixels: IntArray,
        coloredPixels: IntArray,
        detailSourcePixels: IntArray?,
        revealedDetailPixels: IntArray?,
        maskColor: Int,
        targetColor: Int,
        fillCoveragePixels: IntArray? = null,
    ) {
        for (idx in region.indices) {
            if (idx !in maskPixels.indices || idx !in coloredPixels.indices) continue
            val isMaskPixel = maskPixels[idx] == maskColor
            val isCoveragePixel = !isMaskPixel && fillCoveragePixels?.getOrNull(idx) == maskColor
            if (!isMaskPixel && !isCoveragePixel) continue

            coloredPixels[idx] = targetColor
            if (isMaskPixel && detailSourcePixels != null && revealedDetailPixels != null) {
                revealedDetailPixels[idx] = detailSourcePixels[idx]
            }
        }
    }
}
