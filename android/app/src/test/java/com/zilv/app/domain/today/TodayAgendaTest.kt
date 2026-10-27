package com.zilv.app.domain.today

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.habit.HabitFrequencyCodec
import com.zilv.app.domain.habit.StreakStats
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.plan.Plan
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * [TodayAgenda] 的单元测试（v2.5 的 B）。
 *
 * 用户原话：
 *  - "该界面显示当天的习惯和昨天做好的规划"
 *  - "当天不需要打卡的习惯不显示"
 *  - "以时间从早到晚排序"
 *
 * 这个文件把这三句话逐条钉死。基准日期沿用本项目其它测试的口径：
 * **2026-10-14 是周三，10-15 是周四**。
 *
 * 全部用例都能在纯 JVM 上跑：`TodayAgenda` 是纯函数，不碰数据库、不碰时钟。
 */
class TodayAgendaTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private fun habit(
        id: Long,
        name: String,
        frequency: HabitFrequency = HabitFrequency.Daily,
        remindTimes: List<LocalTime> = emptyList()
    ) = Habit(
        id = id,
        name = name,
        frequency = frequency,
        remindTimes = remindTimes,
        // createdAt 固定，避免它参与任何比较时抖动
        createdAt = 0L
    )

    private fun entry(habit: Habit, checkedToday: Boolean = false) =
        TodayHabit(habit = habit, checkedToday = checkedToday, stats = StreakStats.EMPTY)

    private fun plan(
        id: Long,
        name: String,
        date: LocalDate = today,
        startMinute: Int = 9 * 60,
        endMinute: Int = 10 * 60
    ) = Plan(id = id, name = name, date = date, startMinute = startMinute, endMinute = endMinute)

    private fun names(items: List<AgendaItem>): List<String> = items.map {
        when (it) {
            is AgendaItem.HabitTask -> it.entry.habit.name
            is AgendaItem.PlanTask -> it.plan.name
        }
    }

    // ------------------------------------------------------ 1）混排与排序

    @Test
    fun `习惯与规划按时间从早到晚混排`() = runTest {
        val items = TodayAgenda.build(
            habits = listOf(
                entry(habit(1, "十点的习惯", remindTimes = listOf(LocalTime.of(10, 0))))
            ),
            plans = listOf(plan(1, "九点的规划", startMinute = 9 * 60, endMinute = 10 * 60)),
            date = today
        )

        // 09:00 的规划必须排在 10:00 的习惯**前面** ——
        // 这正是"合成一个列表"的理由：分两段的话规划会被习惯压到后面
        assertThat(names(items)).containsExactly("九点的规划", "十点的习惯").inOrder()
        assertThat(items[0]).isInstanceOf(AgendaItem.PlanTask::class.java)
        assertThat(items[1]).isInstanceOf(AgendaItem.HabitTask::class.java)
    }

    @Test
    fun `没有提醒时间的习惯排在最后`() = runTest {
        val items = TodayAgenda.build(
            habits = listOf(
                entry(habit(1, "随时做")),
                entry(habit(2, "八点做", remindTimes = listOf(LocalTime.of(8, 0))))
            ),
            plans = listOf(plan(1, "九点的规划", startMinute = 9 * 60, endMinute = 10 * 60)),
            date = today
        )

        assertThat(names(items)).containsExactly("八点做", "九点的规划", "随时做").inOrder()
    }

    @Test
    fun `同一时刻规划在前`() = runTest {
        val items = TodayAgenda.build(
            habits = listOf(entry(habit(1, "同点习惯", remindTimes = listOf(LocalTime.of(9, 0))))),
            plans = listOf(plan(1, "同点规划", startMinute = 9 * 60, endMinute = 10 * 60)),
            date = today
        )

        assertThat(names(items)).containsExactly("同点规划", "同点习惯").inOrder()
    }

    @Test
    fun `一个习惯有多个提醒时间时取最早的那个排序`() = runTest {
        val items = TodayAgenda.build(
            habits = listOf(
                entry(
                    habit(
                        1, "多提醒",
                        remindTimes = listOf(LocalTime.of(20, 0), LocalTime.of(7, 30))
                    )
                )
            ),
            plans = emptyList(),
            date = today
        )

        assertThat(items.single().startMinute).isEqualTo(7 * 60 + 30)
        assertThat(items.single().timeLabel).isEqualTo("07:30")
    }

    @Test
    fun `排序结果稳定：输入顺序颠倒不影响输出`() = runTest {
        val a = entry(habit(1, "甲", remindTimes = listOf(LocalTime.of(9, 0))))
        val b = entry(habit(2, "乙", remindTimes = listOf(LocalTime.of(9, 0))))

        val forward = names(TodayAgenda.build(listOf(a, b), emptyList(), today))
        val backward = names(TodayAgenda.build(listOf(b, a), emptyList(), today))

        assertThat(forward).isEqualTo(backward)
    }

    // ------------------------------------------ 2）"当天不需要打卡的不显示"

    @Test
    fun `指定星期里不含今天就不显示`() = runTest {
        val mondayOnly = entry(
            habit(1, "周一才做", frequency = HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY)))
        )

        // 2026-10-14 是周三
        assertThat(TodayAgenda.needsCheckInToday(mondayOnly, today)).isFalse()
        assertThat(TodayAgenda.build(listOf(mondayOnly), emptyList(), today)).isEmpty()
    }

    @Test
    fun `指定星期里含今天就显示`() = runTest {
        val wednesday = entry(
            habit(
                1, "周三做",
                frequency = HabitFrequency.SpecificDays(setOf(DayOfWeek.WEDNESDAY))
            )
        )

        assertThat(TodayAgenda.needsCheckInToday(wednesday, today)).isTrue()
        assertThat(TodayAgenda.build(listOf(wednesday), emptyList(), today)).hasSize(1)
    }

    @Test
    fun `指定每月几号里不含今天就不显示`() = runTest {
        // v2.13：「每月 N 次」已被「指定每月几号」取代。
        // 今天是 10-14，只选了 1 号与 15 号 -> 今天不需要打卡
        val monthly = entry(
            habit(1, "每月一号十五号", frequency = HabitFrequency.SpecificMonthDays(setOf(1, 15)))
        )

        assertThat(TodayAgenda.needsCheckInToday(monthly, today)).isFalse()
        assertThat(TodayAgenda.build(listOf(monthly), emptyList(), today)).isEmpty()
    }

    @Test
    fun `指定每月几号里含今天就显示`() = runTest {
        val monthly = entry(
            habit(1, "每月十四号", frequency = HabitFrequency.SpecificMonthDays(setOf(14)))
        )

        assertThat(TodayAgenda.needsCheckInToday(monthly, today)).isTrue()
        assertThat(TodayAgenda.build(listOf(monthly), emptyList(), today)).hasSize(1)
    }

    @Test
    fun `指定每月几号：当月没有这个日期时整月都不显示`() = runTest {
        // 用户要求"当月没有31号就跳过没有的号数"：4 月没有 31 号
        // -> 只选 31 号的习惯在 4 月的任何一天都不该出现在「今天」页
        val only31 = habit(1, "每月31号", frequency = HabitFrequency.SpecificMonthDays(setOf(31)))
        val april = LocalDate.of(2026, 4, 15) // 4 月只有 30 天

        assertThat(TodayAgenda.needsCheckInToday(entry(only31), april)).isFalse()
        assertThat(TodayAgenda.build(listOf(entry(only31)), emptyList(), april)).isEmpty()
        // 而 5 月 31 号会出现
        assertThat(
            TodayAgenda.needsCheckInToday(entry(only31), LocalDate.of(2026, 5, 31))
        ).isTrue()
    }

    @Test
    fun `指定每月几号：今天已打卡仍然显示（要打勾）`() = runTest {
        val monthly = entry(
            habit(1, "每月十四号", frequency = HabitFrequency.SpecificMonthDays(setOf(14))),
            checkedToday = true
        )
        assertThat(TodayAgenda.needsCheckInToday(monthly, today)).isTrue()
    }

    @Test
    fun `指定每月几号：上个月的打卡不影响这个月，这个月打了才算数`() = runTest {
        val monthly = entry(
            habit(1, "每月十四号", frequency = HabitFrequency.SpecificMonthDays(setOf(14)))
        )
        val lastMonth = setOf(LocalDate.of(2026, 9, 14))

        // 只有上个月 14 号打过 -> 今天仍然欠
        assertThat(TodayAgenda.needsCheckInToday(monthly, today, lastMonth)).isTrue()
        // 这个月 14 号也打了 -> 今天不再欠
        val both = lastMonth + LocalDate.of(2026, 10, 14)
        assertThat(TodayAgenda.needsCheckInToday(monthly, today, both)).isFalse()
    }

    @Test
    fun `旧每周 N 次数据迁移后：计划日已打卡就不需要，非计划日也不需要`() = runTest {
        // v2.14 改写：原来这条是 `每周 N 次已经做满就不显示`，
        // 断言 `WeeklyTimes(2)` 的"本周额度已满"语义。
        // 该频率被用户删除后，"周期配额"这个概念整体不存在，
        // 归类为**测试错（旧规格）**。
        // 换成旧数据的真实迁移结果（DAYS 周一/周二/周三）：
        // 计划日打了就不需要；非计划日从来就不需要。
        val migrated = entry(
            habit(
                1,
                "旧每周三次",
                frequency = HabitFrequencyCodec.legacyWeeklyTimesToSpecificDays("2")
            )
        )
        // 2026-10-14 是周三，属于计划日（周一/周二）
        assertThat(today.dayOfWeek).isEqualTo(java.time.DayOfWeek.WEDNESDAY)
        // 今天不是计划日 -> 不需要（哪怕今天没打卡）
        assertThat(TodayAgenda.needsCheckInToday(migrated, today, emptySet())).isFalse()

        // 换到计划日（10-12 周一）：没打卡就需要，打过了就不需要
        val monday = LocalDate.of(2026, 10, 12)
        assertThat(TodayAgenda.needsCheckInToday(migrated, monday, emptySet())).isTrue()
        assertThat(
            TodayAgenda.needsCheckInToday(migrated, monday, setOf(monday))
        ).isFalse()
    }

    @Test
    fun `已经打过卡的习惯仍然显示（要打勾）`() = runTest {
        val earlyBird = entry(
            habit(1, "早起", remindTimes = listOf(LocalTime.of(7, 0))),
            checkedToday = true
        )

        val items = TodayAgenda.build(listOf(earlyBird), emptyList(), today)

        assertThat(items).hasSize(1)
        // 用户要求"显示当天的习惯"：打过的卡要看得见，否则不知道自己今天做过什么
        assertThat((items.single() as AgendaItem.HabitTask).entry.checkedToday).isTrue()
    }

    @Test
    fun `每天的习惯：今天还没打卡就显示`() = runTest {
        val daily = entry(habit(1, "喝水"))
        assertThat(TodayAgenda.needsCheckInToday(daily, today, emptySet())).isTrue()
    }

    @Test
    fun `查不到已打卡日期时保守放行（宁可多显示，也不要把该做的藏起来）`() = runTest {
        val monthly = entry(
            habit(1, "每月十四号", frequency = HabitFrequency.SpecificMonthDays(setOf(14)))
        )
        // checkedDates 传 null = 未知
        assertThat(TodayAgenda.needsCheckInToday(monthly, today, null)).isTrue()
    }

    // ------------------------------------------------------ 3）规划的过滤

    @Test
    fun `别的日期的规划不会出现在今天`() = runTest {
        val items = TodayAgenda.build(
            habits = emptyList(),
            plans = listOf(
                plan(1, "今天的", date = today),
                plan(2, "明天的", date = tomorrow)
            ),
            date = today
        )

        assertThat(names(items)).containsExactly("今天的")
    }

    @Test
    fun `非法窗口的规划（起止相同或跨 0 点）不会出现在今天`() = runTest {
        val items = TodayAgenda.build(
            habits = emptyList(),
            plans = listOf(
                // 起止相同 = 全天锁机；跨 0 点 = 规划明令禁止
                plan(1, "会议", startMinute = 10 * 60, endMinute = 10 * 60),
                plan(2, "通宵", startMinute = 22 * 60, endMinute = 6 * 60)
            ),
            date = today
        )

        assertThat(items).isEmpty()
    }

    @Test
    fun `规划的时间文案是完整时段`() = runTest {
        val items = TodayAgenda.build(
            habits = emptyList(),
            plans = listOf(plan(1, "读书", startMinute = 9 * 60 + 30, endMinute = 11 * 60)),
            date = today
        )

        assertThat(items.single().timeLabel).isEqualTo("09:30-11:00")
        assertThat(items.single().startMinute).isEqualTo(9 * 60 + 30)
    }

    // ------------------------------------------------------ 4）边界与工具

    @Test
    fun `没有任何内容时返回空列表`() = runTest {
        assertThat(TodayAgenda.build(emptyList(), emptyList(), today)).isEmpty()
    }

    @Test
    fun `onlyHabits 只取习惯条目`() = runTest {
        val items = TodayAgenda.build(
            habits = listOf(entry(habit(1, "习惯"))),
            plans = listOf(plan(1, "规划")),
            date = today
        )

        assertThat(TodayAgenda.onlyHabits(items).map { it.entry.habit.name })
            .containsExactly("习惯")
    }

    @Test
    fun `没有时间的条目时段文案是占位符`() = runTest {
        val items = TodayAgenda.build(
            habits = listOf(entry(habit(1, "随时做"))),
            plans = emptyList(),
            date = today
        )

        assertThat(items.single().startMinute).isNull()
        assertThat(items.single().timeLabel).isEqualTo("--:--")
    }

    @Test
    fun `timeLabelOf 与 minuteToLocalTime 互为逆运算`() {
        assertThat(TodayAgenda.timeLabelOf(9 * 60 + 5)).isEqualTo("09:05")
        assertThat(TodayAgenda.timeLabelOf(null)).isEqualTo("--:--")
        assertThat(minuteToLocalTime(9 * 60 + 5)).isEqualTo(LocalTime.of(9, 5))
    }
}
