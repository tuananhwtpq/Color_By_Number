package com.example.baseproject.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.baseproject.BuildConfig
import com.example.baseproject.data.LevelConfig
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RemoteAssetLoader(
    private val baseUrl: String = BuildConfig.PIXCOLOR_BASE_URL,
    private val gson: Gson = Gson(),
    private val cacheDir: File? = null,
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
        readCachedBytes(resolveUrl(configPath) ?: throw RemoteApiException("Missing config path"), "json")
            .inputStream()
            .use { stream ->
                InputStreamReader(stream).use { reader ->
                    gson.fromJson(reader, LevelConfig::class.java)
                }
        }

    fun downloadBitmap(path: String, label: String): Bitmap =
        readCachedBytes(resolveUrl(path) ?: throw RemoteApiException("Missing $label path"), "bin")
            .let { bytes ->
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } ?: throw RemoteApiException("Failed to decode $label bitmap")

    private fun readCachedBytes(url: String, extension: String): ByteArray {
        val cacheFile = cacheFile(url, extension) ?: return downloadBytes(url)
        if (cacheFile.isFile && cacheFile.length() > 0L) {
            return cacheFile.readBytes()
        }

        return synchronized(cacheFile.absolutePath.intern()) {
            if (cacheFile.isFile && cacheFile.length() > 0L) {
                cacheFile.readBytes()
            } else {
                val bytes = downloadBytes(url)
                cacheFile.parentFile?.mkdirs()
                val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                tmpFile.writeBytes(bytes)
                if (cacheFile.exists() && !cacheFile.delete()) {
                    tmpFile.delete()
                    throw RemoteApiException("Cannot replace cached asset ${cacheFile.absolutePath}")
                }
                if (!tmpFile.renameTo(cacheFile)) {
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                }
                bytes
            }
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RemoteApiException("$url failed with HTTP ${response.code}")
            }
            return response.body?.bytes()
                ?: throw RemoteApiException("$url response body is empty")
        }
    }

    private fun cacheFile(url: String, extension: String): File? {
        val dir = cacheDir ?: return null
        return File(dir, "${sha256(url)}.$extension")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
