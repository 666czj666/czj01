package com.zilv.app.domain.records

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * [MonthCalendar] 的单元测试。
 *
 * 用户要求："把打卡日历改为显示所有天数，有一个或多个打卡记录的日期下以绿色为底色"。
 * 这里钉死两件事：**每一天都在网格里**（含首尾补位）、**打卡计数正确**。
 *
 * 2026-10 的日历：1 号是周四，31 号是周六。
 */
class MonthCalendarTest {

    private val october = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 14)

    /**
     * 原有的 15 个用例都通过这个工厂构造网格。
     *
     * v2.3 给 `build` 加了 `noteDates` 参数（默认空集合），这里保持
     * "不传 = 没有留言"的口径，因此**上述用例的断言一条都不用改**。
     * v2.4 又加了 `planDates`（同样默认空），工厂跟着多一个参数，
     * 位置参数的含义（第 4 个仍是留言）没有变。
     */
    private fun build(
        counts: Map<LocalDate, Int> = emptyMap(),
        notes: Set<LocalDate> = emptySet(),
        plans: Set<LocalDate> = emptySet()
    ) = MonthCalendar.build(october, counts, today, notes, plans)

    // ---------------------------------------------------------------- 网格

    @Test
    fun `网格包含整月每一天`() {
        val month = build()
        val inMonth = month.days.filter { it.inMonth }.map { it.date }

        assertThat(inMonth).hasSize(31)
        assertThat(inMonth.first()).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(inMonth.last()).isEqualTo(LocalDate.of(2026, 10, 31))
    }

    @Test
    fun `首尾用邻月日期补齐成整周`() {
        val month = build()
        // 每一行都是 7 天
        assertThat(month.weeks.all { it.size == 7 }).isTrue()
        // 10/1 是周四，所以网格从 9/28（周一）开始
        assertThat(month.days.first().date).isEqualTo(LocalDate.of(2026, 9, 28))
        assertThat(month.days.first().inMonth).isFalse()
        // 总天数是 7 的倍数
        assertThat(month.days.size % 7).isEqualTo(0)
    }

    @Test
    fun `补位日不计入本月统计`() {
        val month = build(mapOf(LocalDate.of(2026, 9, 28) to 5))
        // 9/28 是补位日，虽然网格里有它，但不该算进本月
        assertThat(month.days.first { it.date == LocalDate.of(2026, 9, 28) }.checkInCount).isEqualTo(5)
        assertThat(month.totalCheckIns).isEqualTo(0)
        assertThat(month.checkedDayCount).isEqualTo(0)
    }

    @Test
    fun `二月也能铺满至少四行`() {
        // 2026-02：1 号是周日，28 天。若不对齐可能只有 4 行，但要保证不出现 3 行
        val feb = MonthCalendar.build(YearMonth.of(2026, 2), emptyMap(), today)
        assertThat(feb.weeks.size).isAtLeast(4)
        assertThat(feb.days.filter { it.inMonth }).hasSize(28)
    }

    @Test
    fun `周日开头的月份也对`() {
        val feb = MonthCalendar.build(
            YearMonth.of(2026, 2),
            emptyMap(),
            today,
            firstDayOfWeek = DayOfWeek.SUNDAY
        )
        // 2026-02-01 是周日，所以网格首格就是它
        assertThat(feb.days.first().date).isEqualTo(LocalDate.of(2026, 2, 1))
    }

    // ---------------------------------------------------------------- 打卡

    @Test
    fun `有打卡记录的日期计数正确`() {
        val month = build(
            mapOf(
                LocalDate.of(2026, 10, 1) to 1,
                LocalDate.of(2026, 10, 14) to 3,
                LocalDate.of(2026, 10, 31) to 2
            )
        )

        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 1) }.checkInCount).isEqualTo(1)
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 14) }.checkInCount).isEqualTo(3)
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 31) }.checkInCount).isEqualTo(2)
    }

    @Test
    fun `hasCheckIn 是一条还是多条都为真`() {
        val month = build(
            mapOf(LocalDate.of(2026, 10, 5) to 1, LocalDate.of(2026, 10, 6) to 7)
        )
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 5) }.hasCheckIn).isTrue()
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 6) }.hasCheckIn).isTrue()
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 7) }.hasCheckIn).isFalse()
    }

    @Test
    fun `统计本月有打卡的天数与总条数`() {
        val month = build(
            mapOf(
                LocalDate.of(2026, 10, 1) to 1,
                LocalDate.of(2026, 10, 2) to 2,
                LocalDate.of(2026, 10, 3) to 1
            )
        )
        assertThat(month.checkedDayCount).isEqualTo(3)
        assertThat(month.totalCheckIns).isEqualTo(4)
    }

    @Test
    fun `整月无记录时 isEmpty 为真`() {
        assertThat(build().isEmpty).isTrue()
        assertThat(build(mapOf(LocalDate.of(2026, 10, 1) to 1)).isEmpty).isFalse()
    }

    // ---------------------------------------------------------------- 今天

    @Test
    fun `今天被正确标记`() {
        val month = build()
        val todays = month.days.filter { it.isToday }
        assertThat(todays).hasSize(1)
        assertThat(todays.first().date).isEqualTo(today)
    }

    @Test
    fun `今天不在本月时没有任何格子被标记`() {
        val month = MonthCalendar.build(YearMonth.of(2026, 3), emptyMap(), today)
        assertThat(month.days.none { it.isToday }).isTrue()
    }

    // ---------------------------------------------------------------- 翻月

    @Test
    fun `翻月辅助方法正确`() {
        val month = build()
        assertThat(month.previousMonth()).isEqualTo(YearMonth.of(2026, 9))
        assertThat(month.nextMonth()).isEqualTo(YearMonth.of(2026, 11))
    }

    @Test
    fun `跨年翻月正确`() {
        val jan = MonthCalendar.build(YearMonth.of(2026, 1), emptyMap(), today)
        assertThat(jan.previousMonth()).isEqualTo(YearMonth.of(2025, 12))
    }

    // ---------------------------------------------------------------- 表头

    @Test
    fun `周一起始的表头`() {
        assertThat(MonthCalendar.weekdayLabels())
            .containsExactly("一", "二", "三", "四", "五", "六", "日").inOrder()
    }

    @Test
    fun `周日起始的表头`() {
        assertThat(MonthCalendar.weekdayLabels(DayOfWeek.SUNDAY))
            .containsExactly("日", "一", "二", "三", "四", "五", "六").inOrder()
    }

    // ---------------------------------------------------------------- 留言
    //
    // 用户要求："点「所有任务已完成」可以留言，留言记录可以在日历上看见。"
    // 日历上留言是**小圆点**，与"打卡绿底"是两个独立标记，必须能同时出现。

    @Test
    fun `没有留言时所有格子 hasNote 为假`() {
        val month = build(mapOf(LocalDate.of(2026, 10, 14) to 1))
        assertThat(month.days.none { it.hasNote }).isTrue()
        assertThat(month.notedDayCount).isEqualTo(0)
    }

    @Test
    fun `有留言的那一天 hasNote 为真，其它天为假`() {
        val noteDay = LocalDate.of(2026, 10, 14)
        val month = build(notes = setOf(noteDay))

        assertThat(month.days.first { it.date == noteDay }.hasNote).isTrue()
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 13) }.hasNote).isFalse()
        assertThat(month.notedDayCount).isEqualTo(1)
    }

    @Test
    fun `打卡与留言可以同时存在且互不影响`() {
        val both = LocalDate.of(2026, 10, 14)
        val onlyNote = LocalDate.of(2026, 10, 15)
        val onlyCheckIn = LocalDate.of(2026, 10, 16)
        val month = build(
            counts = mapOf(both to 2, onlyCheckIn to 1),
            notes = setOf(both, onlyNote)
        )

        val bothDay = month.days.first { it.date == both }
        assertThat(bothDay.hasCheckIn).isTrue()
        assertThat(bothDay.hasNote).isTrue()

        val noteDay = month.days.first { it.date == onlyNote }
        assertThat(noteDay.hasNote).isTrue()
        assertThat(noteDay.hasCheckIn).isFalse()

        val checkInDay = month.days.first { it.date == onlyCheckIn }
        assertThat(checkInDay.hasCheckIn).isTrue()
        assertThat(checkInDay.hasNote).isFalse()
    }

    @Test
    fun `补位日的留言不计入本月统计`() {
        // 9/28 是补位日（10 月网格从它开始）
        val padding = LocalDate.of(2026, 9, 28)
        val month = build(notes = setOf(padding))

        assertThat(month.days.first { it.date == padding }.hasNote).isTrue()
        assertThat(month.notedDayCount).isEqualTo(0)
    }

    @Test
    fun `只有留言没有打卡的月份不算空`() {
        // isEmpty 同时看打卡与留言：某天只写了留言、一条都没打卡，
        // 那也不该显示"这个月还没有记录"
        assertThat(build().isEmpty).isTrue()
        assertThat(build(notes = setOf(LocalDate.of(2026, 10, 14))).isEmpty).isFalse()
    }

    @Test
    fun `统计本月有留言的天数`() {
        val month = build(
            notes = setOf(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 31)
            )
        )
        assertThat(month.notedDayCount).isEqualTo(3)
    }

    @Test
    fun `留言集合里的非本月日期不会落到任何格子`() {
        // 11 月的日期不在 10 月的网格里，不该凭空造出格子
        val month = build(notes = setOf(LocalDate.of(2026, 11, 5)))
        assertThat(month.days.none { it.hasNote }).isTrue()
        assertThat(month.notedDayCount).isEqualTo(0)
    }

    @Test
    fun `不传留言参数时默认没有留言`() {
        // 显式钉住新参数的默认值 —— 这是"现有调用点无需改动"的前提
        val month = MonthCalendar.build(october, emptyMap(), today)
        assertThat(month.days.none { it.hasNote }).isTrue()
    }

    // ---------------------------------------------------------------- 规划
    //
    // 用户要求："日历上可以看见当天的规划。"
    // 规划是与"打卡绿底""留言小圆点"并列的**第三个**标记，
    // 三者必须能各自独立出现（规划日往往还没有打卡，也没有留言）。

    @Test
    fun `规划 - 没有规划时所有格子 hasPlan 为假`() {
        val month = build(mapOf(LocalDate.of(2026, 10, 14) to 1))
        assertThat(month.days.none { it.hasPlan }).isTrue()
        assertThat(month.days.all { it.planCount == 0 }).isTrue()
        assertThat(month.plannedDayCount).isEqualTo(0)
    }

    @Test
    fun `规划 - 有规划的那一天 hasPlan 为真，其它天为假`() {
        val planDay = LocalDate.of(2026, 10, 15)
        val month = build(plans = setOf(planDay))

        val day = month.days.first { it.date == planDay }
        assertThat(day.hasPlan).isTrue()
        assertThat(day.planCount).isEqualTo(1)

        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 14) }.hasPlan).isFalse()
        assertThat(month.plannedDayCount).isEqualTo(1)
    }

    @Test
    fun `规划 - 统计本月有规划的天数`() {
        val month = build(
            plans = setOf(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 31)
            )
        )
        assertThat(month.plannedDayCount).isEqualTo(3)
    }

    @Test
    fun `规划 - 补位日的规划不计入本月统计`() {
        // 9/28 是补位日（10 月网格从它开始）
        val padding = LocalDate.of(2026, 9, 28)
        val month = build(plans = setOf(padding))

        assertThat(month.days.first { it.date == padding }.hasPlan).isTrue()
        assertThat(month.plannedDayCount).isEqualTo(0)
    }

    @Test
    fun `规划 - 打卡、留言、规划三个标记互不影响`() {
        val all = LocalDate.of(2026, 10, 14)
        val onlyPlan = LocalDate.of(2026, 10, 15)
        val planAndCheckIn = LocalDate.of(2026, 10, 16)
        val month = build(
            counts = mapOf(all to 2, planAndCheckIn to 1),
            notes = setOf(all),
            plans = setOf(all, onlyPlan, planAndCheckIn)
        )

        val allDay = month.days.first { it.date == all }
        assertThat(allDay.hasCheckIn).isTrue()
        assertThat(allDay.hasNote).isTrue()
        assertThat(allDay.hasPlan).isTrue()

        val planDay = month.days.first { it.date == onlyPlan }
        assertThat(planDay.hasPlan).isTrue()
        assertThat(planDay.hasCheckIn).isFalse()
        assertThat(planDay.hasNote).isFalse()

        val checkInDay = month.days.first { it.date == planAndCheckIn }
        assertThat(checkInDay.hasPlan).isTrue()
        assertThat(checkInDay.hasCheckIn).isTrue()
        assertThat(checkInDay.hasNote).isFalse()
    }

    @Test
    fun `规划 - 只有规划没有打卡的月份不算空`() {
        // 规划是用户自己排的计划，日历上看得见就该算"有内容"
        assertThat(build().isEmpty).isTrue()
        assertThat(build(plans = setOf(LocalDate.of(2026, 10, 15))).isEmpty).isFalse()
    }

    @Test
    fun `规划 - 集合里的非本月日期不会落到任何格子`() {
        val month = build(plans = setOf(LocalDate.of(2026, 11, 5)))
        assertThat(month.days.none { it.hasPlan }).isTrue()
        assertThat(month.plannedDayCount).isEqualTo(0)
    }

    @Test
    fun `规划 - 不传参数时默认没有规划`() {
        val month = MonthCalendar.build(october, emptyMap(), today)
        assertThat(month.days.none { it.hasPlan }).isTrue()
        assertThat(month.plannedDayCount).isEqualTo(0)
        // 默认值不影响 isEmpty 的既有口径
        assertThat(month.isEmpty).isTrue()
    }

    @Test
    fun `规划 - 留言与规划参数可以各自独立传（命名参数）`() {
        val month = MonthCalendar.build(
            yearMonth = october,
            checkInCounts = emptyMap(),
            today = today,
            noteDates = setOf(LocalDate.of(2026, 10, 14)),
            planDates = setOf(LocalDate.of(2026, 10, 15))
        )
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 14) }.hasNote).isTrue()
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 14) }.hasPlan).isFalse()
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 15) }.hasPlan).isTrue()
        assertThat(month.days.first { it.date == LocalDate.of(2026, 10, 15) }.hasNote).isFalse()
    }
}
