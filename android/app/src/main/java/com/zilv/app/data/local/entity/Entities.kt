package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 习惯表（PRD §5 `habits`）。
 *
 * 频率拆成 `frequency_type` + `frequency_payload` 两列，编解码见
 * [com.zilv.app.domain.habit.HabitFrequencyCodec]。
 */
@Entity(
    tableName = "habits",
    indices = [Index("archived"), Index("sort_order")]
)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val icon: String,
    val color: Long,
    val type: String,
    @ColumnInfo(name = "frequency_type") val frequencyType: String,
    @ColumnInfo(name = "frequency_payload") val frequencyPayload: String,
    /** 提醒时间，逗号分隔的 HH:mm */
    @ColumnInfo(name = "remind_times") val remindTimes: String,
    @ColumnInfo(name = "spot_id") val spotId: Long?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 打卡记录表（PRD §5 `check_ins`）。
 *
 * `epoch_day` 是统计连续天数的关键列，单独建索引。
 */
@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habit_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("habit_id"),
        Index("epoch_day"),
        Index(value = ["habit_id", "epoch_day"], unique = false)
    ]
)
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "habit_id") val habitId: Long,
    @ColumnInfo(name = "rule_id") val ruleId: Long?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "checked_at") val checkedAtMillis: Long,
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    val status: String,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
    @ColumnInfo(name = "distance_m") val distanceM: Double?,
    @ColumnInfo(name = "spot_id") val spotId: Long?,
    @ColumnInfo(name = "photo_path") val photoPath: String?,
    val note: String?
)
