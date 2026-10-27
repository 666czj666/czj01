package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 前台使用会话段（PRD §5 `usage_sessions`）。
 *
 * 为什么要存**原始段**而不只是按天聚合：锁机结束报告需要"某个时间窗口内"的
 * 各应用时长，而按天聚合的粒度切不出窗口。两条数据各司其职：
 *  - 本表：窗口内切片、最长连续使用
 *  - [AppUsageEntity]：使用时长页的快速按天查询
 */
@Entity(
    tableName = "usage_sessions",
    indices = [Index("package_name"), Index("started_at"), Index("epoch_day")]
)
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "started_at") val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at") val endedAtMillis: Long,
    @ColumnInfo(name = "duration_ms") val durationMillis: Long,
    @ColumnInfo(name = "epoch_day") val epochDay: Long
)

/** 按天聚合的使用时长（PRD §5 `app_usage`）。 */
@Entity(
    tableName = "app_usage",
    primaryKeys = ["epoch_day", "package_name"],
    indices = [Index("epoch_day")]
)
data class AppUsageEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "foreground_ms") val foregroundMillis: Long
)

/** 某条规则的某一次锁机窗口实例（PRD §5 `lock_sessions`）。 */
@Entity(
    tableName = "lock_sessions",
    indices = [Index("rule_id"), Index("window_start"), Index("report_seen")]
)
data class LockSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "rule_name") val ruleName: String,
    @ColumnInfo(name = "window_start") val windowStartMillis: Long,
    @ColumnInfo(name = "window_end") val windowEndMillis: Long,
    /** locked / unlocked / expired */
    val state: String,
    @ColumnInfo(name = "unlocked_at") val unlockedAtMillis: Long?,
    /** checkin / time / emergency / null */
    @ColumnInfo(name = "unlock_reason") val unlockReason: String?,
    /** 首页「上次回顾」卡片是否已查看（PRD §3.8） */
    @ColumnInfo(name = "report_seen") val reportSeen: Boolean
)

/**
 * 事件记录（PRD §5 `events`、§3.8「异常」区块）。
 *
 * 类型见 [com.zilv.app.data.repository.EventType]。
 */
@Entity(
    tableName = "events",
    indices = [Index("session_id"), Index("type"), Index("detected_at")]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    val type: String,
    @ColumnInfo(name = "detected_at") val detectedAtMillis: Long,
    val detail: String?
)
