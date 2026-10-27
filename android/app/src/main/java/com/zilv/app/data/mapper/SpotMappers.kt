package com.zilv.app.data.mapper

import com.zilv.app.data.local.entity.RuleCheckInEntity
import com.zilv.app.data.local.entity.SpotEntity
import com.zilv.app.domain.model.Spot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 解锁打卡的状态（PRD §3.8 报告的「打卡」区块）。 */
enum class RuleCheckInStatus {
    /** 按时（窗口开始前有效期内或窗口刚开始不久） */
    ON_TIME,

    /** 迟到（窗口已开始一段时间才打上） */
    LATE,

    /** 异常解锁（走室内降级通道，拍照存证 + 临时解锁） */
    ABNORMAL_UNLOCK;

    companion object {
        fun decode(raw: String): RuleCheckInStatus =
            runCatching { valueOf(raw.trim().uppercase()) }.getOrDefault(ON_TIME)
    }
}

/** 解锁打卡的领域视图。 */
data class RuleCheckIn(
    val id: Long = 0L,
    val ruleId: Long,
    val spotId: Long,
    val checkedAtMillis: Long,
    val epochDay: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val distanceM: Double,
    val status: RuleCheckInStatus
) {
    fun localDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        java.time.Instant.ofEpochMilli(checkedAtMillis).atZone(zone).toLocalDateTime()

    fun localDate(): LocalDate = LocalDate.ofEpochDay(epochDay)
}

fun SpotEntity.toDomain(): Spot = Spot(
    id = id,
    name = name,
    lat = lat,
    lng = lng,
    radiusM = radiusM,
    minAccuracyM = minAccuracyM,
    address = address,
    createdAt = createdAt
)

fun Spot.toEntity(): SpotEntity = SpotEntity(
    id = id,
    name = name,
    lat = lat,
    lng = lng,
    radiusM = radiusM,
    minAccuracyM = minAccuracyM,
    address = address,
    createdAt = createdAt
)

fun RuleCheckInEntity.toDomain(): RuleCheckIn = RuleCheckIn(
    id = id,
    ruleId = ruleId,
    spotId = spotId,
    checkedAtMillis = checkedAtMillis,
    epochDay = epochDay,
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    distanceM = distanceM,
    status = RuleCheckInStatus.decode(status)
)

fun RuleCheckIn.toEntity(): RuleCheckInEntity = RuleCheckInEntity(
    id = id,
    ruleId = ruleId,
    spotId = spotId,
    checkedAtMillis = checkedAtMillis,
    epochDay = epochDay,
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    distanceM = distanceM,
    status = status.name
)
