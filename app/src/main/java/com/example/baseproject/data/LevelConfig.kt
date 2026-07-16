package com.example.baseproject.data

import android.graphics.Color
import com.google.gson.annotations.SerializedName

data class LevelConfig(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("category") val category: String,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("palette") val palette: List<PaletteItem>,
    @SerializedName("regions") val regions: List<RegionMetadata>? = null,
    @SerializedName("estimated_difficulty") val estimatedDifficulty: Int? = null,
    @SerializedName("total_regions") val totalRegions: Int? = null,
    @SerializedName("unique_numbers") val uniqueNumbers: Int? = null,
    @SerializedName("small_regions_count") val smallRegionsCount: Int? = null
) {
    fun hasRegionMetadata(): Boolean = !regions.isNullOrEmpty()

    fun toRegionDataList(): List<RegionData> {
        return regions?.mapNotNull { it.toRegionData() }.orEmpty()
    }
}

data class PaletteItem(
    @SerializedName("number") val number: Int,
    @SerializedName("mask_color") val maskColorHex: String,
    @SerializedName("target_color") val targetColorHex: String
) {
    // Helper to get integer colors
    fun getMaskColorInt(): Int = android.graphics.Color.parseColor(maskColorHex)
    fun getTargetColorInt(): Int = android.graphics.Color.parseColor(targetColorHex)
}

data class RegionMetadata(
    @SerializedName("mask_color") val maskColorHex: String,
    @SerializedName("number") val number: Int,
    @SerializedName("target_color") val targetColorHex: String,
    @SerializedName("area") val area: Int,
    @SerializedName("bbox") val bbox: BoundingBox? = null,
    @SerializedName("centroid") val centroid: PointData? = null,
    @SerializedName("label_anchor") val labelAnchor: PointData? = null,
    @SerializedName("hide_number") val hideNumber: Boolean? = null
) {
    fun getMaskColorInt(): Int = Color.parseColor(maskColorHex)
    fun getTargetColorInt(): Int = Color.parseColor(targetColorHex)

    fun toRegionData(): RegionData? {
        val center = centroid ?: labelAnchor ?: return null
        val label = labelAnchor ?: center
        val box = bbox
        val radius = if (box != null) {
            (minOf(box.width(), box.height()) / 2f).coerceAtLeast(6f)
        } else {
            12f
        }
        return RegionData(
            maskColorInt = getMaskColorInt(),
            centerX = center.x,
            centerY = center.y,
            number = number,
            area = area,
            radius = radius,
            labelX = label.x,
            labelY = label.y,
            hideNumber = hideNumber == true
        )
    }
}

data class BoundingBox(
    @SerializedName("left") val left: Int,
    @SerializedName("top") val top: Int,
    @SerializedName("right") val right: Int,
    @SerializedName("bottom") val bottom: Int
) {
    fun width(): Int = (right - left + 1).coerceAtLeast(1)
    fun height(): Int = (bottom - top + 1).coerceAtLeast(1)
}

data class PointData(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float
)
