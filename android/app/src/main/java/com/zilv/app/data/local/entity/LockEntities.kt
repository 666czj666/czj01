package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 锁机规则表（PRD §5 `lock_rules`）。
 *
 * 复杂字段用字符串编码（编解码见 Mappers.kt），与 habits 表的处理方式一致：
 * 纯字符串比 JSON 更好做单元测试，也避免在 JVM 单测里依赖 Android 的 org.json。
 */
@Entity(
    tableName = "lock_rules",
    indices = [Index("enabled"), Index("sort_order"), Index("habit_id")]
)
data class LockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /**
     * 所属习惯；null 表示旧版遗留的独立规则（仍可正常工作）。
     *
     * 加列而不是建新表：调度与拦截整条链路都建立在 lock_rules 之上，
     * 换表意味着同时重写四条链路，风险远大于收益。
     */
    @ColumnInfo(name = "habit_id") val habitId: Long? = null,
    /**
     * 来源规划（`plans.id`）；null 表示不是规划派生的规则。
     *
     * v2.4 新增。规划规则的真相来源仍是 `plans` 表，这一列只是让
     * [com.zilv.app.domain.lock.LockRule.planId] 能**完整往返**：
     * 万一将来某条规划规则被写进 `lock_rules`，它读回来仍能指回那条规划，
     * 而不是变成一条"来源不明、却只能到点解锁"的僵尸规则。
     */
    @ColumnInfo(name = "plan_id") val planId: Long? = null,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "start_date") val startDate: String?,   // ISO yyyy-MM-dd
    @ColumnInfo(name = "end_date") val endDate: String?,
    /** 逗号分隔的 ISO 星期序号（周一=1），空串表示每天 */
    @ColumnInfo(name = "days_of_week") val daysOfWeek: String,
    /** 未打卡时的锁机时间段；逗号分隔的 `start-end` 分钟数，如 "480-1080" */
    val windows: String,
    /**
     * 必须打卡的时间段；格式同 [windows]，**空串表示不要求打卡**。
     *
     * 空串时行为与 v1.x 完全一致（向后兼容）。
     */
    @ColumnInfo(name = "check_in_windows") val checkInWindows: String = "",
    /**
     * 锁机时长（分钟），上限 24 小时；0 表示走旧式自选窗口。
     *
     * v2.1：锁机窗口不再自选，由「打卡截止 + 该时长」推导。
     */
    @ColumnInfo(name = "lock_duration_minutes") val lockDurationMinutes: Int = 0,
    @ColumnInfo(name = "unlock_mode") val unlockMode: String,
    /**
     * 打卡能否解锁这条规则，**默认 true**（v2.4 新增）。
     *
     * 建表默认值是 1，所以历史数据与"没写过这一列"的旧规则读回来都是 true ——
     * 与 [com.zilv.app.domain.lock.LockRule.allowCheckInUnlock] 的默认值一致，
     * 习惯的锁机行为因此一字未变。
     *
     * **为什么必须落库**：如果不存这一列，任何一条 `allowCheckInUnlock = false`
     * 的规则（规划）一旦经过一次落库再读回，就会静默变回"打卡可解锁" ——
     * 用户明确要求的"仅到点解锁"被悄悄破坏，而且是**解锁方向的退化**
     * （安全相关）。这种静默退化正是本项目在 `UnlockModeCodec` 里
     * 特意处理 `EITHER` 的那种 bug，宁可多一列也不留这个坑。
     */
    @ColumnInfo(name = "allow_check_in_unlock", defaultValue = "1")
    val allowCheckInUnlock: Boolean = true,
    @ColumnInfo(name = "spot_id") val spotId: Long?,
    @ColumnInfo(name = "early_check_in_minutes") val earlyCheckInMinutes: Int,
    @ColumnInfo(name = "grace_minutes") val graceMinutes: Int,
    @ColumnInfo(name = "strict_mode") val strictMode: Boolean,
    @ColumnInfo(name = "strict_leave_minutes") val strictLeaveMinutes: Int,
    val message: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)

/**
 * 白名单表（PRD §5 `allow_apps`）。
 *
 * `scope` 区分内置强制项与用户可编辑项；内置项在 UI 上不可移除。
 */
@Entity(
    tableName = "allow_apps",
    primaryKeys = ["package_name"]
)
data class AllowAppEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    val label: String,
    /** builtin / user */
    val scope: String,
    val enabled: Boolean
)

/** 规则级白名单：在全局白名单之上叠加（PRD §3.2）。 */
@Entity(
    tableName = "rule_allow_apps",
    primaryKeys = ["rule_id", "package_name"]
)
data class RuleAllowAppEntity(
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "package_name") val packageName: String
)
