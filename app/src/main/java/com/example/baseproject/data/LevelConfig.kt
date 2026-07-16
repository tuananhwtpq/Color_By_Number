package com.example.baseproject.data

import com.google.gson.annotations.SerializedName

data class LevelConfig(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("category") val category: String,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("palette") val palette: List<PaletteItem>
)

data class PaletteItem(
    @SerializedName("number") val number: Int,
    @SerializedName("mask_color") val maskColorHex: String,
    @SerializedName("target_color") val targetColorHex: String
) {
    // Helper to get integer colors
    fun getMaskColorInt(): Int = android.graphics.Color.parseColor(maskColorHex)
    fun getTargetColorInt(): Int = android.graphics.Color.parseColor(targetColorHex)
}
