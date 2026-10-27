package com.zilv.app.domain.records

import java.time.LocalDate

/**
 * 打卡日历热力图的数据构建（PRD §3.1 / §4.4）。
 *
 * 抽成纯函数是为了能确定性地做单元测试：日期网格的起点、跨月跨年、
 * 每天所在列/行、以及"今天之后不显示"这些边界很容易写错。
 *
 * 网格约定：**列 = 自然周（周一为起点），行 = 周一..周日**。
 */
object HeatmapBuilder {

    /** 热力等级 0..4，由每日打卡条数映射而来。 */
    const val MAX_LEVEL = 4

    data class Cell(
        val date: LocalDate,
        val count: Int
    ) {
        val level: Int get() = levelOf(count)
    }

    /** 一列（一周）。7 个位置固定对应周一..周日；范围外为 null。 */
    data class Week(
        val weekStart: LocalDate,
        val days: List<Cell?>
    ) {
        init {
            require(days.size == 7) { "一周必须有 7 个格子，实际 ${days.size}" }
        }
    }

    /**
     * 把每日打卡条数映射为 0..4 的等级。
     *
     * 0 条 = 0；1 条 = 1；2 条 = 2；3 条 = 3；≥4 条 = 4。
     */
    fun levelOf(count: Int): Int = when {
        count <= 0 -> 0
        count >= MAX_LEVEL -> MAX_LEVEL
        else -> count
    }

    /**
     * 构建最近 [weeks] 周的热力图网格，**最后一列包含 [endDate]**。
     *
     * [endDate] 之后的日子不会出现在网格里（即使在同一周内，也留 null）——
     * 未来不应该显示为"没打卡"。
     */
    fun build(
        counts: Map<LocalDate, Int>,
        endDate: LocalDate,
        weeks: Int = 13
    ): List<Week> {
        require(weeks > 0) { "weeks 必须为正数，实际 $weeks" }

        val lastWeekStart = mondayOf(endDate)
        val firstWeekStart = lastWeekStart.minusWeeks((weeks - 1).toLong())

        return (0 until weeks).map { offset ->
            val weekStart = firstWeekStart.plusWeeks(offset.toLong())
            val days = (0 until 7).map { dayOffset ->
                val date = weekStart.plusDays(dayOffset.toLong())
                when {
                    date.isAfter(endDate) -> null
                    else -> Cell(date = date, count = counts[date] ?: 0)
                }
            }
            Week(weekStart = weekStart, days = days)
        }
    }

    /** 某个日期所在周的周一。 */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())
}
