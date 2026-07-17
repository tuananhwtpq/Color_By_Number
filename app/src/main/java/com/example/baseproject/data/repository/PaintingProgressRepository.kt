package com.example.baseproject.data.repository

interface PaintingProgressRepository {
    fun loadProgress(category: String, levelId: String): Set<Int>
    fun saveProgress(category: String, levelId: String, completedMaskColors: Set<Int>)
    fun resetProgress(category: String, levelId: String)
}
