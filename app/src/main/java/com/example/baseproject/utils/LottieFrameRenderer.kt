package com.example.baseproject.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.RawRes
import androidx.core.graphics.createBitmap
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object LottieFrameRenderer {

    /**
     * Ảnh gốc nhúng trong file Lottie chỉ 1024px, nên render lớn hơn khoảng 2x kích thước
     * composition chỉ làm nặng file chứ không thêm chi tiết nào.
     */
    private const val RENDER_SCALE = 2f

    /**
     * Vẽ đúng frame đang xem của một animation ra bitmap, cắt theo tỉ lệ màn hình giống
     * scaleType centerCrop — tức ảnh nhận được đúng bằng vùng người dùng đang nhìn thấy.
     *
     * Cố tình KHÔNG chụp view: chụp view sẽ dính nút back/download vẽ đè lên. Ở đây dựng một
     * [LottieDrawable] riêng rồi vẽ vào canvas của mình — cũng vì thế mà không được dùng chung
     * drawable với view đang hiển thị: hai luồng cùng vẽ một drawable sẽ ra hình hỏng.
     */
    suspend fun renderFrame(
        context: Context,
        @RawRes animationRes: Int,
        progress: Float,
        targetAspectRatio: Float
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Composition được LottieCompositionFactory cache theo rawRes, nên màn hình thứ hai
            // (và lần lưu thứ hai) không phải parse lại file JSON.
            val composition = LottieCompositionFactory
                .fromRawResSync(context, animationRes)
                .value ?: throw IOException("Cannot load animation $animationRes")

            val compositionWidth = composition.bounds.width().toFloat()
            val compositionHeight = composition.bounds.height().toFloat()
            if (compositionWidth <= 0f || compositionHeight <= 0f) {
                throw IOException("Animation has empty bounds")
            }

            val height = (compositionHeight * RENDER_SCALE).roundToInt()
            val width = (height * targetAspectRatio).roundToInt().coerceAtLeast(1)

            val drawable = LottieDrawable().apply {
                setComposition(composition)
                this.progress = progress.coerceIn(0f, 1f)
            }

            // centerCrop: phóng composition tới mức phủ kín khung rồi canh giữa phần thừa.
            val scale = max(width / compositionWidth, height / compositionHeight)
            val scaledWidth = (compositionWidth * scale).roundToInt()
            val scaledHeight = (compositionHeight * scale).roundToInt()
            drawable.setBounds(0, 0, scaledWidth, scaledHeight)

            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            canvas.translate((width - scaledWidth) / 2f, (height - scaledHeight) / 2f)
            drawable.draw(canvas)

            Result.success(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(IOException("Out of memory while rendering animation frame", e))
        }
    }
}
