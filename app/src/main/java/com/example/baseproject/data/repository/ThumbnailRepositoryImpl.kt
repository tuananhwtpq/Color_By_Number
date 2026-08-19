package com.example.baseproject.data.repository

import android.content.Context
import android.graphics.Bitmap
import java.io.File

class ThumbnailRepositoryImpl(
    private val context: Context
) : ThumbnailRepository {

    override fun getThumbnailFile(category: String, levelId: String): File {
        val dir = File(context.filesDir, "thumbnails")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${category}_${levelId}.webp")
    }

    override fun saveThumbnail(category: String, levelId: String, bitmap: Bitmap, size: Int) {
        val file = getThumbnailFile(category, levelId)
        val thumbnail = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        }

        try {
            file.outputStream().use { out ->
                thumbnail.compress(Bitmap.CompressFormat.WEBP, 95, out)
            }
        } finally {
            if (thumbnail !== bitmap) {
                thumbnail.recycle()
            }
        }
    }

    override fun deleteThumbnail(category: String, levelId: String) {
        val file = getThumbnailFile(category, levelId)
        if (file.exists()) {
            file.delete()
        }
    }
}
