package com.example.baseproject.data

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DetailRevealEngineTest {
    @Test
    fun revealCopiesOnlyDetailPixelsForFilledMaskColor() {
        val maskPixels = intArrayOf(
            0x000001, 0x000002,
            0x000001, 0x000000
        )
        val detailSource = intArrayOf(
            0x11223344, 0x55667788,
            0x99aabbcc.toInt(), 0xddeeff00.toInt()
        )
        val detailOut = IntArray(4)

        DetailRevealEngine.revealDetailForMaskColor(
            maskPixels = maskPixels,
            detailSourcePixels = detailSource,
            revealedDetailPixels = detailOut,
            maskColor = 0x000001,
        )

        assertArrayEquals(
            intArrayOf(0x11223344, 0, 0x99aabbcc.toInt(), 0),
            detailOut
        )
    }
}
