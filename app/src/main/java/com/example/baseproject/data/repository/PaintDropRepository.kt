package com.example.baseproject.data.repository

data class PaintDropStats(
    val paintDrops: Int,
    val daysExplored: Int,
    val worksCompleted: Int,
    val areasUnlocked: Int,
)

interface PaintDropRepository {
    fun loadStats(): PaintDropStats
    fun loadUnlockedRealmIds(): Set<String>
    fun trackAppOpened()
    fun trackArtworkCompleted(category: String, levelId: String): Int
    fun unlockRealm(realmId: String)
}
