package com.example.baseproject.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FillColorComposerTest {
    @Test
    fun maskPixelAppliesDetailOverTargetColor() {
        val targetColor = 0xFFDF5D4E.toInt()
        val detailColor = 0x80FFFFFF.toInt()

        val result = FillColorComposer.colorWithOptionalDetail(
            isMaskPixel = true,
            targetColor = targetColor,
            detailColor = detailColor,
        )

        assertEquals(0xFFEFAEA6.toInt(), result)
    }

    @Test
    fun bleedPixelDoesNotApplyDetail() {
        val targetColor = 0xFFDF5D4E.toInt()
        val detailColor = 0x80FFFFFF.toInt()

        val result = FillColorComposer.colorWithOptionalDetail(
            isMaskPixel = false,
            targetColor = targetColor,
            detailColor = detailColor,
        )

        assertEquals(targetColor, result)
    }

    @Test
    fun transparentDetailLeavesTargetColorUnchanged() {
        val targetColor = 0xFFDF5D4E.toInt()
        val detailColor = 0x00FFFFFF

        val result = FillColorComposer.colorWithOptionalDetail(
            isMaskPixel = true,
            targetColor = targetColor,
            detailColor = detailColor,
        )

        assertEquals(targetColor, result)
    }
}
