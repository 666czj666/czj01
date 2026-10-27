package com.zilv.app.location

import com.zilv.app.domain.model.GeoPoint

/**
 * 定位来源的抽象。
 *
 * 抽成接口是为了让打卡流程**可单元测试** —— 通知是否在打卡成功后正确收起，
 * 是我需要能自动验证的行为，而真实的 [LocationProvider] 需要 Android Context。
 */
interface LocationSource {

    /** 是否已获得定位权限。 */
    fun hasPermission(): Boolean

    /**
     * 取一次当前位置。
     *
     * @return null 表示无权限、超时或定位失败
     */
    suspend fun currentLocation(timeoutMs: Long = LocationProvider.DEFAULT_TIMEOUT_MS): GeoPoint?
}
