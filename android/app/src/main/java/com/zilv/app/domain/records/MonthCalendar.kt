package com.zilv.app.domain.records

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历网格构建（用户要求：打卡日历改为**显示所有天数**）。
 *
 * 原先用的是 GitHub 风格的周网格，只按周铺开、没有日期数字、也不能点击。
 * 现在改为标准的月历：补齐整月每一天，首尾用邻月日期填充占位，
 * 每个格子可点击查看当天明细。
 *
 * 纯函数，与 Compose 无关，可在 JVM 上完全确定地验证。
 */
object MonthCalendar {

    /** 一个日期格。 */
    data class Day(
        val date: LocalDate,
        /** 是否属于当前展示的月份（首尾补位为 false） */
        val inMonth: Boolean,
        /** 当天的打卡条数（同一天多次打卡分别计数） */
        val checkInCount: Int,
        val isToday: Boolean,
        /**
         * 这一天有没有留言。
         *
         * 与 [hasCheckIn] **是两件独立的事**：留言是"所有任务完成后写的一句话"，
         * 但某天完全可能只留言而没打卡（或反过来）。所以格子上是两个不同的视觉标记
         * （绿底 + 小圆点），必须能同时出现。
         */
        val hasNote: Boolean = false,
        /**
         * 这一天的规划条数（v2.4）。
         *
         * 用户要求"日历上可以看见当天的规划"。与 [hasNote] 同理，
         * 规划也是**独立的第三个标记**：某天可以只有规划、没有打卡也没有留言
         * （规划本来就是"明天打算做什么"，那时还没有打卡记录）。
         *
         * 值由 [build] 的 `planDates` 集合决定，因此只可能是 0 或 1 ——
         * 月历只需要"这天有没有规划"，具体几条留给当天明细页。
         */
        val planCount: Int = 0
    ) {
        /** 用户要求：有一个或多个打卡记录的日期以绿色为底色 */
        val hasCheckIn: Boolean get() = checkInCount > 0

        /** 这一天有规划 —— 月历上要打规划标记。 */
        val hasPlan: Boolean get() = planCount > 0
    }

    /** 一个月的网格：每行 7 天。 */
    data class Month(
        val yearMonth: YearMonth,
        val weeks: List<List<Day>>
    ) {
        val days: List<Day> get() = weeks.flatten()

        /** 本月内有打卡的天数（补位日不计入）。 */
        val checkedDayCount: Int
            get() = days.count { it.inMonth && it.hasCheckIn }

        /** 本月内写了留言的天数（补位日不计入）。 */
        val notedDayCount: Int
            get() = days.count { it.inMonth && it.hasNote }

        /** 本月内有规划的天数（补位日不计入）。 */
        val plannedDayCount: Int
            get() = days.count { it.inMonth && it.hasPlan }

        /** 本月总打卡条数。 */
        val totalCheckIns: Int
            get() = days.filter { it.inMonth }.sumOf { it.checkInCount }

        /**
         * 是否整月无记录 —— 界面据此显示空状态提示。
         *
         * v2.4 起规划也算"有内容"：某天只有一条规划时，日历上明明看得见标记，
         * 却提示"这个月还没有记录"会自相矛盾（与"只有留言也不算空"同一条口径）。
         */
        val isEmpty: Boolean
            get() = totalCheckIns == 0 && notedDayCount == 0 && plannedDayCount == 0

        fun previousMonth(): YearMonth = yearMonth.minusMonths(1)
        fun nextMonth(): YearMonth = yearMonth.plusMonths(1)
    }

    /**
     * 构建某个月的完整网格。
     *
     * @param yearMonth 展示的月份
     * @param checkInCounts 按天的打卡条数（键为日期）
     * @param today 用于标记"今天"
     * @param noteDates 有留言的日期集合（默认空 —— 加这个参数前所有调用点
     *        的行为保持不变）
     * @param planDates 有规划的日期集合（v2.4，默认空 —— 同上，加参数前
     *        所有调用点的行为一字不变；所有既有调用点都用命名参数传参，
     *        已逐个核对过）
     * @param firstDayOfWeek 一周从哪天开始，默认周一（符合国内习惯）
     */
    fun build(
        yearMonth: YearMonth,
        checkInCounts: Map<LocalDate, Int>,
        today: LocalDate,
        noteDates: Set<LocalDate> = emptySet(),
        planDates: Set<LocalDate> = emptySet(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
    ): Month {
        val first = yearMonth.atDay(1)
        val last = yearMonth.atEndOfMonth()

        // 首日所在周的起始（可能落在上个月）
        val leading = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val gridStart = first.minusDays(leading.toLong())

        // 末日之后还要补几天才能凑满它所在的那一周。
        //
        // 公式必须是 "7 - 已在本周占用的位置数 - 1"，而不是简单的差值取模 ——
        // 后者在"末日是周六、周一起始"这类情况下会多补一天，
        // 导致最后一行只有 1~6 个格子（这个错误就是被单元测试抓出来的）。
        val lastIndexInWeek = ((last.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val trailing = 7 - lastIndexInWeek - 1

        // 对齐之后必然是 7 的倍数
        val totalDays = leading + last.dayOfMonth + trailing
        require(totalDays % 7 == 0) { "网格天数必须是 7 的倍数，实际 $totalDays" }

        val days = (0 until totalDays).map { offset ->
            val date = gridStart.plusDays(offset.toLong())
            Day(
                date = date,
                inMonth = YearMonth.from(date) == yearMonth,
                checkInCount = checkInCounts[date] ?: 0,
                isToday = date == today,
                hasNote = date in noteDates,
                planCount = if (date in planDates) 1 else 0
            )
        }

        return Month(yearMonth = yearMonth, weeks = days.chunked(7))
    }

    /** 表头：一 二 三 四 五 六 日（与 [build] 的 firstDayOfWeek 保持一致）。 */
    fun weekdayLabels(firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): List<String> {
        val names = mapOf(
            DayOfWeek.MONDAY to "一",
            DayOfWeek.TUESDAY to "二",
            DayOfWeek.WEDNESDAY to "三",
            DayOfWeek.THURSDAY to "四",
            DayOfWeek.FRIDAY to "五",
            DayOfWeek.SATURDAY to "六",
            DayOfWeek.SUNDAY to "日"
        )
        return (0..6).map { offset ->
            val d = DayOfWeek.of(((firstDayOfWeek.value - 1 + offset) % 7) + 1)
            names.getValue(d)
        }
    }
}
