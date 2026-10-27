package com.zilv.app.domain.today

import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.plan.Plan
import java.time.LocalDate

/**
 * 「今天有没有进展」的判定结果（v2.12）。
 *
 * ## 用户要求
 *
 * > 「留言条件改为『有习惯完成 或 规划结束』。现在是『当天所有习惯都打卡完成』
 * > 才出现绿底横幅、点它才能留言。」
 *
 * 于是判定拆成两个**互相独立**的维度：
 *  - [allHabitsChecked]：**所有**习惯都打卡完成 —— 绿底横幅「所有任务已完成」
 *    这一档观感是用户明确要求保留的
 *  - [hasProgress]：**有**习惯完成，**或**有规划已经结束 —— 只要它成立，
 *    留言入口就必须在
 *
 * 横幅文案由界面按这三个计数分档（全完成 / 部分完成 / 规划结束），
 * 所以「没全完成却说『所有任务已完成』」在结构上不可能发生：
 * 文案只认 [allHabitsChecked]，而它要求 `checked == total > 0`。
 */
data class TodayProgress(
    /** 今天**要打卡**且已打卡的习惯条数。 */
    val checkedHabitCount: Int = 0,
    /** 今天要打卡的习惯总条数。 */
    val totalHabitCount: Int = 0,
    /** 今天**已经**过了结束时刻的规划条数。 */
    val endedPlanCount: Int = 0
) {

    /** 至少完成了一条习惯。 */
    val anyHabitChecked: Boolean get() = checkedHabitCount > 0

    /**
     * 所有习惯都打卡完成。
     *
     * ⚠️ **必须至少有一条习惯** —— 一条习惯都没有时 `all {}` 对空集合恒为 true，
     * 那会让一个刚装好、什么都没配的 App 首页挂着「所有任务已完成」。
     */
    val allHabitsChecked: Boolean get() = totalHabitCount > 0 && checkedHabitCount == totalHabitCount

    /** 至少有一条规划已经过了它的结束时刻。 */
    val anyPlanEnded: Boolean get() = endedPlanCount > 0

    /**
     * **今天有进展** —— 留言入口的判据。
     *
     * 「有习惯完成 或 规划结束」，注意是**或**：
     * 只完成了一部分习惯照样算有进展（这正是本次改动要修的东西）。
     */
    val hasProgress: Boolean get() = anyHabitChecked || anyPlanEnded

    companion object {
        /** 什么都没有：既没有完成的习惯，也没有结束的规划。 */
        val NONE = TodayProgress()
    }
}

/**
 * [TodayProgress] 的计算规则（**纯函数**，可在 JVM 上完全确定地验证）。
 *
 * ## 「有习惯完成」的口径
 *
 * 只看**今天要打卡的那一份**（也就是首页列表里的习惯），并且只看
 * [TodayHabit.checkedToday] —— 与首页列表、与聚焦卡片同一个来源
 * （`TodayAgenda` 已经把"今天不需要打卡的习惯"过滤掉了）。
 *
 * 「完成」定义成 `checkedToday`，与 [TodayProgress.allHabitsChecked] 用的是
 * **同一个标记**，所以不会出现"计数说完成了 2 条、全完成判定却说没完成"的分叉。
 *
 * ## 「规划结束」的口径 —— 明确"当天"
 *
 * 用户原话：「规划『结束』的判定 = 此刻已经过了它的结束时刻（`Plan.endMinute`）」。
 * 这里把"当天"钉成两条：
 *
 *  1. **只算 `plan.date == date` 的规划**。规划不跨 0 点（[Plan.hasValidWindow]
 *     要求 `endMinute > startMinute`），所以昨天的规划在昨天就已经结束了 ——
 *     把它算进来等于每天早上打开 App 就白送一个留言入口。
 *  2. **用"当天第几分钟"比较，不用 `LocalDateTime`**：`date` 由调用方给定
 *     （首页用注入的时钟算出来的 `today`），`nowMinuteOfDay` 是同一时刻的
 *     "当天第几分钟"。两者同源，所以跨过 0 点时不会出现"日期是新的、分钟是旧的"。
 *
 * 比较是 `nowMinuteOfDay >= endMinute`（**闭**端点）：`Plan` 的时间区间是
 * 半开的 `[startMinute, endMinute)`，第 `endMinute` 分钟已经不属于这条规划了，
 * 那一刻就是"结束"。
 *
 * ## 为什么规划必须 `isValid`
 *
 * 与 [TodayAgenda.build] 同一口径：窗口非法（起止相同＝全天 / 跨 0 点）的脏数据
 * 不该参与任何判定。首页列表本来就不会画它，这里也不该拿它给留言入口。
 */
object TodayProgressRules {

    /**
     * 算一次今日进展。
     *
     * @param habits 今天**要打卡**的习惯（首页列表里那一份，已过滤掉
     *        "今天不需要打卡"的）
     * @param plans 今天**生效**的规划（首页列表里那一份）
     * @param date 今天（由注入的时钟决定）
     * @param nowMinuteOfDay 此刻是当天的第几分钟（`hour * 60 + minute`）；
     *        可以大于 1439 也无所谓 —— 比较只是 `>=`
     */
    fun evaluate(
        habits: List<TodayHabit>,
        plans: List<Plan>,
        date: LocalDate,
        nowMinuteOfDay: Int
    ): TodayProgress = TodayProgress(
        checkedHabitCount = habits.count { it.checkedToday },
        totalHabitCount = habits.size,
        endedPlanCount = plans.count { plan ->
            plan.date == date && plan.isValid && nowMinuteOfDay >= plan.endMinute
        }
    )
}
