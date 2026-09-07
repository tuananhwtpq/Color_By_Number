package com.example.baseproject.data.repository

import com.example.baseproject.data.remote.RemoteLevelAssetDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDisplayLineAssetPolicyTest {
    @Test
    fun detectsSvgByMimeType() {
        val asset = RemoteLevelAssetDto(
            role = "DISPLAY_LINE",
            path = "/uploads/images/display-line",
            mimeType = "image/svg+xml"
        )

        assertTrue(RemoteDisplayLineAssetPolicy.isSvg(asset))
    }

    @Test
    fun detectsSvgByPathBeforeQueryString() {
        val asset = RemoteLevelAssetDto(
            role = "DISPLAY_LINE",
            path = "/uploads/images/display_line.svg?token=abc",
            mimeType = null
        )

        assertTrue(RemoteDisplayLineAssetPolicy.isSvg(asset))
    }

    @Test
    fun treatsRasterDisplayLineAsBitmap() {
        val asset = RemoteLevelAssetDto(
            role = "DISPLAY_LINE",
            path = "/uploads/images/display_line.webp",
            mimeType = "image/webp"
        )

        assertFalse(RemoteDisplayLineAssetPolicy.isSvg(asset))
    }

    @Test
    fun treatsMissingDisplayLineAsRasterFallback() {
        assertFalse(RemoteDisplayLineAssetPolicy.isSvg(null))
    }
}
