package com.example.baseproject.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.baseproject.data.repository.LevelBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

object TimelapseVideoGenerator {

    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val OUTPUT_SIZE = 720
    private const val FPS = 30
    private const val DURATION_MS = 15_000L
    private const val I_FRAME_INTERVAL_SECONDS = 1
    private const val BIT_RATE = 3_000_000
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L

    suspend fun generateMp4(
        bundle: LevelBundle,
        paintHistory: List<Int>,
        outputFile: File,
    ): Result<File> = withContext(Dispatchers.Default) {
        var renderer: TimelapseFrameRenderer? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var scaledBitmap: Bitmap? = null

        try {
            if (paintHistory.isEmpty()) {
                throw IOException("Paint history is empty")
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            renderer = TimelapseFrameRenderer(bundle, paintHistory)
            if (renderer.stepCount == 0) {
                throw IOException("No timelapse steps available")
            }

            val encoderConfig = findSupportedEncoderConfig()
            val format = MediaFormat.createVideoFormat(MIME_TYPE, OUTPUT_SIZE, OUTPUT_SIZE).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderConfig.colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }

            encoder = MediaCodec.createByCodecName(encoderConfig.codecName).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val coroutineContext = currentCoroutineContext()
            scaledBitmap = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
            encodeFrames(
                encoder = encoder,
                muxer = muxer,
                renderer = renderer,
                scaledBitmap = scaledBitmap,
                colorFormat = encoderConfig.colorFormat,
                checkCancellation = { coroutineContext.ensureActive() },
            )

            Result.success(outputFile)
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            outputFile.delete()
            Result.failure(IOException("Out of memory while generating timelapse video", e))
        } finally {
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            renderer?.recycle()
            scaledBitmap?.recycle()
        }
    }

    private fun encodeFrames(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        renderer: TimelapseFrameRenderer,
        scaledBitmap: Bitmap,
        colorFormat: Int,
        checkCancellation: () -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val totalFrames = (DURATION_MS / 1000f * FPS).roundToInt()
        var muxerStarted = false
        var videoTrackIndex = -1
        var frameIndex = 0
        val yuvFrame = ByteArray(OUTPUT_SIZE * OUTPUT_SIZE * 3 / 2)

        fun drain() {
            while (true) {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        return
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw IllegalStateException("Encoder format changed twice")
                        }
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outputIndex)
                            ?: throw IOException("Encoder output buffer $outputIndex was null")

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            if (!muxerStarted) {
                                throw IllegalStateException("Muxer has not started")
                            }
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return
                        }
                    }
                }
            }
        }

        while (frameIndex < totalFrames) {
            checkCancellation()
            val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)
                    ?: throw IOException("Encoder input buffer $inputIndex was null")
                val progress = frameIndex.toFloat() / (totalFrames - 1).coerceAtLeast(1)
                val step = (renderer.stepCount * progress).roundToInt()
                    .coerceIn(0, renderer.stepCount)

                val frame = renderer.renderStep(step)
                drawScaledFrame(source = frame, target = scaledBitmap)
                bitmapToYuv420(scaledBitmap, yuvFrame, colorFormat)

                inputBuffer.clear()
                inputBuffer.put(yuvFrame)

                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    yuvFrame.size,
                    presentationTimeUs(frameIndex),
                    0
                )
                frameIndex++
            }
            drain()
        }

        while (true) {
            checkCancellation()
            val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs(totalFrames),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                drain()
                break
            }
            drain()
        }
    }

    private fun drawScaledFrame(source: Bitmap, target: Bitmap) {
        val canvas = Canvas(target)
        canvas.drawColor(0xFFFFFFFF.toInt())
        canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE), SCALE_PAINT)
    }

    private fun bitmapToYuv420(bitmap: Bitmap, output: ByteArray, colorFormat: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize
        val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                output[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    if (semiPlanar) {
                        output[uvIndex++] = u.coerceIn(0, 255).toByte()
                        output[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        output[uIndex++] = u.coerceIn(0, 255).toByte()
                        output[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    private fun presentationTimeUs(frameIndex: Int): Long =
        frameIndex * 1_000_000L / FPS

    private fun findSupportedEncoderConfig(): EncoderConfig {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (codecInfo in codecs) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }) continue

            val capabilities = codecInfo.getCapabilitiesForType(MIME_TYPE)
            val formats = capabilities.colorFormats.toSet()
            val colorFormat = when {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

                else -> continue
            }
            return EncoderConfig(codecName = codecInfo.name, colorFormat = colorFormat)
        }
        throw IOException("No AVC encoder with YUV420 support")
    }

    private data class EncoderConfig(
        val codecName: String,
        val colorFormat: Int,
    )

    private val SCALE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
}
