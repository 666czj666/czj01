package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 打卡点表（PRD §5 `spots`）。
 *
 * 半径与精度门槛的取值范围来自 PRD §2.2 默认值表（50–1000 米 / ≤50 米）。
 */
@Entity(
    tableName = "spots",
    indices = [Index("name")]
)
data class SpotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Int,
    @ColumnInfo(name = "min_accuracy_m") val minAccuracyM: Float,
    val address: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 「打卡解锁」记录。
 *
 * ⚠️ **这是对 PRD §5 的一处有意细化**：PRD 把打卡记录统一放在 `check_ins`
 * （含 `rule_id` 列）。但两类打卡的性质不同：
 *  - **习惯打卡**：喂连续天数统计，按习惯 + 日期查询，频率语义复杂
 *  - **解锁打卡**：按规则 + 窗口的一次性事件，只关心"本窗口内有没有打上"
 *
 * 混在一张表里会让连续天数查询被迫加额外过滤（排除 rule-only 记录），
 * 反而更容易出错。因此单独建表，两边语义各自清晰。
 */
@Entity(
    tableName = "rule_check_ins",
    indices = [Index("rule_id"), Index("checked_at")]
)
data class RuleCheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "spot_id") val spotId: Long,
    @ColumnInfo(name = "checked_at") val checkedAtMillis: Long,
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lng") val lng: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    /** ontime / late / abnormal_unlock */
    val status: String
)
