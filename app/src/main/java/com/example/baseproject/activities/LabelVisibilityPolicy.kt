package com.example.baseproject.activities

/**
 * Giữ trạng thái hiện/ẩn nhãn theo kích thước vùng trên MÀN HÌNH.
 *
 * Hai ngưỡng khác nhau tránh nhãn chớp tắt khi người dùng pinch-zoom sát ngưỡng.
 */
internal object LabelVisibilityPolicy {
    const val SHOW_RADIUS_PX = 25f
    const val HIDE_RADIUS_PX = 20f

    fun shouldShow(wasVisible: Boolean, screenRadiusPx: Float): Boolean {
        return if (wasVisible) {
            screenRadiusPx >= HIDE_RADIUS_PX
        } else {
            screenRadiusPx >= SHOW_RADIUS_PX
        }
    }
}
