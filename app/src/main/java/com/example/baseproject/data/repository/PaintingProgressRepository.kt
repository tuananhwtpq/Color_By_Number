package com.example.baseproject.data.repository

interface PaintingProgressRepository {
    fun loadProgress(category: String, levelId: String): Set<Int>
    fun saveProgress(category: String, levelId: String, completedMaskColors: Set<Int>)
    fun loadPaintHistory(category: String, levelId: String): List<Int>
    fun appendPaintHistory(category: String, levelId: String, maskColor: Int)
    fun resetProgress(category: String, levelId: String)

    /** Mốc thời gian lần tô gần nhất (epoch millis), 0 nếu chưa từng tô hoặc chưa có dữ liệu. */
    fun lastPaintedAt(category: String, levelId: String): Long
}
