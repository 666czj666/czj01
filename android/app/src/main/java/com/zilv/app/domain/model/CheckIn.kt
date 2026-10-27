package com.zilv.app.domain.model

import java.time.LocalDate
import java.time.ZoneId

/** 打卡状态（PRD §5 `check_ins.status`）。 */
enum class CheckInStatus {
    /** 按时打卡 */
    ON_TIME,

    /** 迟到打卡（窗口已开始才打上） */
    LATE,

    /** 手动勾选（纯打卡习惯） */
    MANUAL,

    /** 异常解锁（室内定位失败走的降级通道，见 PRD §3.5） */
    ABNORMAL_UNLOCK
}

/**
 * 打卡记录领域模型。
 *
 * 同时保存精确时刻（[checkedAtMillis]）与本地日期（[epochDay]）：
 * 前者用于展示与排序，后者用于连续天数统计，避免时区换算带来的歧义。
 */
data class CheckIn(
    val id: Long = 0L,
    val habitId: Long,
    val ruleId: Long? = null,
    val sessionId: Long? = null,
    val checkedAtMillis: Long,
    val epochDay: Long,
    val status: CheckInStatus = CheckInStatus.MANUAL,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val distanceM: Double? = null,
    val spotId: Long? = null,
    val photoPath: String? = null,
    val note: String? = null
) {
    val localDate: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    companion object {
        fun epochDayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
    }
}
