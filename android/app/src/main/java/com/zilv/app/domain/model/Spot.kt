package com.zilv.app.domain.model

/**
 * 打卡点（PRD §3.5 / §5 `spots`）。
 *
 * 半径与精度门槛的最小值/最大值来自 PRD §2.2 的默认值表：
 * 半径 50–1000 米（默认 100），最小精度门槛默认 ≤ 50 米。
 */
data class Spot(
    val id: Long = 0L,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
    val minAccuracyM: Float = DEFAULT_MIN_ACCURACY_M,
    val address: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(lat in -90.0..90.0) { "纬度超出范围: $lat" }
        require(lng in -180.0..180.0) { "经度超出范围: $lng" }
    }

    val isValid: Boolean
        get() = name.isNotBlank() && radiusM in RADIUS_RANGE && minAccuracyM > 0f

    companion object {
        const val DEFAULT_RADIUS_M = 100
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 1000
        val RADIUS_RANGE = MIN_RADIUS_M..MAX_RADIUS_M

        const val DEFAULT_MIN_ACCURACY_M = 50f
    }
}

/** 一次定位结果。 */
data class GeoPoint(
    val lat: Double,
    val lng: Double,
    /** 系统报告的精度（米，68% 置信半径） */
    val accuracyM: Float,
    /** 定位时刻（epoch millis） */
    val atMillis: Long
)
