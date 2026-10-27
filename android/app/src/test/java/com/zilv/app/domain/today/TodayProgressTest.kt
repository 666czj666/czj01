package com.zilv.app.domain.today

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.habit.StreakStats
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.plan.Plan
import org.junit.Test
import java.time.LocalDate

/**
 * [TodayProgressRules] 的单元测试（v2.12）。
 *
 * 用户原话：「留言条件改为『**有习惯完成 或 规划结束**』」。
 *
 * 这个文件把"有进展"这件事逐条钉死 —— 它是**纯函数**，不碰数据库、不碰时钟，
 * 所以「当天」的口径（哪一天的规划算数、拿什么时刻比）必须在这里讲清楚，
 * 而不是散在 ViewModel 与界面里各写一遍。
 *
 * 基准日期沿用本项目其它测试的口径：**2026-10-14 是周三**。
 */
class TodayProgressTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val yesterday: LocalDate = LocalDate.of(2026, 10, 13)

    /** 16:00 —— 与 `TodayViewModelTest` 固定时钟的本地时刻一致。 */
    private val nowMinute: Int = 16 * 60

    private fun entry(name: String, checked: Boolean) = TodayHabit(
        habit = Habit(id = 0L, name = name, frequency = HabitFrequency.Daily, createdAt = 0L),
        checkedToday = checked,
        stats = StreakStats.EMPTY
    )

    private fun plan(
        name: String,
        date: LocalDate = today,
        startMinute: Int = 9 * 60,
        endMinute: Int = 11 * 60
    ) = Plan(name = name, date = date, startMinute = startMinute, endMinute = endMinute)

    private fun progress(
        habits: List<TodayHabit> = emptyList(),
        plans: List<Plan> = emptyList(),
        nowMinuteOfDay: Int = nowMinute
    ) = TodayProgressRules.evaluate(habits, plans, today, nowMinuteOfDay)

    // ------------------------------------------------------------ 空场景

    @Test
    fun `什么都没有时既没进展也没全完成`() {
        val p = progress()
        assertThat(p.hasProgress).isFalse()
        assertThat(p.allHabitsChecked).isFalse()
        assertThat(p.checkedHabitCount).isEqualTo(0)
        assertThat(p.totalHabitCount).isEqualTo(0)
        assertThat(p.endedPlanCount).isEqualTo(0)
    }

    /**
     * 这条是"全部完成"那条老不变式的续命钉子：
     * 一条习惯都没有时 `all {}` 对空集合恒为 true，
     * 不显式拦下的话刚装好的 App 首页会挂着「所有任务已完成」。
     */
    @Test
    fun `没有习惯时全完成为假 —— 空集合不算全完成`() {
        assertThat(progress(plans = listOf(plan("读书"))).allHabitsChecked).isFalse()
    }

    // ------------------------------------------------- 有习惯完成 = 有进展

    @Test
    fun `完成一条习惯就算有进展`() {
        val p = progress(habits = listOf(entry("早起", checked = true)))
        assertThat(p.hasProgress).isTrue()
        assertThat(p.anyHabitChecked).isTrue()
        assertThat(p.allHabitsChecked).isTrue()
    }

    /** **本次改动的核心**：只完成了一部分也算有进展（旧规格要求"全部完成"）。 */
    @Test
    fun `只完成一部分习惯也算有进展 —— 不必全部完成`() {
        val p = progress(
            habits = listOf(entry("早起", checked = true), entry("读书", checked = false))
        )
        assertThat(p.hasProgress).isTrue()
        assertThat(p.anyHabitChecked).isTrue()
        // 但"全完成"仍然是假 —— 两档必须分得开
        assertThat(p.allHabitsChecked).isFalse()
        assertThat(p.checkedHabitCount).isEqualTo(1)
        assertThat(p.totalHabitCount).isEqualTo(2)
    }

    @Test
    fun `一条都没完成时不算有进展`() {
        val p = progress(
            habits = listOf(entry("早起", checked = false), entry("读书", checked = false))
        )
        assertThat(p.hasProgress).isFalse()
        assertThat(p.allHabitsChecked).isFalse()
    }

    // --------------------------------------------------- 规划结束 = 有进展

    @Test
    fun `规划已过结束时刻就算有进展 —— 即使一条习惯都没打`() {
        val p = progress(plans = listOf(plan("读书", startMinute = 9 * 60, endMinute = 11 * 60)))
        assertThat(p.endedPlanCount).isEqualTo(1)
        assertThat(p.anyPlanEnded).isTrue()
        assertThat(p.hasProgress).isTrue()
        // 习惯一条都没打 —— 全完成仍然是假
        assertThat(p.allHabitsChecked).isFalse()
    }

    @Test
    fun `规划结束时刻还没到时不算有进展`() {
        // 16:00 时，一条 17:00-18:00 的规划还没结束
        val p = progress(plans = listOf(plan("运动", startMinute = 17 * 60, endMinute = 18 * 60)))
        assertThat(p.endedPlanCount).isEqualTo(0)
        assertThat(p.hasProgress).isFalse()
    }

    /**
     * 端点语义：`Plan` 的区间是半开的 `[start, end)`，
     * 所以第 `endMinute` 分钟已经不属于这条规划 —— 那一刻就是"结束"。
     */
    @Test
    fun `恰好等于结束时刻就算结束 —— 闭端点`() {
        val p = progress(
            plans = listOf(plan("读书", startMinute = 9 * 60, endMinute = 16 * 60)),
            nowMinuteOfDay = 16 * 60
        )
        assertThat(p.endedPlanCount).isEqualTo(1)
    }

    @Test
    fun `结束时刻前一分钟还不算结束`() {
        val p = progress(
            plans = listOf(plan("读书", startMinute = 9 * 60, endMinute = 16 * 60)),
            nowMinuteOfDay = 16 * 60 - 1
        )
        assertThat(p.endedPlanCount).isEqualTo(0)
    }

    // ------------------------------------------------------- "当天"的口径

    /**
     * 这是"当天"口径的钉子：规划不跨 0 点，昨天的规划在昨天就已经结束了。
     * 把它算进来的话，每天早上打开 App 就白送一个留言入口。
     */
    @Test
    fun `昨天的规划不算今天的进展`() {
        val p = progress(plans = listOf(plan("昨天的读书", date = yesterday)))
        assertThat(p.endedPlanCount).isEqualTo(0)
        assertThat(p.hasProgress).isFalse()
    }

    @Test
    fun `明天还没到的规划不算今天的进展`() {
        val p = progress(
            plans = listOf(plan("明天的读书", date = today.plusDays(1)))
        )
        assertThat(p.endedPlanCount).isEqualTo(0)
        assertThat(p.hasProgress).isFalse()
    }

    /** 脏数据（起止相同＝会被当成全天锁机）不参与判定 —— 与首页列表同一口径。 */
    @Test
    fun `窗口非法的规划不算进展`() {
        val p = progress(plans = listOf(plan("坏数据", startMinute = 9 * 60, endMinute = 9 * 60)))
        assertThat(p.endedPlanCount).isEqualTo(0)
        assertThat(p.hasProgress).isFalse()
    }

    // --------------------------------------------------------- 两条一起算

    @Test
    fun `习惯与规划各自都能单独撑起留言入口`() {
        // 只有规划结束
        assertThat(progress(plans = listOf(plan("读书"))).hasProgress).isTrue()
        // 只有习惯完成
        assertThat(
            progress(habits = listOf(entry("早起", checked = true))).hasProgress
        ).isTrue()
    }

    @Test
    fun `多条规划里只要有一条结束就算有进展`() {
        val p = progress(
            plans = listOf(
                plan("晚一点", startMinute = 17 * 60, endMinute = 18 * 60),
                plan("早一点", startMinute = 8 * 60, endMinute = 9 * 60)
            )
        )
        assertThat(p.endedPlanCount).isEqualTo(1)
        assertThat(p.hasProgress).isTrue()
    }
}
