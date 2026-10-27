package com.zilv.app.domain.spot

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地理计算（PRD §3.5）。
 *
 * 纯函数，不依赖任何 Android API，因此可以确定性地做单元测试 ——
 * 距离算错会直接导致"到了地方却打不了卡"，是本项目最不能出错的地方之一。
 */
object GeoUtils {

    /** IUGG 平均地球半径（米）。 */
    const val EARTH_RADIUS_M = 6_371_008.8

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Haversine 大圆距离，单位米。
     *
     * 对打卡这种"百米级"判定，球面模型完全够用
     * （与椭球模型的差距在百米尺度上远小于 GPS 本身的误差）。
     */
    fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dPhi = (lat2 - lat1) * DEG_TO_RAD
        val dLambda = (lng2 - lng1) * DEG_TO_RAD

        val a = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }

        // 用 asin(sqrt(a)) 而不是 atan2：在短距离上数值更稳定
        val c = 2 * asin(min(1.0, sqrt(a)))
        return EARTH_RADIUS_M * c
    }

    /**
     * 从点 1 指向点 2 的方位角（度，正北为 0，顺时针）。
     *
     * 用于打卡失败时显示"往哪个方向走"的罗盘箭头（PRD §4.3）。
     */
    fun bearingDegrees(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dLambda = (lng2 - lng1) * DEG_TO_RAD

        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)

        val deg = atan2(y, x) * RAD_TO_DEG
        return (deg + 360.0) % 360.0
    }

    /** 把用户当前朝向与目标方位角转换为罗盘提示文案。 */
    fun directionHint(bearingDeg: Double): String {
        val normalized = ((bearingDeg % 360) + 360) % 360
        return when {
            normalized < 22.5 || normalized >= 337.5 -> "正前方（北）"
            normalized < 67.5 -> "右前方（东北）"
            normalized < 112.5 -> "右侧（东）"
            normalized < 157.5 -> "右后方（东南）"
            normalized < 202.5 -> "正后方（南）"
            normalized < 247.5 -> "左后方（西南）"
            normalized < 292.5 -> "左侧（西）"
            else -> "左前方（西北）"
        }
    }
}
