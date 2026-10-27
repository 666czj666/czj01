package com.zilv.app.domain.lock

import java.time.LocalDateTime

/**
 * 「即时锁机」（v2.12）—— **纯函数**，可单元测试。
 *
 * ## 用户要求
 *
 * > 「添加即时锁机功能，锁机可选时间从 0 分到 12 小时。入口放在今天界面的最上方。」
 *
 * ## 为什么它是一个「普通规则」而不是一套新机制
 *
 * 锁机链路（[WindowScheduler] 状态判定 → 前台服务通知 →
 * [com.zilv.app.service.LockAccessibilityService] 拦截 → `AlarmScheduler` 到点唤醒）
 * **全部建立在 [LockRule] 之上**，而且它们的真相来源就是 `lock_rules` 表
 * （见 `LockRuleRepository.getEnabledRules` / `currentStatus`）。
 *
 * 所以即时锁机做成"**往 `lock_rules` 里插一条今天生效的规则**"就自动接上了
 * 整条链路，一行拦截代码都不用新写 —— 用户也点名要求
 * 「复用既有的锁机链路，不要另做一套拦截」。
 *
 * ## 不新增数据库列/表
 *
 * 规则用**旧式自选窗口**（`windows`）表达"从此刻到某个时刻"，
 * 而不是"打卡截止 + 时长"（那需要打卡窗口，语义也不对）。
 * 所有用到的列在 v10 的 `lock_rules` 上本来就有，因此
 * **数据库版本保持 10、不需要任何迁移**。
 *
 * ## 「从 0 分到 12 小时」，0 分怎么讲得通
 *
 * [TimeWindow] 的粒度是**分钟**，而且 `start == end` 会被当成"全天"
 * （跨零点语义）—— 那正是最危险的形态：本想锁 0 分钟，结果锁了一整天。
 * 所以这里显式取 `max(1, minutes)`：
 *
 * > **0 分 = 立刻锁机，到最近一次刷新点（下一个整分钟）自动解除。**
 *
 * 这句话正是用户自己给的边界描述（"立刻锁、等刷新前不锁"）：锁**立刻**生效，
 * 而刷新点一到就不锁了。于是 0 分不是"什么都不做"（那没有意义），
 * 而是"最短的一次锁机"（最多 60 秒）。
 *
 * 用户说过"如果 0 分在语义上讲不通，说明理由并给个最小值" ——
 * 理由与最小值都在上面：粒度是分钟，最小值就是 1 分钟，
 * 而 0 分被**定义**成这个最小值（保留这一档，因为用户明确要求允许 0 分）。
 *
 * ## 解锁方式：**仅到点解锁**
 *
 * 用户倾向"仅到点解锁"，本实现照此执行：生成的规则
 * [LockRule.allowCheckInUnlock] = `false`。
 *
 * 理由：即时锁机是"**我主动锁的**"，如果打卡就能解开，那它随手就能绕过，
 * 锁着也没有意义（这跟"习惯的锁机"不一样 —— 那里的打卡是完成任务本身）。
 * 这条与「明日规划」用的是**同一个**开关、同一条语义
 * （见 `Plan.toLockRule`），没有引入新概念。
 *
 * ⚠️ 反过来，[com.zilv.app.ui.today.TodayViewModel] 也会因此**不给**
 * 「立即打卡解锁」那个按钮（`允许打卡解锁` 为假时不提供入口）——
 * 否则界面上会挂着一个按了没用的按钮。
 */
object InstantLock {

    /** 规则名。它同时是**识别即时锁机的标记**（见 [isInstantLock]）。 */
    const val NAME = "即时锁机"

    /** 时长下限：**0 分**（用户明确要求允许，语义见 KDoc）。 */
    const val MIN_MINUTES = 0

    /** 时长上限：**12 小时**（用户要求"从 0 分到 12 小时"）。 */
    const val MAX_MINUTES = 12 * 60

    /**
     * 对话框里的快捷档位（分钟）。
     *
     * 用户建议：「15 分 / 30 分 / 1 小时 / 2 小时 / 4 小时 / 8 小时 / 12 小时」
     * ＋可自填。0 分单独做一个档位（它不是"没有时长"，见 KDoc）。
     */
    val PRESETS: List<Int> = listOf(15, 30, 60, 120, 240, 480, MAX_MINUTES)

    /** 把任意输入夹进合法区间（界面上"自填小时/分钟"那条路要用）。 */
    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** 可读时长，如 `30 分钟` / `2 小时` / `12 小时`；0 分单独说清它的含义。 */
    fun label(minutes: Int): String = when (val m = clampMinutes(minutes)) {
        0 -> "0 分（立刻锁，到下一个整分钟解除）"
        in 1..59 -> "$m 分钟"
        else -> if (m % 60 == 0) "${m / 60} 小时" else "${m / 60} 小时 ${m % 60} 分"
    }

    /**
     * 把"此刻"与时长换算成锁机窗口。
     *
     * ## 跨零点
     *
     * `rawEnd > 1440` 时按 [TimeWindow] 的跨零点语义取模 ——
     * 与 [LockRule.derivedLockWindows] 是**同一套**写法（口径不许分叉）：
     * 20:00 锁 12 小时 → `TimeWindow(1200, 480)` = 次日 08:00 结束。
     *
     * ## 边界
     *
     *  - `minutes = 0` → 取 `start + 1`（见 KDoc 里 0 分的定义）
     *  - 23:59 起锁 → `rawEnd` 可能超过 1440，取模后落到次日；
     *    最极端的情况（23:59 锁 12 小时）也不过是次日 11:59，绝不会退化成"全天"
     *
     * @param nowMinuteOfDay 此刻是当天的第几分钟（`hour * 60 + minute`）
     */
    fun windowAt(nowMinuteOfDay: Int, minutes: Int): TimeWindow {
        val start = nowMinuteOfDay.coerceIn(0, 1439)
        val duration = clampMinutes(minutes).coerceAtLeast(1)
        val rawEnd = start + duration
        return if (rawEnd <= MINUTES_PER_DAY) {
            TimeWindow(start, rawEnd)
        } else {
            TimeWindow(start, rawEnd - MINUTES_PER_DAY)
        }
    }

    /**
     * 为 [now] 这个时刻、[minutes] 这个时长生成一条**运行时/可落库**的规则。
     *
     * @return 一条 `id = 0` 的规则（落库由
     *        `LockRuleRepository.startInstantLock` 负责）
     */
    fun buildRule(now: LocalDateTime, minutes: Int): LockRule {
        val today = now.toLocalDate()
        return LockRule(
            id = 0L,
            // 即时锁机不属于任何习惯、也不来自任何规划 —— 这两个字段都为 null，
            // 于是它绝不会混进"习惯的锁机配置"或"规划的规则"里。
            habitId = null,
            planId = null,
            name = NAME,
            enabled = true,
            // 只在今天生效。跨零点的那一段由 WindowScheduler 的
            // "前一天开始、延续到今天"那条路径覆盖（它按**归属日**展开窗口）。
            startDate = today,
            endDate = today,
            daysOfWeek = emptySet(),
            // 没有"必须先打卡"的窗口：即时锁机跟打卡无关
            checkInWindows = emptyList(),
            windows = listOf(windowAt(now.hour * 60 + now.minute, minutes)),
            // 走旧式自选窗口（不是"打卡截止 + 时长"）
            lockDurationMinutes = 0,
            // ⚠️ 仅到点解锁 —— 打卡对它完全无效（用户倾向 + 本实现的选择）
            allowCheckInUnlock = false,
            message = "即时锁机 · ${label(minutes)}",
            sortOrder = INSTANT_LOCK_SORT_ORDER
        )
    }

    /**
     * 把对话框里"小时 + 分钟"两个输入框合成总分钟数。
     *
     * 与 `QuotaViewModel.limitMinutesOf` 同一套输入规则（用户会打出来的怪输入
     * 就那几种），但**上下限不同**，所以不共用那一个函数：
     *  - 非数字一律忽略（正在删字符时输入框可能是空串）
     *  - 空串按 0 算
     *  - 分钟栏超过 59 按 59 算
     *  - 合计超过 [MAX_MINUTES] **夹到上限**而不是报错
     *
     * 「0 分也要能选」在这里同样成立：小时与分钟都留空就是 0 分。
     */
    fun minutesOf(hours: String, minutes: String): Int {
        val h = hours.filter { it.isDigit() }.take(3).toLongOrNull() ?: 0L
        val m = (minutes.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0).coerceIn(0, 59)
        return clampMinutes((h * 60L + m).coerceAtMost(MAX_MINUTES.toLong()).toInt())
    }

    /** 一天有多少分钟（`24:00` 的分钟表示）。 */
    private const val MINUTES_PER_DAY = 1440

    /**
     * 即时锁机规则的 [LockRule.sortOrder] 哨兵值。
     *
     * 它同时是"这条规则是即时锁机"的**机器可读标记**
     * （见 [isInstantLock]）。选这个字段是因为当前代码库里
     * `sortOrder` 只被原样读写、**没有任何地方拿它排序或展示**，
     * 而"加一列/建一张表"会违反"数据库版本保持 10"这条硬约束。
     */
    const val INSTANT_LOCK_SORT_ORDER = -1
}

/**
 * 这条规则是不是一条「即时锁机」（用户主动锁的那把临时锁）。
 *
 * 判据是三个**机器可读**特征的合取，不依赖任何界面文案：
 *  - [LockRule.sortOrder] = 哨兵值 [InstantLock.INSTANT_LOCK_SORT_ORDER]
 *  - 不挂在习惯上、也不来自规划（两个来源字段都为 null）
 *  - 只在一天之内生效（`startDate == endDate`）
 *
 * 为什么要能识别它：`LockRuleRepository.startInstantLock` 要顺手清掉
 * **已经结束**的即时锁规则（否则每锁一次就在库里留一行）。
 */
val LockRule.isInstantLock: Boolean
    get() = sortOrder == InstantLock.INSTANT_LOCK_SORT_ORDER &&
        habitId == null &&
        planId == null &&
        startDate != null &&
        startDate == endDate
