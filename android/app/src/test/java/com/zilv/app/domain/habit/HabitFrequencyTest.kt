package com.zilv.app.domain.habit

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * [HabitFrequency] 自身的语义测试（不涉及编解码与连续天数）。
 *
 * v2.13 用户要求："删除每月打卡多少次的选项，改为指定每月1-31号打卡，
 * 如果当月没有31号就跳过没有的号数，直接开始下一个月一号。"
 *
 * 这里钉死四件事：
 *  1. [HabitFrequency.SpecificMonthDays] 的 **1..31 边界**（越界一律拒绝，不静默夹取）
 *  2. 「指定每月几号」下 [HabitFrequency.isScheduledOn] 的口径（只有选中的那几号）
 *  3. **当月没有该日期时自然跳过**（4 月没有 31 号 ⇒ 4 月不是计划日）——
 *     这是用户原话里最具体的一条要求，也用 `LocalDate` 的自然行为实现
 *  4. 「今日是否需要打卡」[HabitFrequency.needsCheckInOn] 在四种频率下的定义
 *
 * 日期基准（与 [StreakCalculatorTest] 一致）：2026-10-14 是**周三**，
 * 所在自然周为 10-12（周一）..10-18（周日），所在自然月为 2026-10。
 */
class HabitFrequencyTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    private val today = d("2026-10-14") // 周三

    private fun dates(vararg s: String): Set<LocalDate> = s.map { d(it) }.toSet()

    // ------------------------------------------------- 指定每月几号（v2.13 新）

    @Test
    fun `指定每月几号 - 1 到 31 都可以构造`() {
        assertThat(HabitFrequency.SpecificMonthDays(setOf(1)).days).containsExactly(1)
        assertThat(HabitFrequency.SpecificMonthDays(setOf(31)).days).containsExactly(31)
        assertThat(HabitFrequency.SpecificMonthDays(setOf(1, 15, 31)).days)
            .containsExactly(1, 15, 31)
    }

    @Test
    fun `指定每月几号 - 越界被拒绝而不是静默夹取`() {
        assertFailsWith<IllegalArgumentException> { HabitFrequency.SpecificMonthDays(setOf(0)) }
        assertFailsWith<IllegalArgumentException> { HabitFrequency.SpecificMonthDays(setOf(32)) }
        assertFailsWith<IllegalArgumentException> { HabitFrequency.SpecificMonthDays(setOf(-1)) }
        assertFailsWith<IllegalArgumentException> {
            HabitFrequency.SpecificMonthDays(setOf(1, 99))
        }
    }

    @Test
    fun `指定每月几号 - 上限就是 31（不是 28）`() {
        // 「每月 N 次」的上限是 28（因为要保证任何月份都排得下 N 次）；
        // 「指定每月几号」约束的是**日期**，31 号在 7 个大月里都存在，
        // 其余月份只是"这个月没有这个计划日"，跳过即可 —— 所以上限是 31。
        assertThat(HabitFrequency.SpecificMonthDays.MAX_DAY).isEqualTo(31)
        assertThat(HabitFrequency.SpecificMonthDays.MIN_DAY).isEqualTo(1)
        assertThat(LocalDate.of(2026, 1, 1).lengthOfMonth()).isEqualTo(31)
    }

    @Test
    fun `指定每月几号 - 计划日口径：只算选中的那几号`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(1, 15))
        assertThat(f.isScheduledOn(d("2026-10-01"))).isTrue()
        assertThat(f.isScheduledOn(d("2026-10-15"))).isTrue()
        assertThat(f.isScheduledOn(d("2026-10-14"))).isFalse()
        assertThat(f.isScheduledOn(d("2026-10-31"))).isFalse()
    }

    @Test
    fun `指定每月几号 - 当月没有这个日期时那一天不存在，自然跳过`() {
        val only31 = HabitFrequency.SpecificMonthDays(setOf(31))

        // 2026-04 只有 30 天：整月没有任何一天是计划日
        (1..30).forEach { dayOfMonth ->
            assertThat(only31.isScheduledOn(LocalDate.of(2026, 4, dayOfMonth))).isFalse()
        }
        // 而 3 月/5 月都有 31 号
        assertThat(only31.isScheduledOn(d("2026-03-31"))).isTrue()
        assertThat(only31.isScheduledOn(d("2026-05-31"))).isTrue()
    }

    @Test
    fun `指定每月几号 - 2 月的 29 30 31 与平年闰年`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(29, 30, 31))

        // 2026 是平年，2 月只有 28 天 —— 三个选中日期一个都不存在
        (1..28).forEach { dayOfMonth ->
            assertThat(f.isScheduledOn(LocalDate.of(2026, 2, dayOfMonth))).isFalse()
        }
        // 2028 是闰年，2 月有 29 天 —— 只有 29 号存在
        assertThat(f.isScheduledOn(LocalDate.of(2028, 2, 29))).isTrue()
        assertThat(LocalDate.of(2028, 2, 1).lengthOfMonth()).isEqualTo(29)
    }

    @Test
    fun `指定每月几号 - 空集合永远不是计划日`() {
        val empty = HabitFrequency.SpecificMonthDays(emptySet())
        assertThat(empty.isScheduledOn(today)).isFalse()
        assertThat(empty.needsCheckInOn(today, emptySet())).isFalse()
        assertThat(empty.sortedDays()).isEmpty()
    }

    @Test
    fun `指定每月几号 - sortedDays 升序，用于界面与备份展示`() {
        assertThat(HabitFrequency.SpecificMonthDays(setOf(31, 1, 15)).sortedDays())
            .containsExactly(1, 15, 31).inOrder()
    }

    // ------------------------------------------------------- 今日是否需要打卡

    @Test
    fun `需要打卡 - 每天：今天没打就需要`() {
        assertThat(HabitFrequency.Daily.needsCheckInOn(today, emptySet())).isTrue()
        assertThat(HabitFrequency.Daily.needsCheckInOn(today, dates("2026-10-13"))).isTrue()
        assertThat(HabitFrequency.Daily.needsCheckInOn(today, dates("2026-10-14"))).isFalse()
    }

    // ======================================================== v2.14：删除「每周 N 次」
    //
    // 用户原话：「打卡频率里删除每周 N 次。」
    //
    // ⚠️ 改写说明（旧口径 → 新口径）：
    // 原来这里有一条 `需要打卡 - 每周N次：本周没做满就需要`，
    // 它断言的是**已被用户明确删除**的 `HabitFrequency.WeeklyTimes` 的周期配额语义
    // （`needsCheckInOn` 里"本周完成天数 < N"那一支）。
    // 归类：**测试错（旧规格）** —— 被测语义整体删除，等价断言不存在。
    // 换成下面这条**更强**的断言：它把"旧数据迁移之后仍然有确定的打卡语义"钉死
    // （迁移目标 = 指定星期，所以按"计划日"判定，而不是按"本周次数"）。

    @Test
    fun `旧的每周N次编码迁移成指定星期后，按计划日判定是否需要打卡`() {
        // WEEKLY:3 → 指定星期「一、二、三」（次数不变：一周 3 次）
        val migrated = HabitFrequencyCodec.legacyWeeklyTimesToSpecificDays("3")
        assertThat(migrated)
            .isEqualTo(HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)))

        // 今天 2026-10-14 是周三（计划日），还没打卡 -> 需要
        assertThat(today.dayOfWeek).isEqualTo(DayOfWeek.WEDNESDAY)
        assertThat(migrated.needsCheckInOn(today, emptySet())).isTrue()
        // 今天打过 -> 不需要
        assertThat(migrated.needsCheckInOn(today, dates("2026-10-14"))).isFalse()
        // 周四不是计划日 -> 不需要（旧语义是"本周还欠几次"，新语义是"今天是不是计划日"）
        assertThat(migrated.needsCheckInOn(d("2026-10-15"), emptySet())).isFalse()
    }

    @Test
    fun `需要打卡 - 指定每月几号：今天是选中的日期且还没打卡就需要`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(14))
        assertThat(f.needsCheckInOn(today, emptySet())).isTrue()
        assertThat(f.needsCheckInOn(today, dates("2026-10-14"))).isFalse()
    }

    @Test
    fun `需要打卡 - 指定每月几号：今天没选就不需要，与这个月打了几天无关`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(1, 15))
        // 今天 10-14 不是计划日 -> 不需要（哪怕这个月一次都没打）
        assertThat(f.needsCheckInOn(today, emptySet())).isFalse()
        // 10-15 是计划日 -> 需要
        assertThat(f.needsCheckInOn(d("2026-10-15"), emptySet())).isTrue()
    }

    @Test
    fun `需要打卡 - 指定每月几号：当月没有该日期时整月都不需要`() {
        val only31 = HabitFrequency.SpecificMonthDays(setOf(31))
        // 4 月的任何一天都不需要打卡 —— 用户要的"跳过没有的号数"
        listOf("2026-04-01", "2026-04-15", "2026-04-30").forEach { day ->
            assertThat(only31.needsCheckInOn(d(day), emptySet())).isFalse()
        }
        // 5 月 31 号恢复需要
        assertThat(only31.needsCheckInOn(d("2026-05-31"), emptySet())).isTrue()
    }

    @Test
    fun `需要打卡 - 指定每月几号：上个月的打卡不能顶替这个月`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(14))
        // 上个月 14 号打过卡，但这个月 14 号还没打 -> 仍然需要
        assertThat(f.needsCheckInOn(today, dates("2026-09-14"))).isTrue()
        // 这个月 14 号打过 -> 不需要
        assertThat(f.needsCheckInOn(today, dates("2026-09-14", "2026-10-14"))).isFalse()
    }

    @Test
    fun `需要打卡 - 指定每月几号：本月做满过也不影响别的日期`() {
        // 与「每月 N 次」不同，新频率不看"这个月做了几次"。
        // 选了 1 号和 15 号，1 号已经打过；15 号该做还是要做。
        val f = HabitFrequency.SpecificMonthDays(setOf(1, 15))
        assertThat(f.needsCheckInOn(d("2026-10-15"), dates("2026-10-01"))).isTrue()
    }

    @Test
    fun `需要打卡 - 未来的脏数据不能把本周期提前填满`() {
        val daily = HabitFrequency.Daily
        // 明天有一条脏打卡记录：今天仍然"还没打卡"
        assertThat(daily.needsCheckInOn(today, dates("2026-10-14", "2026-10-15"))).isFalse()
        assertThat(daily.needsCheckInOn(today, dates("2026-10-15"))).isTrue()

        // v2.14 改写：原来的第二条用的是已删除的 `WeeklyTimes(2)`
        // （断言"本周的后天脏记录不能把本周提前填满"）。
        // 归类：**测试错（旧规格）** —— 周期配额的语义随类型一起删除。
        // 换成「指定每月几号」的同一条意图（更强：它连"未来同号日期"都能挡住）：
        // 16 号是计划日但还没到，脏记录不能把 14 号顶成"已完成"。
        val monthDays = HabitFrequency.SpecificMonthDays(setOf(14, 16))
        assertThat(monthDays.needsCheckInOn(today, dates("2026-10-13", "2026-10-16"))).isTrue()
        assertThat(monthDays.needsCheckInOn(today, dates("2026-10-13", "2026-10-14"))).isFalse()
    }

    @Test
    fun `需要打卡 - 指定星期：只看计划日`() {
        val monday = HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY))
        // 今天是周三，非计划日 -> 不需要
        assertThat(monday.needsCheckInOn(today, emptySet())).isFalse()
        // 周一没打卡 -> 需要
        assertThat(monday.needsCheckInOn(d("2026-10-12"), emptySet())).isTrue()
        // 周一打了 -> 不需要
        assertThat(monday.needsCheckInOn(d("2026-10-12"), dates("2026-10-12"))).isFalse()
    }

    // ------------------------------------------------- 「指定星期」含周日（v2.13）
    //
    // 用户原话："打卡频率里指定星期加上周日选项。"
    //
    // 查证结论：领域层**一直**支持 SUNDAY（`DayOfWeek` 有 7 个值，编解码按 1..7
    // 存储，周日就是 7），出问题的只是界面布局（见 HabitEditScreen 的 KDoc）。
    // 这几条把"数据层支持周日"这件事钉死，避免以后有人把界面 bug 误判成领域 bug
    // 而去乱改 `DayOfWeek` 的取舍。

    @Test
    fun `指定星期含周日 - 周日是计划日`() {
        val sunday = HabitFrequency.SpecificDays(setOf(DayOfWeek.SUNDAY))
        // 2026-10-18 是周日
        assertThat(d("2026-10-18").dayOfWeek).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(sunday.isScheduledOn(d("2026-10-18"))).isTrue()
        // 相邻的周六/周一不是
        assertThat(sunday.isScheduledOn(d("2026-10-17"))).isFalse()
        assertThat(sunday.isScheduledOn(d("2026-10-19"))).isFalse()
    }

    @Test
    fun `指定星期含周日 - 周日需要打卡且打了就不需要`() {
        val sunday = HabitFrequency.SpecificDays(setOf(DayOfWeek.SUNDAY))
        val date = d("2026-10-18")
        assertThat(sunday.needsCheckInOn(date, emptySet())).isTrue()
        assertThat(sunday.needsCheckInOn(date, dates("2026-10-18"))).isFalse()
    }

    @Test
    fun `指定星期 - 七天全选时每天都是计划日`() {
        // DayOfWeek 是 Java 枚举，取值用 values()；顺带证明"七天都在领域模型里"，
        // 界面少显示一天只可能是布局问题，不可能是枚举缺值
        val all = HabitFrequency.SpecificDays(DayOfWeek.values().toSet())
        assertThat(all.days).hasSize(7)
        (0L until 7L).forEach { offset ->
            assertThat(all.isScheduledOn(d("2026-10-12").plusDays(offset))).isTrue()
        }
    }

    @Test
    fun `需要打卡 - 空计划日集合永远不需要`() {
        val empty = HabitFrequency.SpecificDays(emptySet())
        assertThat(empty.needsCheckInOn(today, emptySet())).isFalse()
    }
}
