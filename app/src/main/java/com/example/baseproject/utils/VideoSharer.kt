package com.example.baseproject.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object VideoSharer {

    const val SHARE_MIME_TYPE = "video/mp4"

    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    suspend fun prepareShareUri(
        context: Context,
        sourceFile: File,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw IOException("Source video not found: ${sourceFile.absolutePath}")
            }

            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + FILE_PROVIDER_SUFFIX,
                sourceFile
            )
            Result.success(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
