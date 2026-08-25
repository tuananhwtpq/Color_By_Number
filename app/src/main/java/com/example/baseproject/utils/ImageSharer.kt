package com.example.baseproject.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object ImageSharer {

    const val SHARE_MIME_TYPE = "image/png"

    private const val SHARE_DIR = "shared_images"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /**
     * Dựng bản PNG trong cacheDir rồi trả về content:// URI để đính vào Intent chia sẻ.
     *
     * Phải đi qua FileProvider vì ảnh gốc nằm trong filesDir (bộ nhớ riêng của app): chia sẻ
     * thẳng bằng file:// sẽ ném FileUriExposedException từ API 24. Cũng chuyển webp sang PNG
     * luôn vì khá nhiều app nhận chia sẻ xử lý image/webp không tốt.
     *
     * Tên file cố định theo từng bức tranh nên chia sẻ lại sẽ ghi đè chính nó, không tạo rác
     * và cũng không xoá nhầm file mà app khác có thể còn đang đọc dở.
     */
    suspend fun prepareShareUri(
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

            val shareDir = File(context.cacheDir, SHARE_DIR)
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                bitmap.recycle()
                throw IOException("Cannot create share dir: ${shareDir.absolutePath}")
            }

            val targetFile = File(shareDir, "$displayName.png")
            try {
                targetFile.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("Compress bitmap failed")
                    }
                    output.flush()
                }
            } finally {
                bitmap.recycle()
            }

            // Ném IllegalArgumentException nếu đường dẫn chưa được khai báo trong file_paths.xml.
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + FILE_PROVIDER_SUFFIX,
                targetFile
            )
            Result.success(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException, SecurityException, IllegalArgumentException (FileProvider chưa cấu
            // hình)... gom hết về đây để phía UI chỉ cần báo thất bại.
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(IOException("Out of memory while preparing share image", e))
        }
    }
}
