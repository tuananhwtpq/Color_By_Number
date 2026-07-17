package com.example.baseproject.data.repository

import android.graphics.Bitmap
import java.io.File

interface ThumbnailRepository {
    fun getThumbnailFile(category: String, levelId: String): File
    fun saveThumbnail(category: String, levelId: String, bitmap: Bitmap, size: Int = 400)
    fun deleteThumbnail(category: String, levelId: String)
}
