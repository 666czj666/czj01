package com.zilv.app.domain.records

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * [HeatmapBuilder] 的单元测试。
 *
 * 日期基准：2026-10-14 是**周三**，所在周的周一是 2026-10-12。
 */
class HeatmapBuilderTest {

    private val today = LocalDate.of(2026, 10, 14) // 周三

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    @Test
    fun `mondayOf - 周一为起点且周日仍属上一周起点`() {
        assertThat(HeatmapBuilder.mondayOf(d("2026-10-12"))).isEqualTo(d("2026-10-12")) // 周一
        assertThat(HeatmapBuilder.mondayOf(d("2026-10-14"))).isEqualTo(d("2026-10-12")) // 周三
        assertThat(HeatmapBuilder.mondayOf(d("2026-10-18"))).isEqualTo(d("2026-10-12")) // 周日
        assertThat(HeatmapBuilder.mondayOf(d("2026-10-19"))).isEqualTo(d("2026-10-19")) // 下周一
    }

    @Test
    fun `levelOf - 分级边界`() {
        assertThat(HeatmapBuilder.levelOf(0)).isEqualTo(0)
        assertThat(HeatmapBuilder.levelOf(-3)).isEqualTo(0)
        assertThat(HeatmapBuilder.levelOf(1)).isEqualTo(1)
        assertThat(HeatmapBuilder.levelOf(2)).isEqualTo(2)
        assertThat(HeatmapBuilder.levelOf(3)).isEqualTo(3)
        assertThat(HeatmapBuilder.levelOf(4)).isEqualTo(4)
        assertThat(HeatmapBuilder.levelOf(99)).isEqualTo(HeatmapBuilder.MAX_LEVEL)
    }

    @Test
    fun `build - 列数等于周数且每列7格`() {
        val weeks = HeatmapBuilder.build(emptyMap(), today, weeks = 13)
        assertThat(weeks).hasSize(13)
        weeks.forEach { assertThat(it.days).hasSize(7) }
    }

    @Test
    fun `build - 最后一列包含今天`() {
        val weeks = HeatmapBuilder.build(emptyMap(), today, weeks = 4)
        val last = weeks.last()
        assertThat(last.weekStart).isEqualTo(d("2026-10-12"))
        assertThat(last.days.map { it?.date })
            .containsExactly(
                d("2026-10-12"), d("2026-10-13"), d("2026-10-14"),
                null, null, null, null // 10-15 之后是"未来"，留空
            )
            .inOrder()
    }

    @Test
    fun `build - 今天之后不出现格子`() {
        val weeks = HeatmapBuilder.build(emptyMap(), today, weeks = 4)
        val allDates = weeks.flatMap { it.days }.filterNotNull().map { it.date }
        assertThat(allDates.any { it.isAfter(today) }).isFalse()
        assertThat(allDates.max()).isEqualTo(today)
    }

    @Test
    fun `build - 第一列起点等于周数回推`() {
        val weeks = HeatmapBuilder.build(emptyMap(), today, weeks = 4)
        // 最后一列周一 10-12；往回 3 周 = 09-21
        assertThat(weeks.first().weekStart).isEqualTo(d("2026-09-21"))
    }

    @Test
    fun `build - 行与星期的对应关系正确`() {
        val weeks = HeatmapBuilder.build(emptyMap(), today, weeks = 2)
        weeks.forEach { week ->
            week.days.forEachIndexed { index, cell ->
                if (cell != null) {
                    assertThat(cell.date.dayOfWeek.value).isEqualTo(index + 1)
                    assertThat(cell.date.dayOfWeek).isEqualTo(DayOfWeek.of(index + 1))
                }
            }
        }
    }

    @Test
    fun `build - 打卡条数被正确带入并映射等级`() {
        val counts = mapOf(
            d("2026-10-14") to 2,
            d("2026-10-13") to 5,
            d("2026-10-12") to 1
        )
        val last = HeatmapBuilder.build(counts, today, weeks = 2).last()
        assertThat(last.days[0]!!.count).isEqualTo(1)
        assertThat(last.days[0]!!.level).isEqualTo(1)
        assertThat(last.days[1]!!.count).isEqualTo(5)
        assertThat(last.days[1]!!.level).isEqualTo(4)
        assertThat(last.days[2]!!.count).isEqualTo(2)
        assertThat(last.days[2]!!.level).isEqualTo(2)
    }

    @Test
    fun `build - 无记录的日期计数为0且等级为0`() {
        val last = HeatmapBuilder.build(emptyMap(), today, weeks = 2).last()
        assertThat(last.days[0]!!.count).isEqualTo(0)
        assertThat(last.days[0]!!.level).isEqualTo(0)
    }

    @Test
    fun `build - 跨月与跨年不越界`() {
        val newYear = LocalDate.of(2027, 1, 3) // 周日
        val weeks = HeatmapBuilder.build(emptyMap(), newYear, weeks = 4)
        assertThat(weeks.last().weekStart).isEqualTo(LocalDate.of(2026, 12, 28))
        assertThat(weeks.first().weekStart).isEqualTo(LocalDate.of(2026, 12, 7))
        val dates = weeks.flatMap { it.days }.filterNotNull().map { it.date }
        assertThat(dates.min()).isEqualTo(LocalDate.of(2026, 12, 7))
        assertThat(dates.max()).isEqualTo(newYear)
    }

    @Test
    fun `build - weeks 为1时只有一列`() {
        val weeks = HeatmapBuilder.build(emptyMap(), today, weeks = 1)
        assertThat(weeks).hasSize(1)
        assertThat(weeks.single().weekStart).isEqualTo(d("2026-10-12"))
    }

    @Test
    fun `build - weeks 非正数抛出异常`() {
        var threw = false
        try {
            HeatmapBuilder.build(emptyMap(), today, weeks = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }
}
