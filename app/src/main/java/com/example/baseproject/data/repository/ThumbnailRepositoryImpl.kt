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
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
        }
    }

    override fun deleteThumbnail(category: String, levelId: String) {
        val file = getThumbnailFile(category, levelId)
        if (file.exists()) {
            file.delete()
        }
    }
}
