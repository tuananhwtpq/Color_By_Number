package com.example.baseproject.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object VideoSaver {

    private const val ALBUM_NAME = "Pixlory"
    private const val MIME_TYPE = "video/mp4"

    suspend fun saveVideoToGallery(
        context: Context,
        sourceFile: File,
        displayName: String,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw IOException("Source video not found: ${sourceFile.absolutePath}")
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
            pendingUri = uri

            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            } ?: throw IOException("Cannot open output stream for $uri")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.success(uri)
        } catch (e: CancellationException) {
            deletePendingEntry(context, pendingUri)
            throw e
        } catch (e: Exception) {
            deletePendingEntry(context, pendingUri)
            Result.failure(e)
        }
    }

    private fun deletePendingEntry(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}
