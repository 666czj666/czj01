package com.zilv.app.domain.lock

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 时间窗口，以「当天第几分钟」表示。
 *
 * - [startMinute] 含，[endMinute] 不含（半开区间）
 * - 支持跨零点（如 22:30–07:00）
 * - [endMinute] 允许取 1440 表示 24:00（全天窗口 0–1440）
 */
data class TimeWindow(
    val startMinute: Int,
    val endMinute: Int
) {
    init {
        require(startMinute in 0..1439) { "startMinute 必须在 0..1439，实际 $startMinute" }
        require(endMinute in 0..1440) { "endMinute 必须在 0..1440，实际 $endMinute" }
    }

    /** 是否跨零点。start == end 视为跨零点（等价于全天）。 */
    val crossesMidnight: Boolean get() = endMinute <= startMinute

    val durationMinutes: Int
        get() = if (crossesMidnight) (1440 - startMinute) + endMinute else endMinute - startMinute

    val start: LocalTime get() = LocalTime.ofSecondOfDay(startMinute * 60L)

    /** 24:00 用次日 00:00 表示。 */
    val end: LocalTime
        get() = if (endMinute == 1440) LocalTime.MIDNIGHT else LocalTime.ofSecondOfDay(endMinute * 60L)

    fun containsMinuteOfDay(minuteOfDay: Int): Boolean {
        require(minuteOfDay in 0..1439) { "minuteOfDay 必须在 0..1439，实际 $minuteOfDay" }
        return if (crossesMidnight) {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        } else {
            minuteOfDay >= startMinute && minuteOfDay < endMinute
        }
    }

    fun contains(time: LocalTime): Boolean = containsMinuteOfDay(time.hour * 60 + time.minute)

    /** 人类可读，如 `07:30-09:00`。 */
    override fun toString(): String =
        "%02d:%02d-%02d:%02d".format(
            startMinute / 60, startMinute % 60,
            (if (endMinute == 1440) 24 else endMinute / 60), endMinute % 60
        )

    companion object {
        fun of(start: LocalTime, end: LocalTime, endMeansMidnight: Boolean = false): TimeWindow {
            val s = start.hour * 60 + start.minute
            val e = if (endMeansMidnight && end == LocalTime.MIDNIGHT) {
                1440
            } else {
                end.hour * 60 + end.minute
            }
            return TimeWindow(s, e)
        }
    }
}

/**
 * 解锁方式 —— **已废弃，不再影响任何行为**。
 *
 * 用户明确要求（v2.2）：
 *  - 「锁机后的解锁方法 = 打卡（任何打卡方法都可以），或者过完锁机时间」
 *  - 「把习惯的锁机解锁方法和打卡方法统一 —— 打卡方法就是解锁方法」
 *
 * 于是原先那个由用户选择的「仅定时解锁 / 需地点打卡」二选一被取消：
 * 现在的语义是**任何打卡都能解锁**（见 [WindowScheduler.isValidCheckIn]），
 * 或者等锁机时长走完自然解除。打卡点归**习惯的打卡条件**
 * （`Habit.checkInCondition`）管，不归规则管。
 *
 * 枚举本身**保留**，理由只有一个：历史数据里 `lock_rules.unlock_mode`
 * 这一列存的就是它，映射层（`UnlockModeCodec`）还要能解码旧值 ——
 * 直接删枚举会让旧库读不出来，而这次改动刻意不动任何数据库列。
 *
 * 曾经出现过的第三种 `EITHER` 在解码时仍然映射到 [SPOT_CHECKIN]。
 */
enum class UnlockMode {
    /** 历史值：曾经的"仅定时解锁"。**现在与 [SPOT_CHECKIN] 行为完全相同。** */
    TIME_ONLY,

    /** 历史值：曾经的"需地点打卡解锁"。**现在与 [TIME_ONLY] 行为完全相同。** */
    SPOT_CHECKIN
}

/**
 * 锁机规则。
 *
 * ## v2.0 结构性调整：打卡时间与锁机时间分开
 *
 * 用户要求："把锁机时间和打卡时间分开设置，在打卡时间未能打卡才进行锁机，
 * 锁机时间也能打卡解锁，把锁机功能和习惯功能结合，删除单独锁机"。
 *
 * | 字段 | 含义 |
 * | --- | --- |
 * | [checkInWindows] | 必须打卡的时间段。没打上，到锁机时间就会被锁 |
 * | [windows]（别名 [lockWindows]） | 未打卡时的锁机时间段。**期间打卡仍可解锁** |
 * | [habitId] | 归属于哪个习惯；null 表示旧版遗留的独立规则 |
 *
 * ### 为什么保留 `lock_rules` 表而不是把字段塞进 `habits`
 *
 * 调度（[WindowScheduler]）、拦截（无障碍服务）、闹钟（[com.zilv.app.service.AlarmPlanner]）
 * 全都建立在 [LockRule] 之上。把锁机字段并进 `habits` 意味着同时重写这四条链路，
 * 风险远大于收益 —— 而"删除单独锁机"指的是**界面上不再有独立的锁机入口**，
 * 不是要求底层表结构合并。
 *
 * ### 字段名为什么没改
 *
 * `windows` 沿用旧名，是为了不动数据库列名与全部既有调用点。
 * **v2.1 起它是"旧式"字段**：新配置只让用户选打卡时间与锁机**时长**，
 * 锁机窗口由 [effectiveLockWindows] 推导。旧规则继续走 `windows`。
 */
data class LockRule(
    val id: Long = 0L,
    /**
     * 所属习惯；null 表示旧数据里的独立规则（仍可正常工作）。
     */
    val habitId: Long? = null,
    /**
     * 来源规划（`plans.id`）；null 表示这不是规划派生的规则。
     *
     * v2.4 新增。用户要求「明日规划」在规划时间内锁机，而锁机链路
     * （[WindowScheduler] / 拦截服务 / 闹钟）全部建立在 [LockRule] 之上，
     * 所以规划在**运行时**由 `Plan.toLockRule()` 转成一条规则参与调度。
     *
     * 它的作用就是**把规划规则与习惯规则区分开**：
     *  - 习惯编辑页按 `habitId` 取规则，规划规则的 `habitId` 恒为 null，
     *    因此**永远不会**出现在习惯的锁机配置里
     *  - 报告/日志要说明"这次锁机是谁造成的"时，能指回具体哪条规划
     *
     * 规划规则的真相来源始终是 `plans` 表（[planId] 只是运行时的来源标记），
     * 但这一列仍然落库 —— 理由见 [allowCheckInUnlock]。
     */
    val planId: Long? = null,
    val name: String,
    val enabled: Boolean = true,
    /** 生效日期区间，null 表示不限 */
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    /** 重复的星期。空集合视为每天。 */
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    /**
     * 必须打卡的时间段，**可以为空**。
     *
     * 为空表示"不强制打卡"：此时只要在锁机窗口内打卡就能解锁，
     * 行为与 v1.x 完全一致（向后兼容）。
     */
    val checkInWindows: List<TimeWindow> = emptyList(),
    /**
     * 未打卡时的锁机时间段。
     *
     * ⚠️ **v2.1 起这是"旧式"字段**：新配置不再让用户自选锁机时间，
     * 改为 [lockDurationMinutes]（打卡截止后锁多久）。见 [effectiveLockWindows]。
     * 保留它是为了**已有的旧规则继续生效**，不做数据迁移。
     */
    val windows: List<TimeWindow> = emptyList(),
    /**
     * 锁机时长（分钟），**上限 24 小时**。
     *
     * v2.1 新增（用户要求："不自选锁机时间删除，改为超过打卡时间之后锁机，
     * 锁机的自选时间开始改为自选时长，最长为 24 小时"）。
     *
     * 锁机从**打卡截止时刻**开始，持续这么久。0 表示不使用该模式（走旧式 [windows]）。
     */
    val lockDurationMinutes: Int = 0,
    /**
     * **已废弃**：见 [UnlockMode] 的 KDoc。保留它只是为了不动数据库列、
     * 让历史数据能原样读写；**不要再据此判断"打卡能不能解锁"** ——
     * 按用户要求，任何打卡都能解锁。
     */
    val unlockMode: UnlockMode = UnlockMode.TIME_ONLY,
    /**
     * 打卡能否解锁这条规则，**默认 true**。
     *
     * v2.4 新增，唯一的例外场景是「明日规划」：
     * 用户要求规划的**解锁条件仅为到点解锁**，所以 `Plan.toLockRule()`
     * 把它的规则设成 `false`；打卡（以及打卡窗口）对这类规则只算提示，
     * 见 [WindowScheduler.isValidCheckIn] / [WindowScheduler.checkInPhaseOf]。
     *
     * **默认 true 是向后兼容的关键**：所有既有习惯规则、以及历史数据里
     * 没有这一列的规则，读出来都是 true —— 也就是 v2.2 定下的
     * "任何打卡都能解锁"，习惯的锁机行为**一字未变**。
     */
    val allowCheckInUnlock: Boolean = true,
    /**
     * **已废弃**：打卡点现在归**习惯**的打卡条件（`Habit.checkInCondition`）管。
     *
     * 规则不再要求打卡点，[isValid] 也不再检查它。旧数据里的值原样保留，
     * 不做迁移，避免为了一个不再使用的字段动数据库。
     */
    val spotId: Long? = null,
    /**
     * 提前打卡有效期（分钟），**默认 0**。
     *
     * 相对于**打卡窗口的开始**（没有打卡窗口时相对锁机窗口开始）提前。
     */
    val earlyCheckInMinutes: Int = 0,
    /** 宽限期（分钟）：锁机窗口开始后多久才真正拦截，默认 0 */
    val graceMinutes: Int = 0,
    /** 严格模式：离开打卡点后重新进入拦截（默认关闭） */
    val strictMode: Boolean = false,
    val strictLeaveMinutes: Int = 10,
    /** 遮挡层文案 */
    val message: String = "",
    val sortOrder: Int = 0
) {
    /** 该日期是否是规则的生效日（星期 + 日期区间）。 */
    fun isActiveOn(date: LocalDate): Boolean {
        if (!enabled) return false
        if (startDate != null && date.isBefore(startDate)) return false
        if (endDate != null && date.isAfter(endDate)) return false
        if (daysOfWeek.isNotEmpty() && date.dayOfWeek !in daysOfWeek) return false
        return true
    }

    // ------------------------------------------------------------ 新字段的语义

    /**
     * 这条规则是否由「明日规划」派生（见 [planId]）。
     *
     * 界面据此把它排除在"习惯的锁机配置"之外；习惯规则恒为 false。
     */
    val isPlanRule: Boolean get() = planId != null

    /** `windows` 的语义别名：旧式自选锁机窗口。 */
    val lockWindows: List<TimeWindow> get() = windows

    /**
     * 由「打卡截止 + [lockDurationMinutes]」**推导**出的锁机窗口（v2.1 新式）。
     *
     * 用户要求"改为超过打卡时间之后锁机" —— 所以锁机起点不再自选，
     * 就是打卡截止时刻；用户能选的只有**时长**。
     */
    val derivedLockWindows: List<TimeWindow>
        get() {
            val deadline = checkInDeadlineMinute ?: return emptyList()
            if (lockDurationMinutes <= 0) return emptyList()
            // 打卡窗口可能刚好在 24:00 截止，而 startMinute 上限是 1439
            val start = deadline.coerceIn(0, 1439)
            val rawEnd = start + lockDurationMinutes
            return if (rawEnd <= 1440) {
                listOf(TimeWindow(start, rawEnd))
            } else {
                // 跨零点取模。时长为 1440（满 24 小时）时 rawEnd - 1440 == start，
                // 恰好落到 TimeWindow 的"起止相同＝全天"语义上，无需特判。
                listOf(TimeWindow(start, rawEnd - 1440))
            }
        }

    /**
     * **实际生效**的锁机窗口。
     *
     * - 新式规则（配了打卡时间 + 时长）：由推导得到
     * - 旧式规则（只有自选锁机窗口）：沿用 [windows]，行为与 v1.x 一致
     *
     * 调度器统一读这个属性，两种规则都能正确工作。
     */
    val effectiveLockWindows: List<TimeWindow>
        get() = if (usesDurationMode) derivedLockWindows else windows

    /** 是否使用 v2.1 的"打卡截止 + 时长"模式。 */
    val usesDurationMode: Boolean
        get() = hasCheckInWindow && lockDurationMinutes > 0

    /** 是否配置了"必须先打卡"的时间段。 */
    val hasCheckInWindow: Boolean get() = checkInWindows.isNotEmpty()

    /** 打卡窗口里最晚的截止分钟；没有打卡窗口时返回 null。 */
    val checkInDeadlineMinute: Int?
        get() = checkInWindows.maxOfOrNull { if (it.crossesMidnight) 1440 else it.endMinute }

    /** 生效锁机窗口里最早的开始分钟；没有锁机窗口时返回 null。 */
    val lockStartMinute: Int?
        get() = effectiveLockWindows.minOfOrNull { it.startMinute }

    /**
     * 当天"打卡通道"开启的最早分钟。
     *
     * 有打卡窗口时取最早的打卡窗口开始；没有时退回最早的锁机窗口开始 ——
     * 这样"锁机时间也能打卡解锁"在两种配置下都成立。
     */
    val checkInOpenMinute: Int?
        get() = checkInWindows.minOfOrNull { it.startMinute } ?: lockStartMinute

    /**
     * 锁机是否确实晚于打卡截止。
     *
     * 不满足时 [isValid] 为假 —— 因为"在打卡时间未能打卡才进行锁机"
     * 要求先有打卡的机会，再有锁机的后果。
     *
     * v2.1 的时长模式由定义保证成立（锁机起点就是打卡截止），直接返回 true。
     */
    val lockStartsAfterCheckIn: Boolean
        get() {
            if (usesDurationMode) return true
            val deadline = checkInDeadlineMinute ?: return true
            val lockStart = lockStartMinute ?: return true
            return lockStart >= deadline
        }

    /** 不满足约束时给用户看的原因；满足时返回 null。 */
    val lockWindowProblem: String?
        get() = if (lockStartsAfterCheckIn) {
            null
        } else {
            "锁机开始时间必须晚于打卡截止时间（当前打卡截止 ${
                checkInDeadlineMinute?.let { "%02d:%02d".format(it / 60, it % 60) }
            }，锁机从 ${
                lockStartMinute?.let { "%02d:%02d".format(it / 60, it % 60) }
            } 开始）"
        }

    /** 锁机时长的可读文案，如 "2 小时 30 分"。 */
    val lockDurationLabel: String
        get() = when {
            lockDurationMinutes <= 0 -> "未设置"
            lockDurationMinutes == MAX_LOCK_DURATION_MINUTES -> "24 小时"
            lockDurationMinutes % 60 == 0 -> "${lockDurationMinutes / 60} 小时"
            lockDurationMinutes < 60 -> "$lockDurationMinutes 分钟"
            else -> "${lockDurationMinutes / 60} 小时 ${lockDurationMinutes % 60} 分"
        }

    // ---------------------------------------------------------------- 校验

    /**
     * 规则本身是否自洽。
     *
     * v2.2 起**不再要求打卡点**：打卡点归习惯的打卡条件管（见 [LockRule.spotId] 的
     * 说明），而"打卡能不能解锁"也不再是规则上的开关 —— 任何打卡都能解锁。
     * 曾经的 `unlockMode.checkInUnlocks || spotId != null` 这一条因此被删除。
     */
    val isValid: Boolean
        get() = name.isNotBlank() &&
            effectiveLockWindows.isNotEmpty() &&
            lockStartsAfterCheckIn

    companion object {
        /** 锁机时长上限：24 小时（用户明确要求"最长为 24 小时"）。 */
        const val MAX_LOCK_DURATION_MINUTES = 1440
    }
}

/**
 * 锁机阶段的形态（PRD §5.1）。
 */
enum class LockPhase {
    /** 空闲 */
    IDLE,

    /** 预警中（锁机窗口开始前 T-15 分钟内，且尚未打卡） */
    WARN,

    /**
     * **待打卡**：正处于打卡窗口内且还没打卡。
     *
     * 此时**还没有锁机**，只是提醒该打卡了 —— 这是"在打卡时间未能打卡
     * 才进行锁机"这条语义在状态机上的体现。
     *
     * 与 [WARN] 的区别：WARN 是"快到锁机时间了"，本阶段是"打卡时间已经开始了"。
     */
    CHECKIN_DUE,

    /** 锁机中 */
    LOCKED,

    /** 本窗口已解锁（已打卡） */
    UNLOCKED
}
