package com.zilv.app.domain.lock

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 锁机窗口调度（PRD §3.2 / §5）。
 *
 * 纯函数实现，不依赖任何 Android API，因此可以在 JVM 上确定性地做单元测试。
 * 这是本项目最容易算错的一块逻辑（跨零点、多窗口、多规则叠加、宽限期、
 * 提前打卡有效期、打卡窗口与锁机窗口的组合），所以先用测试把语义钉死。
 *
 * ## v2.0：打卡时间与锁机时间分开
 *
 * | 场景 | 阶段 |
 * | --- | --- |
 * | 在锁机窗口内，未打卡（超过宽限） | [LockPhase.LOCKED] |
 * | 在锁机窗口内，未打卡（宽限内） | [LockPhase.WARN] |
 * | 在锁机窗口内，已打卡 | [LockPhase.UNLOCKED] |
 * | 在打卡窗口内，未打卡 | [LockPhase.CHECKIN_DUE] |
 * | 在打卡窗口内，已打卡 | [LockPhase.UNLOCKED] |
 * | 不在窗口内，但 15 分钟内要开始锁机且未打卡 | [LockPhase.WARN] |
 * | 其它 | [LockPhase.IDLE] |
 *
 * **向后兼容**：`checkInWindows` 为空时，行为与 v1.x 完全一致 ——
 * 打卡窗口的判定整体不参与，锁机窗口按旧规则走各自的有效期。
 *
 * ## v2.2：打卡即解锁
 *
 * 用户要求"锁机后的解锁方法 = 打卡（任何打卡方法都可以），或者过完锁机时间"，
 * 于是解锁**不再有开关**：任何一次有效打卡都会把窗口判为 [LockPhase.UNLOCKED]，
 * 不打卡则等窗口自然走完。原先按 `unlockMode` 区分"打卡有没有用"的两处特判
 * （[isValidCheckIn] 与 [checkInPhaseOf]）已删除。
 *
 * ## v2.4：唯一例外是"明日规划"
 *
 * 用户要求规划的解锁条件**仅为到点解锁**，于是重新引入一个**单一方向**的开关：
 * [LockRule.allowCheckInUnlock]（默认 true）。只有显式设为 false 的规则
 * （由 `Plan.toLockRule()` 生成）会让打卡失效；习惯规则因为它默认为 true，
 * 行为与 v2.2 完全一致。
 */
object WindowScheduler {

    /** 预警提前量（PRD：T-15 分钟） */
    const val WARN_LEAD_MINUTES = 15L

    /** 窗口种类。 */
    enum class WindowKind {
        /** 必须打卡的时间段 */
        CHECK_IN,

        /** 未打卡时的锁机时间段 */
        LOCK
    }

    /** 某次窗口的具体实例。 */
    data class WindowInstance(
        val rule: LockRule,
        val window: TimeWindow,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val kind: WindowKind = WindowKind.LOCK
    ) {
        /** 归属日 = 窗口**开始**那天（PRD：跨零点按开始日归属，用于连续天数统计）。 */
        val ownerDate: LocalDate get() = start.toLocalDate()
    }

    /** 综合状态。 */
    data class LockStatus(
        val phase: LockPhase,
        val rule: LockRule? = null,
        val instance: WindowInstance? = null,
        /** 距离下一次状态变化还有多少分钟（IDLE 时指向下一个窗口开始） */
        val minutesUntilChange: Long = 0L
    ) {
        companion object {
            val IDLE = LockStatus(LockPhase.IDLE)
        }
    }

    // ---------------------------------------------------------------- 窗口展开

    private fun instanceOf(
        rule: LockRule,
        window: TimeWindow,
        date: LocalDate,
        kind: WindowKind
    ): WindowInstance {
        val dayStart = date.atStartOfDay()
        val start = dayStart.plusMinutes(window.startMinute.toLong())
        val end = if (window.crossesMidnight) {
            dayStart.plusDays(1).plusMinutes(window.endMinute.toLong())
        } else {
            dayStart.plusMinutes(window.endMinute.toLong())
        }
        return WindowInstance(rule = rule, window = window, start = start, end = end, kind = kind)
    }

    /**
     * 某条规则在 [date]（归属日）当天开始的全部**锁机**窗口实例。
     * 不满足生效条件（未启用 / 星期不符 / 超出日期区间）时返回空列表。
     *
     * 读的是 [LockRule.effectiveLockWindows]：v2.1 的"打卡截止 + 时长"模式
     * 由此推导，旧式自选窗口原样沿用 —— 调度器不必区分两种规则。
     */
    fun instancesStartingOn(rule: LockRule, date: LocalDate): List<WindowInstance> {
        if (!rule.isActiveOn(date)) return emptyList()
        return rule.effectiveLockWindows.map { instanceOf(rule, it, date, WindowKind.LOCK) }
    }

    /**
     * 某条规则在 [date] 当天开始的全部**打卡**窗口实例。
     *
     * 未配置打卡窗口时返回空列表 —— 这正是向后兼容的开关。
     */
    fun checkInInstancesStartingOn(rule: LockRule, date: LocalDate): List<WindowInstance> {
        if (!rule.isActiveOn(date)) return emptyList()
        return rule.checkInWindows.map { instanceOf(rule, it, date, WindowKind.CHECK_IN) }
    }

    /**
     * [at] 时刻正在生效的**锁机**窗口。
     *
     * 必须同时考察"当天开始的窗口"和"前一天开始、跨零点延续到今天的窗口"。
     */
    fun activeWindows(rules: List<LockRule>, at: LocalDateTime): List<WindowInstance> {
        val dates = listOf(at.toLocalDate().minusDays(1), at.toLocalDate())
        return rules.flatMap { rule ->
            dates.flatMap { date -> instancesStartingOn(rule, date) }
        }.filter { !at.isBefore(it.start) && at.isBefore(it.end) }
            .sortedBy { it.start }
    }

    /** [at] 时刻正在生效的**打卡**窗口。 */
    fun activeCheckInWindows(rules: List<LockRule>, at: LocalDateTime): List<WindowInstance> {
        val dates = listOf(at.toLocalDate().minusDays(1), at.toLocalDate())
        return rules.flatMap { rule ->
            dates.flatMap { date -> checkInInstancesStartingOn(rule, date) }
        }.filter { !at.isBefore(it.start) && at.isBefore(it.end) }
            .sortedBy { it.start }
    }

    /** 处于预警区间（锁机窗口开始前 [WARN_LEAD_MINUTES] 分钟内）的窗口。 */
    fun warnings(rules: List<LockRule>, at: LocalDateTime): List<WindowInstance> {
        val dates = listOf(at.toLocalDate(), at.toLocalDate().plusDays(1))
        return rules.flatMap { rule -> dates.flatMap { d -> instancesStartingOn(rule, d) } }
            .filter {
                val warnStart = it.start.minusMinutes(WARN_LEAD_MINUTES)
                !at.isBefore(warnStart) && at.isBefore(it.start)
            }
            .sortedBy { it.start }
    }

    /**
     * 下一个将要开始的锁机窗口（在 [at] 之后）。
     *
     * [horizonDays] 内找不到就返回 null。
     */
    fun next(rules: List<LockRule>, at: LocalDateTime, horizonDays: Long = 21L): WindowInstance? {
        val dates = (0..horizonDays).map { at.toLocalDate().plusDays(it) }
        return rules.flatMap { rule -> dates.flatMap { d -> instancesStartingOn(rule, d) } }
            .filter { it.start.isAfter(at) }
            .minByOrNull { it.start }
    }

    // ---------------------------------------------------------------- 打卡有效性

    /**
     * 某个**锁机窗口**对应的"打卡通道开启时刻"。
     *
     * - 配置了打卡窗口：取该归属日上**最早的打卡窗口开始** ——
     *   在打卡窗口里打的卡，对当天所有锁机窗口都算数（"未打卡才锁机"）
     * - 没配打卡窗口：退回**本锁机窗口自身的开始** ——
     *   这样每个窗口各自独立计有效期，与 v1.x 行为完全一致
     */
    fun checkInOpenMoment(instance: WindowInstance): LocalDateTime {
        val earliestCheckIn = instance.rule.checkInWindows.minOfOrNull { it.startMinute }
        return if (earliestCheckIn != null) {
            instance.ownerDate.atStartOfDay().plusMinutes(earliestCheckIn.toLong())
        } else {
            instance.start
        }
    }

    /**
     * 打卡时刻是否对本锁机窗口有效。
     *
     * 有效区间 = [打卡通道开启 - 提前打卡有效期, ...]。
     *
     * **v2.2：任何打卡都能解锁。** 用户明确要求"锁机后的解锁方法 = 打卡
     * （任何打卡方法都可以），或者过完锁机时间"，因此原先那道
     * `unlockMode.checkInUnlocks` 的门被删掉了 —— 打卡点归**习惯的打卡条件**
     * 管，规则不再决定打卡是否管用。
     *
     * **"锁机时间也能打卡解锁"** 就体现在这里：打卡窗口不是唯一入口，
     * 只要打卡通道已经开启，在锁机窗口内打的卡同样有效。
     *
     * ## v2.4：`allowCheckInUnlock == false` 是唯一的例外
     *
     * 用户要求「明日规划」的解锁条件**仅为到点解锁**（见 `Plan.toLockRule()`）。
     * 这类规则直接返回 false —— 打卡一律无效，只能等窗口自己走完。
     *
     * 默认值是 true，所以**习惯的锁机行为完全不变**；这里刻意不叫"规划规则"
     * 而叫"不允许打卡解锁"，是因为调度器不该知道业务来源，只认规则本身的语义。
     */
    fun isValidCheckIn(instance: WindowInstance, checkInAt: LocalDateTime?): Boolean {
        if (checkInAt == null) return false

        // 规划这类"只能到点解锁"的规则：打卡（以及提前打卡有效期）完全不参与
        if (!instance.rule.allowCheckInUnlock) return false

        val open = checkInOpenMoment(instance)
        val earliest = open.minusMinutes(instance.rule.earlyCheckInMinutes.toLong())
        if (checkInAt.isBefore(earliest)) return false

        return if (instance.rule.hasCheckInWindow) {
            // 有打卡窗口：通道开启之后打的卡都算（含锁机窗口内打的）
            true
        } else {
            // 旧语义：本次锁机窗口结束前打卡才算
            checkInAt.isBefore(instance.end)
        }
    }

    // ---------------------------------------------------------------- 阶段判定

    /**
     * 某个**锁机窗口**在 [at] 时刻的阶段。
     *
     * @param checkInAt 本规则**当天最近一次**打卡时刻
     */
    fun phaseOf(
        instance: WindowInstance,
        at: LocalDateTime,
        checkInAt: LocalDateTime?
    ): LockPhase {
        if (at.isBefore(instance.start) || !at.isBefore(instance.end)) return LockPhase.IDLE

        // 宽限期内仍属"已到点但还没真正拦截"，对外表现为预警
        val lockStart = instance.start.plusMinutes(instance.rule.graceMinutes.toLong())
        if (at.isBefore(lockStart)) return LockPhase.WARN

        if (isValidCheckIn(instance, checkInAt)) return LockPhase.UNLOCKED

        return LockPhase.LOCKED
    }

    /**
     * 某个**打卡窗口**在 [at] 时刻的阶段。
     *
     * @return 已打卡返回 [LockPhase.UNLOCKED]，否则返回 [LockPhase.CHECKIN_DUE]
     *
     * v2.2：删掉了对 `unlockMode == TIME_ONLY` 的特判 ——
     * 用户要求"打卡方法就是解锁方法"，所以任何规则的打卡窗口里
     * 打过卡都一律 [LockPhase.UNLOCKED]。
     *
     * v2.4：`allowCheckInUnlock == false` 的规则（规划）例外 ——
     * 打卡对它**不解锁**，所以窗口里一律只算**提示**（[LockPhase.CHECKIN_DUE]），
     * 与"这个窗口只是提醒，真正的解锁条件是等窗口结束"一致。
     */
    fun checkInPhaseOf(
        instance: WindowInstance,
        at: LocalDateTime,
        checkInAt: LocalDateTime?
    ): LockPhase {
        if (!instance.rule.allowCheckInUnlock) return LockPhase.CHECKIN_DUE

        val open = instance.ownerDate.atStartOfDay()
            .plusMinutes(instance.window.startMinute.toLong())
        val earliest = open.minusMinutes(instance.rule.earlyCheckInMinutes.toLong())
        val satisfied = checkInAt != null &&
            !checkInAt.isBefore(earliest) &&
            !checkInAt.isAfter(at)
        return if (satisfied) LockPhase.UNLOCKED else LockPhase.CHECKIN_DUE
    }

    /**
     * 多规则叠加后的综合状态。
     *
     * 优先级：LOCKED > WARN > CHECKIN_DUE > UNLOCKED > IDLE（拦是主目标）。
     */
    fun statusFor(
        rules: List<LockRule>,
        at: LocalDateTime,
        checkInAtByRule: Map<Long, LocalDateTime?> = emptyMap()
    ): LockStatus {
        val lockResolved = activeWindows(rules, at).map { inst ->
            inst to phaseOf(inst, at, checkInAtByRule[inst.rule.id])
        }

        lockResolved.firstOrNull { it.second == LockPhase.LOCKED }?.let { (inst, _) ->
            return LockStatus(
                phase = LockPhase.LOCKED,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, inst.end)
            )
        }

        lockResolved.firstOrNull { it.second == LockPhase.WARN }?.let { (inst, _) ->
            val lockStart = inst.start.plusMinutes(inst.rule.graceMinutes.toLong())
            return LockStatus(
                phase = LockPhase.WARN,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, lockStart)
            )
        }

        // 锁机窗口内已打卡
        lockResolved.firstOrNull { it.second == LockPhase.UNLOCKED }?.let { (inst, _) ->
            return LockStatus(
                phase = LockPhase.UNLOCKED,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, inst.end)
            )
        }

        // 正在打卡窗口内：还没打就提示该打了（此时**尚未锁机**）
        val checkInResolved = activeCheckInWindows(rules, at).map { inst ->
            inst to checkInPhaseOf(inst, at, checkInAtByRule[inst.rule.id])
        }
        checkInResolved.firstOrNull { it.second == LockPhase.UNLOCKED }?.let { (inst, _) ->
            return LockStatus(
                phase = LockPhase.UNLOCKED,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, inst.end)
            )
        }
        checkInResolved.firstOrNull { it.second == LockPhase.CHECKIN_DUE }?.let { (inst, _) ->
            return LockStatus(
                phase = LockPhase.CHECKIN_DUE,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, inst.end)
            )
        }

        // 当前没有生效窗口：考察即将开始的锁机窗口
        val upcoming = warnings(rules, at)

        // PRD 状态流转：「窗口开始前已完成有效打卡 → UNLOCKED」
        upcoming.firstOrNull { isValidCheckIn(it, checkInAtByRule[it.rule.id]) }?.let { inst ->
            return LockStatus(
                phase = LockPhase.UNLOCKED,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, inst.end)
            )
        }

        upcoming.firstOrNull()?.let { inst ->
            return LockStatus(
                phase = LockPhase.WARN,
                rule = inst.rule,
                instance = inst,
                minutesUntilChange = minutesBetween(at, inst.start)
            )
        }

        // 空闲：给出下一个锁机窗口还有多久
        val nextInstance = next(rules, at)
        return LockStatus(
            phase = LockPhase.IDLE,
            rule = nextInstance?.rule,
            instance = nextInstance,
            minutesUntilChange = nextInstance?.let { minutesBetween(at, it.start) } ?: 0L
        )
    }

    private fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Long =
        Duration.between(from, to).toMinutes().coerceAtLeast(0L)
}
