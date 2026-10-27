package com.zilv.app.domain.permission

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [LocationPermissionFlow] 的单元测试（v2.14）。
 *
 * ## 为什么这条流程值得单测
 *
 * 用户报的原话是：「权限体检里的前台定位按了无反应。」
 * 修法里有三件事**只有真机才碰得到**（`rememberLauncherForActivityResult`、
 * `shouldShowRequestPermissionRationale`、`startActivity(应用详情页)`），
 * 但"**结果之后该做什么**"是纯逻辑 —— 而它恰恰是最容易写错的一步：
 * 把"永久拒绝"误判成"普通拒绝"，用户就会陷入"点了按钮、系统不弹框、
 * 页面还是未授予"的死循环，现象与原来的 bug **一模一样**。
 *
 * 所以这一段必须离机钉死：
 *  1. 授予 → 只刷新
 *  2. 普通拒绝（还有 rationale）→ 让用户再点一次
 *  3. 永久拒绝（两个 rationale 都没了）→ 跳应用详情设置页
 *  4. 已经判定过永久拒绝 → 下一次直接跳设置页，不再无效请求
 */
class LocationPermissionFlowTest {

    @Test
    fun `请求的权限是精确加近似，且与清单声明一致`() {
        // Android 12+ 只在**同时请求**精确与近似时才给"精确"选项；
        // 两条都已在 AndroidManifest 里声明（没有新增任何 uses-permission）
        assertThat(LocationPermissionFlow.PERMISSIONS).containsExactly(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).inOrder()
        assertThat(Manifest.permission.ACCESS_FINE_LOCATION)
            .isEqualTo("android.permission.ACCESS_FINE_LOCATION")
        assertThat(Manifest.permission.ACCESS_COARSE_LOCATION)
            .isEqualTo("android.permission.ACCESS_COARSE_LOCATION")
    }

    @Test
    fun `已授予时只需要刷新状态`() {
        assertThat(
            LocationPermissionFlow.afterRequest(
                granted = true,
                fineRationale = false,
                coarseRationale = false
            )
        ).isEqualTo(LocationPermissionFlow.AfterRequest.RefreshOnly)
    }

    @Test
    fun `普通拒绝：还有 rationale 就让用户再点一次`() {
        // 只拒了精确、近似还能再问 —— 也是"普通拒绝"
        assertThat(
            LocationPermissionFlow.afterRequest(
                granted = false,
                fineRationale = true,
                coarseRationale = true
            )
        ).isEqualTo(LocationPermissionFlow.AfterRequest.AskAgain)
        assertThat(
            LocationPermissionFlow.afterRequest(
                granted = false,
                fineRationale = false,
                coarseRationale = true
            )
        ).isEqualTo(LocationPermissionFlow.AfterRequest.AskAgain)
    }

    @Test
    fun `两个 rationale 都没了说明勾了不再询问：退化成应用详情设置页`() {
        // 这是用户明确要求的退化路径：永久拒绝时再请求也不可能弹框，
        // 只能请用户去应用信息页手动打开
        assertThat(
            LocationPermissionFlow.afterRequest(
                granted = false,
                fineRationale = false,
                coarseRationale = false
            )
        ).isEqualTo(LocationPermissionFlow.AfterRequest.OpenAppDetails)
    }

    @Test
    fun `已经判定过永久拒绝时直接跳设置页，不再做一次无效请求`() {
        // 即使某个 ROM 在这个时刻仍报告 rationale=true（时序差异），
        // 只要之前已经确认过永久拒绝，就应该直接跳设置页 ——
        // 否则用户又会看到"按了没反应"
        assertThat(
            LocationPermissionFlow.afterRequest(
                granted = false,
                fineRationale = true,
                coarseRationale = true,
                previouslyPermanentlyDenied = true
            )
        ).isEqualTo(LocationPermissionFlow.AfterRequest.OpenAppDetails)
    }

    @Test
    fun `授予优先于永久拒绝标记 —— 已授权就不用再跳设置页`() {
        assertThat(
            LocationPermissionFlow.afterRequest(
                granted = true,
                fineRationale = false,
                coarseRationale = false,
                previouslyPermanentlyDenied = true
            )
        ).isEqualTo(LocationPermissionFlow.AfterRequest.RefreshOnly)
    }

    @Test
    fun `判定已授予只看精确定位`() {
        assertThat(
            LocationPermissionFlow.isGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true
                )
            )
        ).isTrue()
        // 只要了近似：打卡点校验要精度，按未授予处理（与 PermissionChecker 同一口径）
        assertThat(
            LocationPermissionFlow.isGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true
                )
            )
        ).isFalse()
        assertThat(LocationPermissionFlow.isGranted(emptyMap())).isFalse()
    }

    @Test
    fun `只拿到近似定位能被单独识别出来`() {
        val onlyCoarse = mapOf(
            Manifest.permission.ACCESS_FINE_LOCATION to false,
            Manifest.permission.ACCESS_COARSE_LOCATION to true
        )
        assertThat(LocationPermissionFlow.onlyApproximate(onlyCoarse)).isTrue()
        assertThat(
            LocationPermissionFlow.onlyApproximate(
                mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true)
            )
        ).isFalse()
        assertThat(LocationPermissionFlow.onlyApproximate(emptyMap())).isFalse()
    }
}
