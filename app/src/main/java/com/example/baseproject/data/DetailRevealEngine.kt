package com.example.baseproject.data

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
        targetColor: Int
    ) {
        for (idx in maskPixels.indices) {
            if (maskPixels[idx] != maskColor) continue
            coloredPixels[idx] = targetColor
            if (detailSourcePixels != null && revealedDetailPixels != null) {
                revealedDetailPixels[idx] = detailSourcePixels[idx]
            }
        }
    }
}
