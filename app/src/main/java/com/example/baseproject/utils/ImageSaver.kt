package com.example.baseproject.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object ImageSaver {

    private const val ALBUM_NAME = "Pixlory"

    /**
     * Copy ảnh đã tô xong (file webp trong bộ nhớ riêng của app) ra thư viện ảnh của máy.
     * Từ API 29 trở lên MediaStore ghi vào Pictures/ không cần WRITE_EXTERNAL_STORAGE,
     * nên không phải xin quyền runtime (minSdk của app là 29).
     */
    suspend fun saveImageToGallery(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw IOException("Source image not found: ${sourceFile.absolutePath}")
            }

            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                ?: throw IOException("Cannot decode image: ${sourceFile.absolutePath}")

            try {
                writeToGallery(context, bitmap, displayName)
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(IOException("Out of memory while saving image", e))
        }
    }

    /**
     * Lưu một bitmap dựng sẵn trong bộ nhớ (vd frame render từ Lottie) ra thư viện ảnh.
     * Bitmap thuộc về phía gọi — hàm này không recycle nó.
     */
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            writeToGallery(context, bitmap, displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(IOException("Out of memory while saving image", e))
        }
    }

    private fun writeToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String
    ): Result<Uri> {
        var pendingUri: Uri? = null
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
                )
                // Đánh dấu pending để các app khác không đọc file khi chưa ghi xong.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
            pendingUri = uri

            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Compress bitmap failed")
                }
                output.flush()
            } ?: throw IOException("Cannot open output stream for $uri")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return Result.success(uri)
        } catch (e: CancellationException) {
            // Coroutine bị huỷ (activity destroy...) thì dọn bản ghi dở rồi ném tiếp.
            deletePendingEntry(context, pendingUri)
            throw e
        } catch (e: Exception) {
            // IOException, SecurityException, IllegalStateException... đều gom về đây để phía
            // UI chỉ cần báo thất bại.
            deletePendingEntry(context, pendingUri)
            return Result.failure(e)
        }
    }

    private fun deletePendingEntry(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
        }
    }
}
