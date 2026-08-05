package com.example.baseproject.data

object DetailRevealEngine {
    fun revealDetailForMaskColor(
        maskPixels: IntArray,
        detailSourcePixels: IntArray,
        revealedDetailPixels: IntArray,
        maskColor: Int
    ) {
        val count = minOf(maskPixels.size, detailSourcePixels.size, revealedDetailPixels.size)
        for (index in 0 until count) {
            if (maskPixels[index] == maskColor) {
                revealedDetailPixels[index] = detailSourcePixels[index]
            }
        }
    }
}
