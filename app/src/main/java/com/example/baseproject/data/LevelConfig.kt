package com.example.baseproject.data

import android.graphics.Color
import com.google.gson.annotations.SerializedName

data class LevelConfig(
    @SerializedName("schema_version") val schemaVersion: Int? = null,
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("category") val category: String,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("palette") val palette: List<PaletteItem>,
    @SerializedName("region_palette") val regionPalette: List<PaletteItem>? = null,
    @SerializedName("regions") val regions: List<RegionMetadata>? = null,
    @SerializedName("assets") val assets: LevelAssets? = null,
    @SerializedName("stats") val stats: LevelStats? = null,
    @SerializedName("generation") val generation: GenerationMetadata? = null,
    @SerializedName("estimated_difficulty") val estimatedDifficulty: Int? = null,
    @SerializedName("total_regions") val totalRegions: Int? = null,
    @SerializedName("unique_numbers") val uniqueNumbers: Int? = null,
    @SerializedName("small_regions_count") val smallRegionsCount: Int? = null
) {
    fun hasRegionMetadata(): Boolean = !regions.isNullOrEmpty()

    fun toRegionDataList(): List<RegionData> {
        return regions?.mapNotNull { it.toRegionData() }.orEmpty()
    }

    fun toRegionPaletteItems(): List<PaletteItem> {
        val explicitRegionPalette = regionPalette.orEmpty().filter { it.maskColorHex != null }
        if (explicitRegionPalette.isNotEmpty()) return explicitRegionPalette

        val regionDerivedPalette = regions.orEmpty().map {
            PaletteItem(
                number = it.number,
                maskColorHex = it.maskColorHex,
                targetColorHex = it.targetColorHex
            )
        }
        if (regionDerivedPalette.isNotEmpty()) return regionDerivedPalette

        return palette
    }
}

data class PaletteItem(
    @SerializedName("number") val number: Int,
    @SerializedName("mask_color") val maskColorHex: String? = null,
    @SerializedName("target_color") val targetColorHex: String
) {
    // Helper to get integer colors
    fun getMaskColorInt(): Int = android.graphics.Color.parseColor(
        requireNotNull(maskColorHex) { "Palette item does not have a mask_color" }
    )
    fun getTargetColorInt(): Int = android.graphics.Color.parseColor(targetColorHex)
}

data class RegionMetadata(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("mask_color") val maskColorHex: String,
    @SerializedName("number") val number: Int,
    @SerializedName("target_color") val targetColorHex: String,
    @SerializedName("area") val area: Int,
    @SerializedName("bbox") val bbox: BoundingBox? = null,
    @SerializedName("centroid") val centroid: PointData? = null,
    @SerializedName("label_anchor") val labelAnchor: PointData? = null,
    @SerializedName("hide_number") val hideNumber: Boolean? = null,
    @SerializedName("quality") val quality: RegionQuality? = null
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

data class LevelAssets(
    @SerializedName("line") val line: String? = null,
    @SerializedName("mask") val mask: String? = null,
    @SerializedName("preview") val preview: String? = null,
    @SerializedName("detail") val detail: String? = null,
    @SerializedName("debug_regions") val debugRegions: String? = null,
    @SerializedName("debug_report") val debugReport: String? = null
)

data class LevelStats(
    @SerializedName("total_regions") val totalRegions: Int? = null,
    @SerializedName("unique_numbers") val uniqueNumbers: Int? = null,
    @SerializedName("estimated_difficulty") val estimatedDifficulty: Int? = null,
    @SerializedName("small_regions_count") val smallRegionsCount: Int? = null,
    @SerializedName("giant_regions_count") val giantRegionsCount: Int? = null
)

data class GenerationMetadata(
    @SerializedName("source_mode") val sourceMode: String? = null,
    @SerializedName("brightness_threshold") val brightnessThreshold: Int? = null,
    @SerializedName("merge_threshold") val mergeThreshold: Float? = null,
    @SerializedName("line_close_radius") val lineCloseRadius: Int? = null,
    @SerializedName("target_unique_colors") val targetUniqueColors: Int? = null,
    @SerializedName("quantize_method") val quantizeMethod: String? = null,
    @SerializedName("category_profile") val categoryProfile: String? = null
)

data class RegionQuality(
    @SerializedName("is_tiny") val isTiny: Boolean? = null,
    @SerializedName("is_small") val isSmall: Boolean? = null,
    @SerializedName("touchable") val touchable: Boolean? = null,
    @SerializedName("merged_region_count") val mergedRegionCount: Int? = null
)

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
