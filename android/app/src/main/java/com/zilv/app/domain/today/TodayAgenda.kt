package com.zilv.app.domain.today

import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.plan.Plan
import java.time.LocalDate
import java.time.LocalTime

/**
 * 「今天」页的一行（v2.5）。
 *
 * 用户要求："该界面显示当天的习惯和昨天做好的规划……以时间从早到晚排序。"
 *
 * ## 为什么合成一个列表而不是两段
 *
 * "以时间从早到晚排序"这句话只有在**两种条目混在一条时间轴**上时才成立。
 * 拆成"习惯一组、规划一组"的话，09:00 的规划会被排在 10:00 的习惯后面，
 * 与用户的要求正好相反。
 *
 * ## 为什么规划**不可打卡**
 *
 * 规划是"我打算做的事"，不是"我要打卡的事"：
 *  - 点它不会写入任何打卡记录（界面不给打卡入口）
 *  - 「所有任务已完成」也只数习惯（见 [TodayAgenda.onlyHabits]）
 *
 * 这与 `Plan.toLockRule()` 的 `allowCheckInUnlock = false` 是同一条口径：
 * 规划的解锁条件只有到点解锁。
 */
sealed interface AgendaItem {

    /** 今天要打卡的一条习惯。 */
    data class HabitTask(val entry: TodayHabit) : AgendaItem {
        /**
         * 排序用的"当天第几分钟"；没有提醒时间时为 `null`（排到最后）。
         *
         * 取**最早的**提醒时间：一个习惯可以有多个提醒时刻，
         * 早的那一个才是"这一天什么时候开始"的正确答案。
         */
        override val startMinute: Int?
            get() = entry.habit.remindTimes.minOfOrNull { it.hour * 60 + it.minute }

        /** `09:00`；没有提醒时间时 `--:--`（界面显示成"随时"）。 */
        override val timeLabel: String get() = TodayAgenda.timeLabelOf(startMinute)
    }

    /** 今天生效的一条规划。 */
    data class PlanTask(val plan: Plan) : AgendaItem {
        override val startMinute: Int get() = plan.startMinute

        /** `09:00-11:00`：规划的重点是时段，只显示开始时间没有意义。 */
        override val timeLabel: String
            get() = "%02d:%02d-%02d:%02d".format(
                plan.startMinute / 60, plan.startMinute % 60,
                plan.endMinute / 60, plan.endMinute % 60
            )
    }

    /**
     * 排序用的"当天第几分钟"；没有时间的条目是 `null`（排到最后）。
     *
     * 习惯取**最早的提醒时间**（一个习惯可以有多个提醒时刻，
     * 早的那一个才是"这一天什么时候开始"的正确答案）；
     * 规划取 [Plan.startMinute]。
     */
    val startMinute: Int?

    /** 界面上显示的时段文案；没有时间时返回 `--:--`。 */
    val timeLabel: String
}

/**
 * 「今天」列表的构建规则（纯函数，可在 JVM 上完全确定地验证）。
 *
 * 用户原话：
 *  - "该界面显示当天的习惯和昨天做好的规划"
 *  - "当天不需要打卡的习惯不显示"
 *  - "以时间从早到晚排序"
 *
 * 对应到这里就是三件事：
 *  1. **过滤**：只保留今天真的要打卡的习惯（见 [needsCheckInToday]）
 *  2. **合并**：习惯与今天生效的规划进同一个列表
 *  3. **排序**：按 [AgendaItem.startMinute] 升序，没有时间的排最后
 *
 * ## "当天不需要打卡的习惯不显示"是怎么判的
 *
 * 唯一的情况是**计划日不是今天**：`SpecificDays` 只选了周一三五，那周二就不该出现；
 * `SpecificMonthDays` 只选了 1 号与 15 号，那 14 号就不该出现
 * （**4 月只选了 31 号时，4 月整月都不出现** —— 当月没有这个日期）。
 *
 * 这条判据由 [com.zilv.app.domain.habit.HabitFrequency.needsCheckInOn] 表达，
 * 这里不再自己写一套判断：口径只能有一个地方（第 1 批已经用 13 条单测钉住它）。
 *
 * v2.13：「每月 N 次」已被「指定每月几号」取代，所以这里不再有"本月做满 N 次"
 * 这一支 —— 新频率的过滤条件回到与「指定星期」同一条判据（今天是不是计划日）。
 *
 * v2.14：「每周 N 次」已删除，于是"本周额度用完就不催"这一支也一并消失 ——
 * 现在剩下的三种频率都只回答"今天是不是计划日"，没有周期配额这个概念。
 *
 * 已经打过卡的习惯**仍然留在列表里**（打上勾显示"已完成"），
 * 只是不参与"接下来该做"的挑选 —— [com.zilv.app.domain.habit.NowNextPicker]
 * 自己会跳过已打卡的项。
 */
object TodayAgenda {

    /**
     * 构建今天的列表。
     *
     * @param habits 今天**可能**要打卡的习惯（通常是全部未归档习惯，
     *        由 `HabitRepository.observeActiveHabits` 给出）；
     *        "今天不需要打卡"的在这里被过滤掉
     * @param plans 今天生效的规划（通常是 `PlanRepository.getForDay(today)`）
     * @param date 今天（由注入的时钟决定，测试可固定）
     * @param checkedDatesByHabit 每条习惯**全部**已打卡的日期集合（按 habitId 索引）。
     *        用于判断"本周/本月的 N 次额度有没有用完"；没有条目时按空集合处理
     */
    fun build(
        habits: List<TodayHabit>,
        plans: List<Plan>,
        date: LocalDate,
        checkedDatesByHabit: Map<Long, Set<LocalDate>> = emptyMap()
    ): List<AgendaItem> {
        val habitItems = habits
            .filter {
                needsCheckInToday(it, date, checkedDatesByHabit[it.habit.id] ?: emptySet())
            }
            .map { AgendaItem.HabitTask(it) }

        // 只收当天有效的规划：`plans` 理论上已经是当天的，但这里是**纯函数**，
        // 不该假设调用方一定筛过（脏数据里混进别的日期时不能画到今天页上）。
        // 非法窗口（起止相同＝全天 / 跨 0 点）同样挡在外面。
        val planItems = plans
            .filter { it.date == date && it.isValid }
            .map { AgendaItem.PlanTask(it) }

        return (habitItems + planItems).sortedWith(AGENDA_ORDER)
    }

    /**
     * 这条习惯今天是否需要出现在「今天」页上。
     *
     * 会**出现**的情况：
     *  1. 今天是它的计划日，且这个周期的额度还没用完
     *  2. 今天已经打过卡 —— 仍要显示（打上勾表示"今天这件事有过"），
     *     只是不参与"接下来该做"的挑选
     *  3. `checkedDates` 传 `null`（＝未知）时保守放行
     *
     * **不出现**的情况（用户要求"当天不需要打卡的习惯不显示"）：
     *  - 今天不是计划日（`SpecificDays` 只选了周一三五，今天是周二；
     *    `SpecificMonthDays` 只选了 1 号与 15 号，今天是 14 号；
     *    当月没有选中的日期时整月都不出现 —— 例如只选 31 号的习惯在 4 月不出现）
     *
     * 这一条**复用** [com.zilv.app.domain.habit.HabitFrequency.needsCheckInOn]，
     * 不在这里另写一套：口径只能有一个地方（那里有 13 条单测钉着）。
     *
     * ⚠️ 想表达"确实没有任何打卡"要传**空集合**；`null` 的含义是"查不到"。
     *
     * @param checkedDates 这条习惯全部已打卡的日期；`null` 表示"还没查"
     */
    fun needsCheckInToday(
        entry: TodayHabit,
        date: LocalDate,
        checkedDates: Set<LocalDate>? = null
    ): Boolean {
        val habit = entry.habit
        if (!habit.frequency.isScheduledOn(date)) return false
        if (entry.checkedToday) return true
        val dates = checkedDates ?: return true
        return habit.frequency.needsCheckInOn(date, dates)
    }

    /** 只取习惯条目 —— 「所有任务已完成」的判定用它（规划不可打卡）。 */
    fun onlyHabits(items: List<AgendaItem>): List<AgendaItem.HabitTask> =
        items.filterIsInstance<AgendaItem.HabitTask>()

    /** 时段文案：`09:00`；没有时间时是 `--:--`（界面据此显示"随时"）。 */
    fun timeLabelOf(minuteOfDay: Int?): String =
        minuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "--:--"

    /**
     * 排序规则，**逐条都是确定的**：
     *
     *  1. 有时间在前、没时间在后（"没有提醒时间的排最后"）
     *  2. 时间早的在前
     *  3. 同一时刻**规划在前**：规划是"几点到几点做这件事"的框架，
     *     习惯是"这个点提醒一下"的补充，先读框架更顺
     *  4. 再平手就比名字，保证输出**完全稳定**（同名的按类型不会互串）
     *
     * 第 3 条的次键刻意不是"插入顺序"：习惯与规划来自两张表，
     * 用插入顺序当次键会让列表在数据一变就重排。
     */
    private val AGENDA_ORDER: Comparator<AgendaItem> =
        compareBy<AgendaItem> { it.startMinute ?: Int.MAX_VALUE }
            .thenBy { if (it is AgendaItem.PlanTask) 0 else 1 }
            .thenBy { nameOf(it) }

    private fun nameOf(item: AgendaItem): String = when (item) {
        is AgendaItem.HabitTask -> item.entry.habit.name
        is AgendaItem.PlanTask -> item.plan.name
    }
}

/** 一个习惯今天的起始时刻 = 最早的提醒时间；没有提醒时间时是 null。 */
val TodayHabit.earliestRemindMinute: Int?
    get() = habit.remindTimes.minOfOrNull { it.hour * 60 + it.minute }

/** 把「当天第几分钟」还原成 [LocalTime]。 */
fun minuteToLocalTime(minuteOfDay: Int): LocalTime =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
