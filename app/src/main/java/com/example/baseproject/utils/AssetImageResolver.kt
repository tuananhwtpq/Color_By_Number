package com.example.baseproject.utils

import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.InputStream

object AssetImageResolver {
    private val supportedExtensions = listOf("webp", "png", "jpg", "jpeg")

    fun resolveAssetPath(assetManager: AssetManager, basePath: String): String {
        for (extension in supportedExtensions) {
            val candidate = "$basePath.$extension"
            if (assetExists(assetManager, candidate)) {
                return candidate
            }
        }
        throw FileNotFoundException(
            "No asset image found for $basePath with extensions: ${supportedExtensions.joinToString()}"
        )
    }

    fun openResolvedAsset(assetManager: AssetManager, basePath: String): InputStream {
        return assetManager.open(resolveAssetPath(assetManager, basePath))
    }

    fun openResolvedAssetOrNull(assetManager: AssetManager, basePath: String): InputStream? {
        return try {
            openResolvedAsset(assetManager, basePath)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun toAndroidAssetUri(assetManager: AssetManager, basePath: String): String {
        return "file:///android_asset/${resolveAssetPath(assetManager, basePath)}"
    }

    private fun assetExists(assetManager: AssetManager, path: String): Boolean {
        return try {
            assetManager.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
