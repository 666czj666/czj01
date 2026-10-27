package com.zilv.app.domain.plan

import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import java.time.LocalDate

/**
 * 「明日规划」领域模型。
 *
 * 用户要求："添加明日规划界面，该界面只能添加明天的规划。自定义规划名称，
 * 自定义规划的开始和结束时间，规划时间内锁机，解锁条件仅为到点解锁，
 * 规划不能跨 0 点。日历上可以看见当天的规划。"
 *
 * ## 不变式（由 [com.zilv.app.data.repository.PlanRepository] 在写入口保证）
 *
 *  - [name] 非空白
 *  - [startMinute] / [endMinute] 都在 `0..1439`
 *  - `endMinute > startMinute` —— **不能跨 0 点**
 *
 * ## 为什么这个类本身不抛异常
 *
 * 本项目对"从库/备份里读出来的脏数据"的统一策略是**不抛异常、退化处理**
 * （见 `HabitFrequencyCodec` 与 `TimeWindowCodec` 的注释）：映射一抛异常，
 * 整个列表页就崩了。所以这里把不变式表达成可查询的 [isValid] / [invalidReason]，
 * 由仓库层的写入口负责**拒绝**（抛
 * [com.zilv.app.data.repository.PlanRejectedException]），
 * 读路径则永远安全。
 */
data class Plan(
    val id: Long = 0L,
    val name: String,
    /** 生效的那一天（本地日期）。 */
    val date: LocalDate,
    /** 当天第几分钟，含 */
    val startMinute: Int,
    /** 当天第几分钟，不含 —— 半开区间 `[startMinute, endMinute)` */
    val endMinute: Int,
    val createdAt: Long = 0L
) {

    /**
     * 起止时间是否是**当天内**的合法区间。
     *
     * ⚠️ 必须要求 `endMinute > startMinute`，不能只查范围：
     * [TimeWindow] 把"起止相同"定义成**全天 1440 分钟**（跨零点语义），
     * 所以 `TimeWindow(600, 600)` 不是"10:00 到 10:00 的 0 分钟"，
     * 而是一整天锁机。起止相同必须在这里被显式拦下。
     */
    val hasValidWindow: Boolean
        get() = startMinute in MINUTE_MIN..MINUTE_MAX &&
            endMinute in MINUTE_MIN..MINUTE_MAX &&
            endMinute > startMinute

    /** 名称与时间都合法。 */
    val isValid: Boolean get() = name.isNotBlank() && hasValidWindow

    /** 不合法时给用户看的原因；合法时返回 null。 */
    val invalidReason: String?
        get() = when {
            name.isBlank() -> "规划名称不能为空"
            startMinute !in MINUTE_MIN..MINUTE_MAX || endMinute !in MINUTE_MIN..MINUTE_MAX ->
                "起止时间必须在 00:00..23:59 之间"

            endMinute == startMinute -> "开始和结束时间不能相同（会被当成全天锁机）"
            endMinute < startMinute -> "规划不能跨 0 点，结束时间必须晚于开始时间"
            else -> null
        }

    /** 时长（分钟），恒为正。 */
    val durationMinutes: Int get() = endMinute - startMinute

    /**
     * 时间窗口；只在 [hasValidWindow] 为真时有意义。
     *
     * 用 `require` 而不是返回 null：调用方（[toLockRule]、界面格式化）
     * 只会对已经过仓库校验的规划调用它，拿到可空值反而到处要 `!!`。
     */
    val window: TimeWindow
        get() {
            require(hasValidWindow) { "规划的时间区间不合法：$invalidReason" }
            return TimeWindow(startMinute, endMinute)
        }

    /** [date] 这一天的第 [minuteOfDay] 分钟是否落在本规划内（半开区间 `[start, end)`）。 */
    fun covers(date: LocalDate, minuteOfDay: Int): Boolean =
        date == this.date && hasValidWindow && minuteOfDay in startMinute until endMinute

    /**
     * 把规划转成一条**运行时**锁机规则（不落库）。
     *
     * 锁机链路（[com.zilv.app.domain.lock.WindowScheduler]、拦截服务、
     * `AlarmPlanner`）全部建立在 [LockRule] 之上，所以规划要参与锁机
     * 只能以 [LockRule] 的形式出现；而它的真相来源始终是 `plans` 表。
     *
     * 关键约定：
     *  - `habitId = null`、`planId = id` —— 习惯编辑页按 `habitId` 取规则，
     *    因此规划规则**永远不会**混进习惯的锁机配置里
     *  - `windows = [本规划的时间窗]`、`lockDurationMinutes = 0` ——
     *    走旧式自选窗口，锁机时间就是规划时间（不是"打卡截止 + 时长"）
     *  - `checkInWindows = emptyList()` —— 规划没有"必须先打卡"的概念
     *  - `allowCheckInUnlock = false` —— 用户要求**解锁条件仅为到点解锁**：
     *    打卡对规划无效（`WindowScheduler.isValidCheckIn` 直接返回 false），
     *    只能等窗口自己走完
     *  - `startDate = endDate = date` —— 只在规划当天生效，不会天天重复
     *  - `id = 0` —— 这条规则**不落库**；需要标识来源请读 [planId]
     */
    fun toLockRule(): LockRule {
        require(isValid) { "规划不合法，无法转成锁机规则：${invalidReason}" }
        val w = window
        return LockRule(
            id = 0L,
            habitId = null,
            planId = id,
            name = name,
            enabled = true,
            startDate = date,
            endDate = date,
            daysOfWeek = emptySet(),
            checkInWindows = emptyList(),
            windows = listOf(w),
            lockDurationMinutes = 0,
            allowCheckInUnlock = false,
            message = "规划进行中：$name（$w）",
            sortOrder = 0
        )
    }

    companion object {
        const val MINUTE_MIN = 0

        /** 当天最后一分钟：23:59。**规划不允许跨 0 点**，所以上界就是它。 */
        const val MINUTE_MAX = 1439
    }
}
