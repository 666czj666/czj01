package com.zilv.app.domain.spot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 地理计算的单元测试。
 *
 * 使用可独立验证的参考值：
 *  - 地球半径取 IUGG 平均值 6371008.8 m
 *  - 1 度纬度 = 2πR / 360 ≈ 111194.93 m
 *  - 赤道上 1 度经度同样 ≈ 111194.93 m
 */
class GeoUtilsTest {

    private val oneDegreeMeters = 111_194.93

    @Test
    fun `同一点距离为0`() {
        assertThat(GeoUtils.haversineMeters(30.0, 120.0, 30.0, 120.0)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `一度纬度约等于 111195 米`() {
        val d = GeoUtils.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertThat(d).isWithin(1.0).of(oneDegreeMeters)
    }

    @Test
    fun `赤道上一度经度约等于 111195 米`() {
        val d = GeoUtils.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertThat(d).isWithin(1.0).of(oneDegreeMeters)
    }

    @Test
    fun `高纬度处经度间距显著变小`() {
        // 60 度纬度上，cos(60°)=0.5，一度经度约为赤道的一半
        val d = GeoUtils.haversineMeters(60.0, 0.0, 60.0, 1.0)
        assertThat(d).isWithin(500.0).of(oneDegreeMeters / 2)
    }

    @Test
    fun `距离对称`() {
        val a = GeoUtils.haversineMeters(30.123, 120.456, 31.789, 121.012)
        val b = GeoUtils.haversineMeters(31.789, 121.012, 30.123, 120.456)
        assertThat(a).isWithin(1e-6).of(b)
    }

    @Test
    fun `对跖点距离约等于半个地球周长`() {
        val d = GeoUtils.haversineMeters(0.0, 0.0, 0.0, 180.0)
        val halfCircumference = Math.PI * GeoUtils.EARTH_RADIUS_M
        assertThat(d).isWithin(1.0).of(halfCircumference)
    }

    @Test
    fun `跨零点经度不会被算成绕远路`() {
        // 179 度到 -179 度，实际相距 2 度
        val d = GeoUtils.haversineMeters(0.0, 179.0, 0.0, -179.0)
        assertThat(d).isWithin(6.0).of(2 * oneDegreeMeters)
    }

    @Test
    fun `百米尺度的距离精度足够`() {
        // 纬度上 0.001 度 ≈ 111.19 米
        val d = GeoUtils.haversineMeters(30.0, 120.0, 30.001, 120.0)
        assertThat(d).isWithin(0.5).of(111.19)
    }

    @Test
    fun `距离不会因为浮点误差超过半周长`() {
        // asin(min(1, sqrt(a))) 的钳制保证不会出现 NaN
        val d = GeoUtils.haversineMeters(90.0, 0.0, -90.0, 0.0)
        assertThat(d.isNaN()).isFalse()
        assertThat(d).isWithin(1.0).of(Math.PI * GeoUtils.EARTH_RADIUS_M)
    }

    // ------------------------------------------------------------------ 方位角

    @Test
    fun `正北为0度`() {
        assertThat(GeoUtils.bearingDegrees(0.0, 0.0, 1.0, 0.0)).isWithin(0.01).of(0.0)
    }

    @Test
    fun `正东为90度`() {
        assertThat(GeoUtils.bearingDegrees(0.0, 0.0, 0.0, 1.0)).isWithin(0.01).of(90.0)
    }

    @Test
    fun `正南为180度`() {
        assertThat(GeoUtils.bearingDegrees(0.0, 0.0, -1.0, 0.0)).isWithin(0.01).of(180.0)
    }

    @Test
    fun `正西为270度`() {
        assertThat(GeoUtils.bearingDegrees(0.0, 0.0, 0.0, -1.0)).isWithin(0.01).of(270.0)
    }

    @Test
    fun `方位角始终落在 0 到 360 之间`() {
        val samples = listOf(
            Triple(30.0, 120.0, 31.0 to 121.0),
            Triple(-30.0, -120.0, -31.0 to -119.0),
            Triple(0.0, 179.9, 0.0 to -179.9)
        )
        samples.forEach { (lat, lng, target) ->
            val b = GeoUtils.bearingDegrees(lat, lng, target.first, target.second)
            assertThat(b).isAtLeast(0.0)
            assertThat(b).isLessThan(360.0)
        }
    }

    // ------------------------------------------------------------------ 方向提示

    @Test
    fun `方向提示覆盖八个方位`() {
        assertThat(GeoUtils.directionHint(0.0)).contains("北")
        assertThat(GeoUtils.directionHint(45.0)).contains("东北")
        assertThat(GeoUtils.directionHint(90.0)).contains("东")
        assertThat(GeoUtils.directionHint(135.0)).contains("东南")
        assertThat(GeoUtils.directionHint(180.0)).contains("南")
        assertThat(GeoUtils.directionHint(225.0)).contains("西南")
        assertThat(GeoUtils.directionHint(270.0)).contains("西")
        assertThat(GeoUtils.directionHint(315.0)).contains("西北")
    }

    @Test
    fun `方向提示处理负数与超过360的输入`() {
        assertThat(GeoUtils.directionHint(-90.0)).isEqualTo(GeoUtils.directionHint(270.0))
        assertThat(GeoUtils.directionHint(450.0)).isEqualTo(GeoUtils.directionHint(90.0))
    }
}
