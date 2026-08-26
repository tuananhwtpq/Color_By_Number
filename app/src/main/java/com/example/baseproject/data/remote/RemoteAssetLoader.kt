package com.example.baseproject.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.baseproject.BuildConfig
import com.example.baseproject.data.LevelConfig
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RemoteAssetLoader(
    private val baseUrl: String = BuildConfig.PIXCOLOR_BASE_URL,
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    fun resolveUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path

        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$cleanBase/$cleanPath"
    }

    fun downloadLevelConfig(configPath: String): LevelConfig =
        execute(resolveUrl(configPath) ?: throw RemoteApiException("Missing config path")) { response ->
            response.body?.byteStream()?.use { stream ->
                InputStreamReader(stream).use { reader ->
                    gson.fromJson(reader, LevelConfig::class.java)
                }
            } ?: throw RemoteApiException("Config response body is empty")
        }

    fun downloadBitmap(path: String, label: String): Bitmap =
        execute(resolveUrl(path) ?: throw RemoteApiException("Missing $label path")) { response ->
            response.body?.byteStream()?.use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } ?: throw RemoteApiException("$label response body is empty")
        } ?: throw RemoteApiException("Failed to decode $label bitmap")

    private fun <T> execute(url: String, read: (okhttp3.Response) -> T): T {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RemoteApiException("$url failed with HTTP ${response.code}")
            }
            return read(response)
        }
    }
}

