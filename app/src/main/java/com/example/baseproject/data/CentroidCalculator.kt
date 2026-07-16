package com.example.baseproject.data

import android.graphics.Bitmap

data class RegionData(
    val maskColorInt: Int,
    val centerX: Float,
    val centerY: Float,
    val number: Int, // Số hiển thị
    val area: Int,
    val radius: Float
)

object CentroidCalculator {

    /**
     * Quét maskBitmap 1 lần duy nhất để tính tâm (centroid) cho các mảng màu.
     */
    fun calculateCentroids(maskBitmap: Bitmap, paletteItems: List<PaletteItem>): List<RegionData> {
        val width = maskBitmap.width
        val height = maskBitmap.height
        val numPixels = width * height

        val maskPixels = IntArray(numPixels)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        // Map lưu tổng tọa độ và số lượng pixel: maskColor -> [sumX, sumY, count, minX, maxX, minY, maxY]
        val sums = HashMap<Int, LongArray>()

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = maskPixels[index]
                // Chỉ theo dõi các màu không phải là trong suốt / đen / trắng (nếu cần)
                var arr = sums[color]
                if (arr == null) {
                    arr = LongArray(7)
                    arr[3] = Long.MAX_VALUE // minX
                    arr[4] = Long.MIN_VALUE // maxX
                    arr[5] = Long.MAX_VALUE // minY
                    arr[6] = Long.MIN_VALUE // maxY
                    sums[color] = arr
                }
                arr[0] += x.toLong()
                arr[1] += y.toLong()
                arr[2] += 1L
                
                val lx = x.toLong()
                val ly = y.toLong()
                if (lx < arr[3]) arr[3] = lx
                if (lx > arr[4]) arr[4] = lx
                if (ly < arr[5]) arr[5] = ly
                if (ly > arr[6]) arr[6] = ly
                
                index++
            }
        }

        // Tạo danh sách RegionData
        val result = mutableListOf<RegionData>()
        
        // Map maskColor -> number từ paletteItems
        val colorToNumberMap = paletteItems.associate { it.getMaskColorInt() to it.number }

        for ((color, arr) in sums) {
            val count = arr[2]
            if (count > 0) {
                val number = colorToNumberMap[color]
                if (number != null) {
                    val cx = arr[0].toFloat() / count
                    val cy = arr[1].toFloat() / count
                    val area = count.toInt()
                    
                    val bWidth = (arr[4] - arr[3] + 1).toFloat()
                    val bHeight = (arr[6] - arr[5] + 1).toFloat()
                    
                    // Bán kính được tính bằng nửa cạnh nhỏ nhất của bounding box (ngăn chặn tràn chữ ở nét dài, hẹp)
                    val radius = Math.min(bWidth, bHeight) / 2f
                    
                    result.add(RegionData(color, cx, cy, number, area, radius))
                }
            }
        }

        return result
    }
}
