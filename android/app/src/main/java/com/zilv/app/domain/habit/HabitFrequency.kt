package com.zilv.app.domain.habit

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 习惯的打卡频率。
 *
 * 对应 PRD §3.1「频率：每天 / 每周 N 次 / 自定义星期」。
 *
 * ## 版本沿革（很重要，决定了编解码要兼容什么）
 *
 * | 版本 | 变更 |
 * | --- | --- |
 * | v2.4 | 追加「每月 N 次」（`MonthlyTimes`，1..28，**v2.13 已删除**） |
 * | v2.13 | **删除「每月 N 次」**，改为「指定每月几号」（[SpecificMonthDays]，1..31 多选） |
 * | **v2.14** | **删除「每周 N 次」**（`WeeklyTimes`），只剩三种频率 |
 *
 * v2.13 的用户原话：「删除每月打卡多少次的选项，改为指定每月1-31号打卡，
 * 如果当月没有31号就跳过没有的号数，直接开始下一个月一号。」
 *
 * v2.14 的用户原话：「打卡频率里删除每周 N 次。」
 *
 * 因此现在有**三种**频率：每天 / **指定每月几号** / 指定星期。
 *
 * ## 「指定每周 N 次」为什么整类删除，而不是隐藏界面
 *
 * 它与「指定每月几号」是 v2.13 时同一类问题的两个实例：
 * 「N 次」约束的是**周期内的总量**，不指定具体哪天 —— 于是它需要一整套
 * 独立的"周期配额"算法（`countInWeek` / `currentWeekly` / `longestWeekly`），
 * 与"计划日集合"那套完全并行。用户删掉它之后，**剩下的三种频率都只回答一个问题**：
 * "今天是不是计划日、今天打了没有"（[Daily] 恒为计划日）。
 *
 * 因此 v2.14 顺带删掉了那套周维度算法 —— 不是"省事"，而是**避免两套语义分叉**。
 * 旧数据的迁移方式见 [HabitFrequencyCodec]。
 *
 * ## 「指定每月几号」与「指定星期」是**同构**的
 *
 * 两者都是"一些**计划日**的集合"，差别只在计划日的来源：
 *  - [SpecificDays]：计划日 = 选中的星期几（每周重复）
 *  - [SpecificMonthDays]：计划日 = 选中的几号（每月重复）
 *
 * 于是它们的三个方法逐行对应（[isScheduledOn] / [needsCheckInOn] /
 * [StreakCalculator] 里的连续天数算法），差别只有那一行判据：
 * `date.dayOfWeek in days` 与 `date.dayOfMonth in days`。
 * 这不是巧合 —— 刻意写成同构，让两套语义可以逐行对照，改一处不会漏另一处。
 */
sealed interface HabitFrequency {

    /** 每天 */
    data object Daily : HabitFrequency

    /**
     * **指定每月几号**（v2.13 取代 v2.4 的「每月 N 次」）。
     *
     * [days] 是 **1..31** 的子集，支持多选。语义：
     *  - 只有**选中那几号**需要打卡；其余日子不需要，也不影响连续天数
     *  - **当月没有这个日期就自然跳过**：选了 31 号而 4 月只有 30 天 ⇒ 4 月整月不需要打卡，
     *    下一次落在 5 月 31 日（见下面的"自然行为"一节）
     *  - 空集合 ⇒ 永远不是计划日（与 [SpecificDays] 的空集合一致）。
     *    界面在切到这个频率时会先给一个空集合，等用户点日期。
     *
     * ## 「当月没有该日期」为什么不需要特判
     *
     * 判据只依赖 [LocalDate.dayOfMonth]：`31 in days` 而 4 月的任何一天
     * `dayOfMonth` 最大是 30，于是整个 4 月 `isScheduledOn` 恒为假 ——
     * **跳过是自然发生的**，不需要写"这个月有没有 31 号"的分支，
     * 也就不会有"闰年 2 月 29 日算不算"这类边界 bug。
     * 这条用 [LocalDate] 的自然行为实现，并被单元测试钉死
     * （2026-02 只有 28 天、2026-04 只有 30 天、闰年 2028-02 有 29 天）。
     *
     * ## 为什么上限是 31，而不是「每月 N 次」那样的 28
     *
     * 两种频率的约束对象不同：
     *  - 「每月 N 次」约束的是**总量**，N 必须 ≤ 每月最少天数（28），
     *    否则 2 月永远打不达标（这正是它被删掉的原因之一）
     *  - 「指定每月几号」约束的是**日期**，31 号在 1/3/5/7/8/10/12 月都存在，
     *    其余月份只是"这个月没有这个计划日"，跳过即可 —— 不存在"永远不达标"
     *
     * @throws IllegalArgumentException 日期越界（不在 1..31）。与 v2.4 的
     *         `MonthlyTimes` 一样 **fail fast**：越界只可能是调用方算错或脏数据，
     *         静默夹取会让用户看到一条"31 号"的习惯却不知道自己的输入被改了。
     *         脏数据从数据库 / 备份里来时的策略是**在解码层过滤**
     *         （见 [HabitFrequencyCodec.decode]）。
     */
    data class SpecificMonthDays(val days: Set<Int>) : HabitFrequency {
        init {
            require(days.all { it in MIN_DAY..MAX_DAY }) {
                "每月日期必须在 $MIN_DAY..$MAX_DAY，实际 ${days.filterNot { it in MIN_DAY..MAX_DAY }}"
            }
        }

        /** 升序的日期列表，用于界面/备份展示（`1、15、31`）。 */
        fun sortedDays(): List<Int> = days.sorted()

        companion object {
            const val MIN_DAY = 1

            /** 一个月最多 31 天。 */
            const val MAX_DAY = 31
        }
    }

    /** 指定星期几 */
    data class SpecificDays(val days: Set<DayOfWeek>) : HabitFrequency

    /**
     * 这一天是否属于「计划日」。
     *
     * - [Daily]：每天都是计划日
     * - [SpecificDays]：仅选中的星期几
     * - [SpecificMonthDays]：仅选中的几号 —— **当月没有这个日期时自然为假**
     *   （4 月没有 31 号 ⇒ 4 月不是计划日，跳过）
     */
    fun isScheduledOn(date: LocalDate): Boolean = when (this) {
        is Daily -> true
        is SpecificDays -> date.dayOfWeek in days
        is SpecificMonthDays -> date.dayOfMonth in days
    }

    /**
     * 这一天是否**还需要**打卡。
     *
     * 与 [isScheduledOn]（"这一天算不算数"）不同，这里回答的是
     * "今天还欠不欠一次" —— 也就是今日列表该不该把这条习惯摆出来催。
     *
     * 各种频率的定义：
     *
     * | 频率 | 判定 |
     * | --- | --- |
     * | [Daily] | 今天还没打卡 |
     * | [SpecificDays] | 今天是计划日且今天还没打卡；非计划日不需要 |
     * | [SpecificMonthDays] | 今天是选中的日期且今天还没打卡；没选中的日子/当月没有的日子不需要 |
     *
     * [SpecificDays] 与 [SpecificMonthDays] 的最后一行是**逐字同构**的 ——
     * 只有计划日的判据不同（星期几 / 几号），这正是两者该有的关系。
     *
     * @param checkedDates 该习惯**全部**已打卡的日期（同一天多次打卡只算一天）。
     *        晚于 [date] 的脏数据会被忽略 —— 否则一条未来日期的记录就能
     *        提前把本周期"填满"，让今天不该催的习惯被跳过。
     *        （对 [SpecificMonthDays] 而言"晚于"还能挡住
     *        "未来某个同号日期把本月的今天提前打成已完成"。）
     */
    fun needsCheckInOn(date: LocalDate, checkedDates: Set<LocalDate>): Boolean = when (this) {
        is Daily -> date !in checkedDates
        is SpecificDays -> date.dayOfWeek in days && date !in checkedDates
        is SpecificMonthDays -> date.dayOfMonth in days && date !in checkedDates
    }
}

/**
 * 频率的持久化编解码。
 *
 * 对应 PRD §5 数据模型里的 `frequency_type` + `frequency_json` 两列。
 * 刻意不使用 JSON：纯字符串编码更容易做单元测试，也避免在 JVM 单测里依赖 Android 的 org.json。
 *
 * ## 编码格式
 *
 * | 频率 | frequency_type | frequency_json | 起始版本 | 现状 |
 * | --- | --- | --- | --- | --- |
 * | 每天 | `DAILY` | `""` | v1 | 读写 |
 * | 指定星期 | `DAYS` | `"1,3,5"`（ISO 星期序号，周一=1） | v1 | 读写 |
 * | 指定每月几号 | `MONTH_DAYS` | `"1,15,31"`（1..31，升序） | v2.13 | 读写 |
 * | ~~每周 N 次~~ | ~~`WEEKLY`~~ | ~~`"3"`~~ | v1 | **v2.14 起只读不写** |
 * | ~~每月 N 次~~ | ~~`MONTHLY`~~ | ~~`"10"`~~ | v2.4 | v2.13 起只读不写 |
 *
 * ## 向后兼容之一：旧的 `MONTHLY:n` 怎么读
 *
 * v2.13 删掉了「每月 N 次」，但**存量数据库和旧备份里一定有 `MONTHLY:n` 的行**。
 * 三条硬性要求：不能崩、不能悄悄变严、不能丢掉"每月 N 次"这个意图。
 *
 * 处理方式 —— **映射成 `MONTH_DAYS` 的 `1..n` 号**（见 [legacyMonthlyTimesToMonthDays]）：
 *
 * | 旧编码 | 新频率 | 说明 |
 * | --- | --- | --- |
 * | `MONTHLY:3` | 指定每月 1、2、3 号 | 一个月仍然是"做 3 次" |
 * | `MONTHLY:10` | 指定每月 1..10 号 | 同上，仍然是每月 10 次 |
 * | `MONTHLY:0` / `29` / `abc` / `""` | 每天 | 越界/脏数据，退化为每天（与 v2.4 的旧口径一致：那时这些值也退化为每天） |
 *
 * 为什么选 `1..n` 而不是别的映射：
 *  1. **次数不变**：旧语义是"每月做够 N 次"，新频率选 n 个日期正好是每月 n 次，
 *     用户的达标难度与打卡次数都没变（只是日子从"随便哪几天"固定成"1 号到 n 号"）
 *  2. **永远不会跳过**：v2.4 保证 n ≤ 28，而任何自然月都有 1..28 号，
 *     所以映射出来的计划日**在任何月份都排得下**，不会出现"某个月整月不打卡"
 *  3. 它是**确定且可测**的：同一条旧数据每次解码结果都一样，用户看到的界面文案
 *     会明确变成"每月 1、2、3 号"，一眼就知道自己的旧配置被改成了什么
 *
 * 刻意**不**选的两个方案，以及原因：
 *  - 退化为「每天」：那等于把用户的 3 次/月变成 30 次/月，**明显变严**，
 *    用户会突然天天被催（这是最坏的一种"没崩但坏了"）
 *  - 退化为「每周 N 次」：语义跨度太大（3 次/月 ≠ 3 次/周），属于悄悄改需求
 *
 * ## 向后兼容之二：旧的 `WEEKLY:n` 怎么读（v2.14）
 *
 * v2.14 删掉了「每周 N 次」，同样有存量数据。处理方式与上面**同一套取舍**：
 * **映射成 `DAYS` 的 周一..第 n 天**（见 [legacyWeeklyTimesToSpecificDays]）。
 *
 * | 旧编码 | 新频率 | 说明 |
 * | --- | --- | --- |
 * | `WEEKLY:3` | 指定星期「一、二、三」 | 一周仍然要打 3 次 |
 * | `WEEKLY:7` | 指定星期「一…日」 | 等价于每天 7 次 |
 * | `WEEKLY:0` / `8` / `abc` / `""` | 每天 | 越界/脏数据，退化为每天（与旧版口径一致） |
 *
 * 为什么选 `1..n`（周一..第 n 天）：
 *  1. **次数不变**：n 计划日 = 每周 n 次，用户的打卡负担与达标难度都没变
 *  2. **永远不会跳过**：任何自然周都有周一到周日 7 天，n ≤ 7 一定排得下
 *  3. 它是**确定且可测**的，界面上会直接显示"周一、周二、周三"，
 *     用户一眼就知道旧配置被改成了什么（而不是以为自己的习惯消失了）
 *
 * 刻意**不**选的方案：
 *  - 退化为「每天」：3 次/周 → 7 次/周，**明显变严**，是"没崩但坏了"
 *  - 丢弃这条习惯 / 让整页解析失败：真实用户数据，绝不能因为一个选项被删就丢
 *  - 反向映射成「指定每月几号」：3 次/周 ≠ 3 次/月（一个月大约 4 周），语义差 4 倍
 *
 * 反过来（旧版本 App 读到 `MONTH_DAYS`）走 `else` 分支退化为「每天」——
 * 宁可当作每天打卡，也不要崩溃或空白。
 */
object HabitFrequencyCodec {

    const val TYPE_DAILY = "DAILY"
    const val TYPE_DAYS = "DAYS"

    /** v2.13 新增：「指定每月几号」。 */
    const val TYPE_MONTH_DAYS = "MONTH_DAYS"

    /**
     * v1 的「每周 N 次」。**只用于解码旧数据，不再写入**。
     *
     * 保留这个常量是为了让"兼容"这件事在代码里有个名字 ——
     * 删掉它会让 [decode] 里那个分支变成魔法字符串，以后没人知道它为什么要留着。
     */
    const val TYPE_WEEKLY = "WEEKLY"

    /**
     * v2.4 的「每月 N 次」。**只用于解码旧数据，不再写入**。
     *
     * 保留这个常量是为了让"兼容"这件事在代码里有个名字 ——
     * 删掉它会让 [decode] 里那个分支变成魔法字符串，以后没人知道它为什么要留着。
     */
    const val TYPE_MONTHLY = "MONTHLY"

    /** 旧「每周 N 次」的合法区间（1..7，一周最多 7 天）。 */
    const val WEEKLY_TIMES_MIN = 1
    const val WEEKLY_TIMES_MAX = 7

    /** 每月日期的合法区间，与 [HabitFrequency.SpecificMonthDays] 保持同一个来源。 */
    const val MONTH_DAY_MIN = HabitFrequency.SpecificMonthDays.MIN_DAY
    const val MONTH_DAY_MAX = HabitFrequency.SpecificMonthDays.MAX_DAY

    fun typeOf(frequency: HabitFrequency): String = when (frequency) {
        is HabitFrequency.Daily -> TYPE_DAILY
        is HabitFrequency.SpecificDays -> TYPE_DAYS
        is HabitFrequency.SpecificMonthDays -> TYPE_MONTH_DAYS
    }

    fun payloadOf(frequency: HabitFrequency): String = when (frequency) {
        is HabitFrequency.Daily -> ""
        is HabitFrequency.SpecificDays ->
            frequency.days.sortedBy { it.value }.joinToString(",") { it.value.toString() }

        is HabitFrequency.SpecificMonthDays ->
            frequency.days.sorted().joinToString(",") { it.toString() }
    }

    /**
     * 解码。
     *
     * 对脏数据采取「不抛异常、退化为每天」的策略：数据库里出现无法解析的内容时，
     * 宁可把习惯当作每天打卡，也不要让整个列表页崩溃。
     *
     * 这个策略同时兜住了 [HabitFrequency.SpecificMonthDays] 的越界值：
     * `0` / `32` 会在过滤阶段被丢掉，因此**不会**在解码路径上撞到
     * `SpecificMonthDays` 构造函数里的 `require`。
     *
     * @param type `frequency_type` 列
     * @param payload `frequency_json` 列
     */
    fun decode(type: String, payload: String): HabitFrequency = when (type) {
        // ---- v2.14：只读的旧编码（见类 KDoc 的兼容表）----
        // v1 的「每周 N 次」：映射成「指定星期 周一..第 n 天」，次数不变、永不跳过
        TYPE_WEEKLY -> legacyWeeklyTimesToSpecificDays(payload)

        // ---- v2.13：只读的旧编码 ----
        TYPE_MONTHLY -> legacyMonthlyTimesToMonthDays(payload)

        TYPE_DAYS -> {
            val days = payload.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .map { DayOfWeek.of(it) }
                .toSet()
            if (days.isEmpty()) HabitFrequency.Daily else HabitFrequency.SpecificDays(days)
        }

        TYPE_MONTH_DAYS -> {
            val days = payload.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in MONTH_DAY_MIN..MONTH_DAY_MAX }
                .toSet()
            if (days.isEmpty()) {
                HabitFrequency.Daily
            } else {
                HabitFrequency.SpecificMonthDays(days)
            }
        }

        else -> HabitFrequency.Daily
    }

    /**
     * 把 v2.4 的 `MONTHLY:n`（每月 N 次）映射成 v2.13 的「指定每月几号」。
     *
     * 返回 `SpecificMonthDays(1..n)`；`n` 越界或不是数字时退化为 [HabitFrequency.Daily]。
     * 完整的理由见类 KDoc 里那张兼容表 —— 一句话：**次数不变、永不跳过、可测**。
     *
     * 上限用 [MONTH_DAY_MAX]（31）而不是 v2.4 的 28：解码器的职责是"别崩"，
     * 脏数据里出现 `MONTHLY:30` 时映射成 1..30 号仍然是个合法且有意义的频率
     * （30 号在 2 月不存在，但其余月份都在 —— 自然跳过）。
     */
    fun legacyMonthlyTimesToMonthDays(payload: String): HabitFrequency {
        val n = payload.trim().toIntOrNull() ?: return HabitFrequency.Daily
        if (n !in MONTH_DAY_MIN..MONTH_DAY_MAX) return HabitFrequency.Daily
        return HabitFrequency.SpecificMonthDays((1..n).toSet())
    }

    /**
     * 把 v1 的 `WEEKLY:n`（每周 N 次）映射成 v2.14 的「指定星期」。
     *
     * 返回 `SpecificDays(周一..第 n 天)`；`n` 越界或不是数字时退化为 [HabitFrequency.Daily]。
     * 完整理由见类 KDoc 里那张兼容表 —— 一句话：**次数不变、永不跳过、可测**。
     *
     * ISO 星期序号（周一=1 … 周日=7）与 `frequency_json` 里 `DAYS` 的编码完全一致，
     * 因此映射结果与"用户在界面上手选前 n 个星期"**逐字节相同**：
     * 同一条旧习惯迁移之后，`typeOf/payloadOf` 写出来就是 `DAYS` / `"1,2,3"`。
     */
    fun legacyWeeklyTimesToSpecificDays(payload: String): HabitFrequency {
        val n = payload.trim().toIntOrNull() ?: return HabitFrequency.Daily
        if (n !in WEEKLY_TIMES_MIN..WEEKLY_TIMES_MAX) return HabitFrequency.Daily
        return HabitFrequency.SpecificDays((1..n).map { DayOfWeek.of(it) }.toSet())
    }
}
