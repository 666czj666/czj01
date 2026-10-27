package com.zilv.app.domain.habit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * [StreakCalculator] 的单元测试。
 *
 * 日期基准（已核对）：2026-10-05 是**周一**。
 * 因此 10-05 周一、10-07 周三、10-09 周五、10-12 周一、10-14 周三、10-16 周五。
 */
class StreakCalculatorTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    private fun dates(vararg s: String): Set<LocalDate> = s.map { d(it) }.toSet()

    private val today = d("2026-10-14") // 周三

    // ------------------------------------------------------------------ 每天

    @Test
    fun `每天 - 连续三天含今天_当前连续为3`() {
        val stats = StreakCalculator.stats(
            HabitFrequency.Daily,
            dates("2026-10-12", "2026-10-13", "2026-10-14"),
            today
        )
        assertThat(stats.current).isEqualTo(3)
    }

    @Test
    fun `每天 - 今天尚未打卡不打断_从昨天继续算`() {
        val stats = StreakCalculator.stats(
            HabitFrequency.Daily,
            dates("2026-10-11", "2026-10-12", "2026-10-13"),
            today
        )
        assertThat(stats.current).isEqualTo(3)
    }

    @Test
    fun `每天 - 今天和昨天都没打卡_当前连续归零`() {
        val stats = StreakCalculator.stats(
            HabitFrequency.Daily,
            dates("2026-10-11", "2026-10-12"),
            today
        )
        assertThat(stats.current).isEqualTo(0)
    }

    @Test
    fun `每天 - 中间断档_只算最近的连续段`() {
        val stats = StreakCalculator.stats(
            HabitFrequency.Daily,
            dates("2026-10-01", "2026-10-02", "2026-10-13", "2026-10-14"),
            today
        )
        assertThat(stats.current).isEqualTo(2)
        assertThat(stats.longest).isEqualTo(2)
        assertThat(stats.total).isEqualTo(4)
    }

    @Test
    fun `每天 - 最长连续取历史最大值`() {
        val stats = StreakCalculator.stats(
            HabitFrequency.Daily,
            dates("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-06", "2026-10-14"),
            today
        )
        assertThat(stats.longest).isEqualTo(3)
        assertThat(stats.current).isEqualTo(1)
    }

    @Test
    fun `每天 - 空集合_全为零`() {
        val stats = StreakCalculator.stats(HabitFrequency.Daily, emptySet(), today)
        assertThat(stats).isEqualTo(StreakStats.EMPTY)
    }

    @Test
    fun `每天 - 晚于今天的脏数据被忽略`() {
        val stats = StreakCalculator.stats(
            HabitFrequency.Daily,
            dates("2026-10-14", "2026-10-15", "2026-10-20"),
            today
        )
        assertThat(stats.current).isEqualTo(1)
        assertThat(stats.total).isEqualTo(1)
    }

    // -------------------------------------------------------------- 指定星期

    private val mwf = HabitFrequency.SpecificDays(
        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    )

    @Test
    fun `指定星期 - 计划日全部打卡_非计划日不影响`() {
        val stats = StreakCalculator.stats(
            mwf,
            dates("2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12"),
            d("2026-10-12")
        )
        assertThat(stats.current).isEqualTo(4)
    }

    @Test
    fun `指定星期 - 今天计划日未打卡不打断_从上一个计划日继续`() {
        // 今天是 10-12（周一，计划日）但没打卡；上一个计划日是 10-09（周五）
        val stats = StreakCalculator.stats(
            mwf,
            dates("2026-10-05", "2026-10-07", "2026-10-09"),
            d("2026-10-12")
        )
        assertThat(stats.current).isEqualTo(3)
    }

    @Test
    fun `指定星期 - 漏掉一个计划日_连续归零`() {
        // 今天是 10-16（周五）；10-12 周一 与 10-14 周三 都漏了
        val stats = StreakCalculator.stats(
            mwf,
            dates("2026-10-05", "2026-10-07", "2026-10-09"),
            d("2026-10-16")
        )
        assertThat(stats.current).isEqualTo(0)
    }

    @Test
    fun `指定星期 - 非计划日的长间隔不打断连续`() {
        // 只有周一打卡，周六周日等非计划日不应打断
        val onlyMonday = HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY))
        val stats = StreakCalculator.stats(
            onlyMonday,
            dates("2026-09-21", "2026-09-28", "2026-10-05"),
            d("2026-10-05")
        )
        assertThat(stats.current).isEqualTo(3)
    }

    @Test
    fun `指定星期 - 空计划日集合_返回零且不死循环`() {
        val empty = HabitFrequency.SpecificDays(emptySet())
        val stats = StreakCalculator.stats(empty, dates("2026-10-05"), today)
        assertThat(stats.current).isEqualTo(0)
        assertThat(stats.longest).isEqualTo(0)
    }

    @Test
    fun `指定星期 - 最长连续按计划日序列计算`() {
        val stats = StreakCalculator.stats(
            mwf,
            dates("2026-09-28", "2026-09-30", "2026-10-02", "2026-10-09"),
            d("2026-10-09")
        )
        // 09-28(一) 09-30(三) 10-02(五) 是连续三个计划日 -> 3
        assertThat(stats.longest).isEqualTo(3)
        // 当前：10-09(五) 有打卡 -> 1；10-07(三) 无 -> 断
        assertThat(stats.current).isEqualTo(1)
    }

    // ============================================ v2.14：删除「每周 N 次」的周维度算法
    //
    // 用户原话：「打卡频率里删除每周 N 次。」
    //
    // ⚠️ 改写说明（本节原有 6 条断言）：
    // 前 5 条断言的是已被删除的 `WeeklyTimes` + `currentWeekly`/`longestWeekly`
    // 那套"按自然周统计达标周数"的算法。用户删掉这个频率之后，
    // 那套算法**没有调用方**（它也随类型一起被删除了），
    // 因此这几条归类为**测试错（旧规格）** —— 被测语义整体删除，无等价物。
    //
    // 换成下面 5 条**同等强度、指向新行为**的断言：旧 `WEEKLY:n` 数据迁移成
    // 「指定星期」之后，连续天数必须仍然确定、可解释（按计划日算）。
    // 这样"删了选项"不会变成"旧用户数据失去意义"。
    //
    // 第 6 条（`mondayOf` 的自然周起点）**一条未改**地保留下来：
    // 它测的是一条仍在使用中的日期工具函数，与「每周 N 次」无关。

    /** 旧数据 `WEEKLY:3` 迁移后的频率：指定星期「一、二、三」。 */
    private val legacyWeekly3: HabitFrequency =
        HabitFrequencyCodec.legacyWeeklyTimesToSpecificDays("3")

    @Test
    fun `旧每周N次迁移后 - 连续两个计划年周都打满_当前连续按计划日累计`() {
        // 计划日 = 周一/周二/周三。09-28(一) 09-29(二) 09-30(三)
        // 10-05(一) 10-06(二) 10-07(三) 10-12(一) 10-13(二) 10-14(三)
        val stats = StreakCalculator.stats(
            legacyWeekly3,
            dates(
                "2026-09-28", "2026-09-29", "2026-09-30",
                "2026-10-05", "2026-10-06", "2026-10-07",
                "2026-10-12", "2026-10-13", "2026-10-14"
            ),
            today
        )
        assertThat(stats.current).isEqualTo(9)
    }

    @Test
    fun `旧每周N次迁移后 - 今天的计划日还没打卡不打断`() {
        // 今天 10-14(三) 是计划日但没打卡：不打断，从 10-13 往前数
        val stats = StreakCalculator.stats(
            legacyWeekly3,
            dates("2026-10-12", "2026-10-13"),
            today
        )
        assertThat(stats.current).isEqualTo(2)
    }

    @Test
    fun `旧每周N次迁移后 - 漏掉一个计划日即断（非计划日的空缺不算漏）`() {
        // 10-13(二) 是计划日却没打卡 -> 断；今天 10-14 打了也只有 1
        assertThat(
            StreakCalculator.stats(
                legacyWeekly3,
                dates("2026-10-12", "2026-10-14"),
                today
            ).current
        ).isEqualTo(1)
        // 非计划日 10-15(四) 10-16(五) 不打不影响：计划日 10-12/13/14 都打了 -> 3
        assertThat(
            StreakCalculator.stats(
                legacyWeekly3,
                dates("2026-10-12", "2026-10-13", "2026-10-14"),
                today
            ).current
        ).isEqualTo(3)
    }

    @Test
    fun `旧每周N次迁移后 - 最长连续按计划日统计`() {
        val stats = StreakCalculator.stats(
            legacyWeekly3,
            dates(
                // 第一段：09-14/15/16（一/二/三）共 3 个计划日
                "2026-09-14", "2026-09-15", "2026-09-16",
                // 断：09-21(一) 没打
                // 第二段：10-12/13/14 共 3 个计划日
                "2026-10-12", "2026-10-13", "2026-10-14"
            ),
            today
        )
        assertThat(stats.longest).isEqualTo(3)
        assertThat(stats.current).isEqualTo(3)
    }

    @Test
    fun `旧每周N次的越界值退化为每天_连续天数按每天算`() {
        // 旧版里 `WEEKLY:0` / `WEEKLY:9` 这类脏数据本来也退化为「每天」，
        // 迁移之后行为不变：连续天数按"每天"的口径算。
        val degraded = HabitFrequencyCodec.legacyWeeklyTimesToSpecificDays("0")
        assertThat(degraded).isEqualTo(HabitFrequency.Daily)
        assertThat(
            StreakCalculator.stats(
                degraded,
                dates("2026-10-12", "2026-10-13", "2026-10-14"),
                today
            ).current
        ).isEqualTo(3)
    }

    // ------------------------------------------------------------ 自然周的起点
    //
    // 这一条**与「每周 N 次」无关**：`mondayOf` 是一条仍在使用的日期工具
    // （记录页的热力图有同一定义）。v2.14 只删了周维度的连续天数算法，
    // 没有删这个定义，所以断言原样保留。

    @Test
    fun `自然周以周一为起点_周日晚与周一早属于不同周`() {
        // 2026-10-04 是周日（属于 09-28 那一周），2026-10-05 是周一（属于 10-05 那一周）
        assertThat(StreakCalculator.mondayOf(d("2026-10-04"))).isEqualTo(d("2026-09-28"))
        assertThat(StreakCalculator.mondayOf(d("2026-10-05"))).isEqualTo(d("2026-10-05"))
        assertThat(StreakCalculator.mondayOf(d("2026-10-11"))).isEqualTo(d("2026-10-05"))
        assertThat(StreakCalculator.mondayOf(d("2026-10-12"))).isEqualTo(d("2026-10-12"))
    }

    // -------------------------------------------- 指定每月几号（v2.13 取代每月 N 次）
    //
    // 用户原话：「删除每月打卡多少次的选项，改为指定每月1-31号打卡，
    // 如果当月没有31号就跳过没有的号数，直接开始下一个月一号。」
    //
    // ## 连续天数的定义（正式定义在 StreakCalculator 的 KDoc 里）
    //
    // **按「计划日」的连续性算，单位是"计划日"**，与「指定星期」**严格同构**：
    //   - 计划日 = 选中的那几号；没选中的日子、以及"当月根本不存在的那一号"
    //     都不是计划日
    //   - 从今天往前逐个计划日检查，每个都打了卡就 +1，遇到第一个没打卡的计划日就停
    //   - 今天若是计划日但还没打卡，**不打断**（今天还没过完）
    //   - 非计划日既不计数也不打断
    //
    // 这一组里的日期都用真实日历核对过：
    //   2026-01 有 31 天、2026-02 有 28 天、2026-04 有 30 天、2028-02 有 29 天

    /** 每月 1 号、15 号。 */
    private val firstAnd15th = HabitFrequency.SpecificMonthDays(setOf(1, 15))

    /** 只选 31 号 —— 用来钉死"当月没有这个日期就跳过"。 */
    private val only31 = HabitFrequency.SpecificMonthDays(setOf(31))

    @Test
    fun `指定每月几号 - 计划日全打卡_非计划日不影响`() {
        // 9-15、10-01、10-15 三个计划日全打了；今天是 10-15
        val stats = StreakCalculator.stats(
            firstAnd15th,
            dates("2026-09-15", "2026-10-01", "2026-10-15"),
            d("2026-10-15")
        )
        assertThat(stats.current).isEqualTo(3)
    }

    @Test
    fun `指定每月几号 - 今天计划日未打卡不打断_从上一个计划日继续`() {
        // 今天是 10-15（计划日）但还没打卡；上一个计划日 10-01 打了
        val stats = StreakCalculator.stats(
            firstAnd15th,
            dates("2026-09-15", "2026-10-01"),
            d("2026-10-15")
        )
        assertThat(stats.current).isEqualTo(2)
    }

    @Test
    fun `指定每月几号 - 漏掉一个计划日_连续归零`() {
        // 今天是 10-15；10-01 漏了（只打了 9-15）
        val stats = StreakCalculator.stats(
            firstAnd15th,
            dates("2026-09-15"),
            d("2026-10-15")
        )
        assertThat(stats.current).isEqualTo(0)
    }

    @Test
    fun `指定每月几号 - 与指定星期严格同构：同一组计划日算法`() {
        // 「指定星期」里 09-28(一) 09-30(三) 10-02(五) 是连续三个计划日；
        // 「指定每月几号」里 09-28、09-30、10-02 同理 —— 连续天数只由
        // "计划日序列 + 每个计划日有没有打卡"决定，与计划日怎么定义无关
        val monthDays = HabitFrequency.SpecificMonthDays(setOf(28, 30, 2))
        val stats = StreakCalculator.stats(
            monthDays,
            dates("2026-09-28", "2026-09-30", "2026-10-02"),
            d("2026-10-02")
        )
        assertThat(stats.current).isEqualTo(3)

        // 与「指定星期」同一组数据的结果必须一致（逐行同构的直接证据）
        val weekdays = HabitFrequency.SpecificDays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        assertThat(
            StreakCalculator.stats(
                weekdays,
                dates("2026-09-28", "2026-09-30", "2026-10-02"),
                d("2026-10-02")
            ).current
        ).isEqualTo(3)
    }

    @Test
    fun `指定每月几号 - 最长连续按计划日序列计算`() {
        val stats = StreakCalculator.stats(
            firstAnd15th,
            // 09-01、09-15、10-01 连续三个计划日
            dates("2026-09-01", "2026-09-15", "2026-10-01", "2026-10-15"),
            d("2026-10-15")
        )
        assertThat(stats.longest).isEqualTo(4)
        assertThat(stats.current).isEqualTo(4)
    }

    @Test
    fun `指定每月几号 - 空计划日集合_返回零且不死循环`() {
        val empty = HabitFrequency.SpecificMonthDays(emptySet())
        val stats = StreakCalculator.stats(empty, dates("2026-10-01"), today)
        assertThat(stats.current).isEqualTo(0)
        assertThat(stats.longest).isEqualTo(0)
    }

    @Test
    fun `指定每月几号 - 空集合全为零`() {
        assertThat(StreakCalculator.stats(firstAnd15th, emptySet(), today))
            .isEqualTo(StreakStats.EMPTY)
    }

    @Test
    fun `指定每月几号 - 晚于今天的脏数据被忽略`() {
        val stats = StreakCalculator.stats(
            firstAnd15th,
            // 10-01 是真实打卡；10-20 / 10-25 是晚于今天的脏数据（时钟回拨等）
            dates("2026-10-01", "2026-10-20", "2026-10-25"),
            today
        )
        // 今天 10-14 不是计划日；往前第一个计划日 10-01 打了 -> 1；
        // 再往前 9-15 没打 -> 断。脏数据既不入 total 也不影响连续。
        assertThat(stats.current).isEqualTo(1)
        assertThat(stats.total).isEqualTo(1)
    }

    // -------------------------------------------- "当月没有这个日期就跳过"（核心）
    //
    // 这是用户原话里最具体的一条，也是唯一容易写错的地方：
    // **"当月没有该日期"不能被记成漏打卡**。
    // 若把算法写成"按自然月算、这个月所有选中日期都得打"，2 月就成了一次
    // 无法完成的失败 —— 正好是用户要求的反面。

    @Test
    fun `只选 31 号 - 4 月没有 31 号，整月不打断连续`() {
        // 计划日序列：3-31 → 5-31（4 月整月没有计划日）
        // 今天 4-15（4 月里任意一天都不是计划日）：往前第一个计划日是 3-31
        val stats = StreakCalculator.stats(
            only31,
            dates("2026-01-31", "2026-03-31"),
            d("2026-04-15")
        )
        assertThat(stats.current).isEqualTo(2)
    }

    @Test
    fun `只选 31 号 - 3 月 31 到 5 月 31 之间不断档（跨过没有 31 号的 4 月）`() {
        val stats = StreakCalculator.stats(
            only31,
            dates("2026-03-31", "2026-05-31"),
            d("2026-05-31")
        )
        // 5-31 打了 -> 1；4 月没有计划日 -> 跳过；3-31 打了 -> 2；
        // 2 月没有计划日 -> 跳过；1-31 没打 -> 断
        assertThat(stats.current).isEqualTo(2)
    }

    @Test
    fun `只选 31 号 - 2 月没有 31 号，不产生漏打卡`() {
        // 1-31 打了，2 月没有 31 号，3-31 打了 -> 连续 2（不是 0）
        val stats = StreakCalculator.stats(
            only31,
            dates("2026-01-31", "2026-03-31"),
            d("2026-03-31")
        )
        assertThat(stats.current).isEqualTo(2)
    }

    @Test
    fun `只选 31 号 - 闰年 2 月 29 号也不是计划日`() {
        // 2028 是闰年，2 月有 29 天，但仍然没有 31 号
        val stats = StreakCalculator.stats(
            only31,
            dates("2028-01-31", "2028-03-31"),
            d("2028-02-29")
        )
        assertThat(LocalDate.of(2028, 2, 1).lengthOfMonth()).isEqualTo(29)
        // 今天 2-29 不是计划日；往前 1-31 打了 -> 1；再往前 12-31 没打 -> 断
        assertThat(stats.current).isEqualTo(1)
    }

    @Test
    fun `选 29 30 31 - 2 月只有 28 天时三个都不存在，整月跳过`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(29, 30, 31))
        val stats = StreakCalculator.stats(
            f,
            dates("2026-01-29", "2026-01-30", "2026-01-31", "2026-03-29"),
            d("2026-03-29")
        )
        // 3-29 打了 -> 1；2 月没有任何计划日 -> 跳过；1-31/1-30/1-29 都打了 -> 4
        assertThat(stats.current).isEqualTo(4)
    }

    @Test
    fun `选 29 30 31 - 闰年 2 月 29 号是计划日`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(29, 30, 31))
        val stats = StreakCalculator.stats(
            f,
            dates("2028-01-29", "2028-01-30", "2028-01-31", "2028-02-29"),
            d("2028-02-29")
        )
        // 2-29（闰年存在且已打卡）-> 1，再往前 1-31/1-30/1-29 -> 4
        assertThat(stats.current).isEqualTo(4)
    }

    @Test
    fun `只选 31 号 - 漏了真正存在的那个月就要断`() {
        // 5-31 打了，但 3-31 漏了（3 月是有 31 号的）-> 连续 1
        val stats = StreakCalculator.stats(
            only31,
            dates("2026-01-31", "2026-05-31"),
            d("2026-05-31")
        )
        assertThat(stats.current).isEqualTo(1)
    }

    @Test
    fun `选 1 号 - 每月 1 号都不受当月天数影响`() {
        val firstOfMonth = HabitFrequency.SpecificMonthDays(setOf(1))
        val stats = StreakCalculator.stats(
            firstOfMonth,
            dates("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"),
            d("2026-04-01")
        )
        // 2 月只有 28 天，但 2-01 一定存在
        assertThat(stats.current).isEqualTo(4)
    }

    @Test
    fun `指定每月几号 - 同一天多次打卡只算一天`() {
        // checkedDates 是 Set，天然去重；这里确认口径不会被"条数"影响
        val stats = StreakCalculator.stats(
            HabitFrequency.SpecificMonthDays(setOf(1, 2)),
            dates("2026-10-01", "2026-10-02"),
            today
        )
        assertThat(stats.current).isEqualTo(2)
        assertThat(stats.total).isEqualTo(2)
    }

    @Test
    fun `指定每月几号 - monthOf 与自然月一致`() {
        assertThat(StreakCalculator.monthOf(d("2026-10-01"))).isEqualTo(YearMonth.of(2026, 10))
        assertThat(StreakCalculator.monthOf(d("2026-10-31"))).isEqualTo(YearMonth.of(2026, 10))
        assertThat(StreakCalculator.monthOf(d("2026-11-01"))).isEqualTo(YearMonth.of(2026, 11))
    }
}
