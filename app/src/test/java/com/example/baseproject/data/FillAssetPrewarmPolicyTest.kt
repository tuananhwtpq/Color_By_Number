package com.pixlory.color.by.number.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FillAssetPrewarmPolicyTest {
    @Test
    fun preparesLargestClickableRegionFirstWithoutExceedingBitmapBudget() {
        val smallColor = 1
        val largeColor = 2
        val regions = mapOf(
            smallColor to MaskColorPixelRegion(intArrayOf(0), 0, 0, 1, 1),
            largeColor to MaskColorPixelRegion(intArrayOf(1), 0, 0, 2, 2),
        )

        val selected = FillAssetPrewarmPolicy.selectMaskColors(
            activeMaskColors = setOf(smallColor, largeColor),
            regions = regions,
            bitmapBudgetBytes = 40,
        )

        assertEquals(listOf(largeColor), selected)
    }

    @Test
    fun stillPreparesOneLargeRegionWhenItAloneExceedsTheBudget() {
        val largeColor = 7
        val regions = mapOf(
            largeColor to MaskColorPixelRegion(intArrayOf(0), 0, 0, 9, 9),
        )

        val selected = FillAssetPrewarmPolicy.selectMaskColors(
            activeMaskColors = setOf(largeColor),
            regions = regions,
            bitmapBudgetBytes = 40,
        )

        assertEquals(listOf(largeColor), selected)
    }
}
