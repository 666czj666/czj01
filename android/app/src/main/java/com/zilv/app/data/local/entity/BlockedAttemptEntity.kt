package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拦截记录（PRD §3.8「被拦住的」数据来源）。
 *
 * 采用「合并计数」而非每次尝试写一行：PRD 规定同一 App 5 秒内合并为 1 次
 * （见 [com.zilv.app.domain.intercept.AttemptMerger]），否则报告里的次数会虚高。
 */
@Entity(
    tableName = "blocked_attempts",
    indices = [Index("package_name"), Index("first_at")]
)
data class BlockedAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "rule_id") val ruleId: Long?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "first_at") val firstAtMillis: Long,
    @ColumnInfo(name = "last_at") val lastAtMillis: Long,
    val count: Int
)
