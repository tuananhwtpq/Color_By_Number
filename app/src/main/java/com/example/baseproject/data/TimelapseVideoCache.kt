package com.example.baseproject.data

import com.example.baseproject.data.repository.AssetLevelRepository
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.utils.toFileNameKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

class TimelapseVideoCache(
    cacheDir: File,
    private val assetLevelRepository: AssetLevelRepository,
    private val paintingProgressRepository: PaintingProgressRepository,
) {
    private val exportDir = File(cacheDir, EXPORT_DIR)
    private val mutexes = mutableMapOf<String, Mutex>()

    suspend fun ensureVideo(category: String, levelId: String): File {
        val spec = cacheSpec(category, levelId)
        if (spec.finalFile.isUsableVideo()) return spec.finalFile

        return mutexFor(spec.key).withLock {
            if (spec.finalFile.isUsableVideo()) return@withLock spec.finalFile
            generateVideo(spec, category, levelId)
        }
    }

    private suspend fun generateVideo(
        spec: CacheSpec,
        category: String,
        levelId: String,
    ): File {
        val history = paintingProgressRepository.loadPaintHistory(category, levelId)
        if (history.isEmpty()) {
            throw TimelapseVideoUnavailableException("Paint history is empty for $category/$levelId")
        }

        val tmpFile = spec.tmpFile
        try {
            exportDir.mkdirs()
            deleteStaleFiles(spec)
            tmpFile.delete()

            val bundle = assetLevelRepository.loadLevelBundle(category, levelId)
            TimelapseVideoGenerator.generateMp4(
                bundle = bundle,
                paintHistory = history,
                outputFile = tmpFile,
            ).getOrElse { error ->
                throw IOException("Generate timelapse video failed", error)
            }

            if (spec.finalFile.exists() && !spec.finalFile.delete()) {
                throw IOException("Cannot replace cached timelapse video: ${spec.finalFile.absolutePath}")
            }
            if (!tmpFile.renameTo(spec.finalFile)) {
                tmpFile.copyTo(spec.finalFile, overwrite = true)
                tmpFile.delete()
            }
            return spec.finalFile
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            tmpFile.delete()
            spec.finalFile.delete()
            throw e
        } catch (e: OutOfMemoryError) {
            tmpFile.delete()
            spec.finalFile.delete()
            throw IOException("Out of memory while generating timelapse video", e)
        }
    }

    private fun cacheSpec(category: String, levelId: String): CacheSpec {
        val stablePrefix = "Pixlory_Timelapse_${category}_${levelId}".toFileNameKey()
        val version = paintingProgressRepository.lastPaintedAt(category, levelId)
        val key = "${stablePrefix}_$version"
        return CacheSpec(
            key = key,
            stablePrefix = stablePrefix,
            finalFile = File(exportDir, "$key.mp4"),
            tmpFile = File(exportDir, "$key.tmp.mp4"),
        )
    }

    private fun deleteStaleFiles(spec: CacheSpec) {
        exportDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(spec.stablePrefix) && file.name != spec.finalFile.name) {
                file.delete()
            }
        }
    }

    @Synchronized
    private fun mutexFor(key: String): Mutex = mutexes.getOrPut(key) { Mutex() }

    private fun File.isUsableVideo(): Boolean = exists() && length() > 0L

    private data class CacheSpec(
        val key: String,
        val stablePrefix: String,
        val finalFile: File,
        val tmpFile: File,
    )

    private companion object {
        const val EXPORT_DIR = "timelapse_exports"
    }
}

class TimelapseVideoUnavailableException(message: String) : Exception(message)
