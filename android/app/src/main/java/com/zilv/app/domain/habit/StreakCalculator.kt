package com.zilv.app.domain.habit

import java.time.LocalDate
import java.time.YearMonth

/**
 * 连续天数（Streak）计算。
 *
 * PRD §3.1：连续满足频率要求即 +1，中断归零。
 * 三种频率的「中断」定义：
 *
 * - **每天**：从今天往前逐日回溯，统计连续有打卡的天数。
 *   今天尚未打卡**不打断**（今天还没过完），从昨天继续算。
 * - **指定星期**（`SpecificDays`）：只考察计划日；非计划日跳过、不打断连续。
 *   今天若是计划日但尚未打卡，同样不打断。
 * - **指定每月几号**（`SpecificMonthDays`）：与「指定星期」**逐行同构** ——
 *   只考察计划日（＝选中的那几号），其余日子跳过、不打断。
 *
 * v2.14：**「每周 N 次」的周维度算法已随该频率一起删除**
 * （`currentWeekly` / `longestWeekly` / `MAX_LOOKBACK_WEEKS`）。
 * 用户原话是「打卡频率里删除每周 N 次」，而那条算法只服务它一个 ——
 * 留着就是一套没有调用方的第二套语义，迟早与"计划日"那套分叉。
 * 旧 `WEEKLY:n` 数据在解码时被映射成「指定星期」，因此仍然有确定的连续天数
 * （见 `HabitFrequencyCodec.legacyWeeklyTimesToSpecificDays`）。
 *
 * 边界（都被单元测试钉住）：
 *  - 中间任意一个**计划日**没打卡 → 连续归零
 *  - 总次数 [StreakStats.total] 与频率无关，仍是"有打卡的天数"
 *  - 晚于今天的脏数据一律丢弃（见 [sanitize]）
 *
 * 所有时间计算基于 [java.time.LocalDate]，与设备时区无关（由调用方决定"今天"是哪天），
 * 因此在 JVM 单元测试里可以完全确定地验证。
 *
 * ========================================================================
 * v2.13：「指定每月几号」的连续天数定义（**正式定义**，改实现前先读这一段）
 * ========================================================================
 *
 * 用户原话：「删除每月打卡多少次的选项，改为指定每月1-31号打卡，
 * 如果当月没有31号就跳过没有的号数，直接开始下一个月一号。」
 *
 * ## 定义
 *
 * **按「计划日」的连续性计算，单位是"计划日"，不是"天"，也不是"月"。**
 *
 * 1. **计划日**：`date.dayOfMonth ∈ 选中的日期集合`。没选中的日子不是计划日。
 * 2. **当月没有这个日期时，它在这个月里根本不是计划日** —— 这不需要特判：
 *    4 月任何一天的 `dayOfMonth` 最大是 30，而计划日集合是 `{31}`，
 *    于是整个 4 月 `isScheduledOn` 恒为假，**自然跳过**。
 *    也就是说"只选了 31 号"的习惯，在 4 月整月不需要打卡，
 *    连续天数从 3 月 31 日直接接到 5 月 31 日 —— 中间那一个月不扣分。
 * 3. **当前连续数**：从今天往前，按计划日倒序逐个数，
 *    每个计划日都打了卡就 +1；**遇到第一个没打卡的计划日就停**。
 * 4. **今天若是计划日但还没打卡，不算断**（今天还没过完）—— 与「每天」
 *    「指定星期」的既有口径完全一致；真正的打断发生在"上一个计划日"漏掉时。
 * 5. 非计划日（含"当月不存在的日期"）既不计数，也不打断。

 * ## 为什么与「指定星期」严格同构
 *
 * 两者都只是"计划日的集合"：一个按星期几定义，一个按几号定义。
 * 连续天数只依赖"计划日序列 + 每个计划日有没有打卡"，
 * 因此**同一套算法**同时服务两者（见 [currentScheduled] / [longestScheduled]），
 * 差别只有 `isScheduledOn` 那一行判据。这样两套语义不会分叉。
 *
 * 举两个例子（`days = {1, 15}`，即每月 1 号与 15 号）：
 *
 * | 打卡日期 | 今天 | 当前连续 | 为什么 |
 * | --- | --- | --- | --- |
 * | 10-01, 10-15 | 10-15 | 2 | 今天已打卡，再往前 10-01 也打了 |
 * | 10-01 | 10-15 | 1 | 今天是计划日但还没打卡 → 不打断，从 10-01 继续 |
 * | 10-15 | 10-15 | 1 | 上一个计划日 10-01 没打卡 → 断，只剩今天这 1 个 |
 * | 09-15, 10-01（`{1,15}`）| 10-15 | 3 | 计划日序列 09-15 → 10-01 → 10-15 全打了 |
 *
 * 只选 31 号时（`days = {31}`）：
 *
 * | 打卡日期 | 今天 | 当前连续 | 为什么 |
 * | --- | --- | --- | --- |
 * | 01-31, 03-31 | 04-10 | 2 | 4 月没有 31 号 → 4 月整月不是计划日，不打断 |
 * | 01-31, 03-31 | 05-31 | 2 | 5-31 是计划日但还没打卡 → 不打断，接 3-31 |
 * | 01-31, 03-31 | 04-01 | 2 | 同上（4-01 不是计划日的时候也不打断） |
 * | 01-31, 02-28（漏了 31 号该在的月份不存在 → 不扣分） | 03-31 | 2 | 2 月没有 31 号，不产生"漏打" |
 *
 * ⚠️ 注意最后一行揭示的关键性质：**"当月没有该日期"不会被记成漏打卡**。
 * 若把算法写成"按自然月算，这个月的所有选中日期都得打"，2 月就会变成
 * 一次无法完成的失败 —— 那正是用户明确要求的反面。
 */
object StreakCalculator {

    /** 回溯上限，防止脏数据（如空计划日集合）导致死循环。 */
    private const val MAX_LOOKBACK_DAYS = 365L * 20

    /**
     * 某一周的周一，作为"自然周"的键。
     *
     * v2.14 删掉「每周 N 次」之后，连续天数算法已经**不再用它**；
     * 它作为一条独立的日期工具保留下来（`records` 页的热力图有同一定义，
     * 两处都靠测试钉住"周一为起点"），这样"自然周起点"的定义在项目里
     * 不会因为某处顺手删掉而各写一遍。
     */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    /** 某个日期所在的自然月，作为"自然月"的键（与 [mondayOf] 对称）。 */
    fun monthOf(date: LocalDate): YearMonth = YearMonth.from(date)

    /**
     * 计算连续统计。
     *
     * @param checkedDates 有打卡记录的日期集合（同一天多次打卡只算一天）
     * @param today 判定基准日
     */
    fun stats(
        frequency: HabitFrequency,
        checkedDates: Set<LocalDate>,
        today: LocalDate
    ): StreakStats = StreakStats(
        current = current(frequency, checkedDates, today),
        longest = longest(frequency, checkedDates, today),
        total = sanitize(checkedDates, today).size
    )

    /** 当前连续数。 */
    fun current(
        frequency: HabitFrequency,
        checkedDates: Set<LocalDate>,
        today: LocalDate
    ): Int {
        val dates = sanitize(checkedDates, today)
        return when (frequency) {
            is HabitFrequency.Daily -> currentDaily(dates, today)
            // 「指定星期」与「指定每月几号」共用同一套算法（见 KDoc 的"严格同构"）
            is HabitFrequency.SpecificDays ->
                if (frequency.days.isEmpty()) 0 else currentScheduled(dates, today, frequency)

            is HabitFrequency.SpecificMonthDays ->
                if (frequency.days.isEmpty()) 0 else currentScheduled(dates, today, frequency)
        }
    }

    /** 历史最长连续数。 */
    fun longest(
        frequency: HabitFrequency,
        checkedDates: Set<LocalDate>,
        today: LocalDate
    ): Int {
        val dates = sanitize(checkedDates, today)
        if (dates.isEmpty()) return 0
        return when (frequency) {
            is HabitFrequency.Daily -> longestDaily(dates)
            is HabitFrequency.SpecificDays ->
                if (frequency.days.isEmpty()) 0 else longestScheduled(dates, frequency)

            is HabitFrequency.SpecificMonthDays ->
                if (frequency.days.isEmpty()) 0 else longestScheduled(dates, frequency)
        }
    }

    // ---------------------------------------------------------------- 每天

    private fun currentDaily(dates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in dates) today else today.minusDays(1)
        var streak = 0
        var steps = 0L
        while (cursor in dates && steps < MAX_LOOKBACK_DAYS) {
            streak++
            cursor = cursor.minusDays(1)
            steps++
        }
        return streak
    }

    private fun longestDaily(dates: Set<LocalDate>): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in dates.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }

    // -------------------------------------------- 计划日（指定星期 / 指定每月几号）
    //
    // ⚠️ 这两个函数同时服务「指定星期」与「指定每月几号」——
    // 两者唯一的差别就是 `frequency.isScheduledOn` 那一行判据
    // （`dayOfWeek in days` 与 `dayOfMonth in days`）。
    // 刻意不复制成两份：复制出来的两份迟早会分叉，而"连续天数"一旦两套口径不一致，
    // 用户会看到两条频率语义完全相同的习惯算出不同的连续天数。
    //
    // 日期逐日推进（而不是按周/按月跳），是因为"计划日"本身可能很稀疏
    // （只选 31 号 = 每 30/31 天才一个），按天推进既简单又不会漏，
    // 代价只是几十/几百次比较 —— 上限 [MAX_LOOKBACK_DAYS] 兜住脏数据。

    private fun currentScheduled(
        dates: Set<LocalDate>,
        today: LocalDate,
        frequency: HabitFrequency
    ): Int {
        var cursor = today
        // 今天若是计划日但还没打卡，不打断连续，从昨天继续判定
        if (frequency.isScheduledOn(cursor) && cursor !in dates) {
            cursor = cursor.minusDays(1)
        }

        var streak = 0
        var steps = 0L
        while (steps < MAX_LOOKBACK_DAYS) {
            if (frequency.isScheduledOn(cursor)) {
                if (cursor in dates) {
                    streak++
                    cursor = cursor.minusDays(1)
                } else {
                    break
                }
            } else {
                // 非计划日：跳过，不打断也不计数。
                // "当月没有这个日期"落到的就是这一支 —— 自然跳过。
                cursor = cursor.minusDays(1)
            }
            steps++
        }
        return streak
    }

    private fun longestScheduled(
        dates: Set<LocalDate>,
        frequency: HabitFrequency
    ): Int {
        val first = dates.min()
        val last = dates.max()
        var best = 0
        var run = 0
        var cursor = first
        var steps = 0L
        while (!cursor.isAfter(last) && steps < MAX_LOOKBACK_DAYS) {
            if (frequency.isScheduledOn(cursor)) {
                if (cursor in dates) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            cursor = cursor.plusDays(1)
            steps++
        }
        return best
    }

    // ---------------------------------------------------------------- 工具

    /** 丢弃晚于 [today] 的脏数据（时钟回拨等异常情况下可能出现）。 */
    private fun sanitize(checkedDates: Set<LocalDate>, today: LocalDate): Set<LocalDate> =
        checkedDates.filterTo(HashSet()) { !it.isAfter(today) }
}

/** 连续天数统计结果。 */
data class StreakStats(
    val current: Int,
    val longest: Int,
    val total: Int
) {
    companion object {
        val EMPTY = StreakStats(0, 0, 0)
    }
}
