package com.zilv.app.domain.habit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek

/**
 * [HabitFrequencyCodec] 的单元测试。
 *
 * 重点验证三件事：
 *  1. **往返一致性**（编码 → 解码 → 相等）
 *  2. **脏数据不得抛异常**（数据库里出现无法解析的内容时，
 *     宁可退化为「每天」，也不能让列表页崩溃）
 *  3. **旧编码 `MONTHLY:n` 的兼容**（v2.13 删掉了「每月 N 次」这个类型，
 *     但存量数据库与旧备份里一定有它 —— 详见下面那一组测试与
 *     [HabitFrequencyCodec] 类 KDoc 里的兼容表）
 */
class HabitFrequencyCodecTest {

    private fun roundTrip(f: HabitFrequency): HabitFrequency =
        HabitFrequencyCodec.decode(
            HabitFrequencyCodec.typeOf(f),
            HabitFrequencyCodec.payloadOf(f)
        )

    @Test
    fun `每天 - 往返一致`() {
        assertThat(roundTrip(HabitFrequency.Daily)).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.typeOf(HabitFrequency.Daily))
            .isEqualTo(HabitFrequencyCodec.TYPE_DAILY)
        assertThat(HabitFrequencyCodec.payloadOf(HabitFrequency.Daily)).isEmpty()
    }

    // ================================================ v2.14：删除「每周 N 次」
    //
    // 用户原话：「打卡频率里删除每周 N 次。」
    //
    // ⚠️ 改写说明（本文件里 5 条旧断言）：
    // 它们断言的是 `HabitFrequency.WeeklyTimes` 的**编码往返**
    // （`typeOf` 返回 `WEEKLY`、`payloadOf` 返回 `"3"`、解码得到 `WeeklyTimes(n)`）。
    // 这个类型已被用户明确删除，所以归类全部是**测试错（旧规格）**。
    // 换成下面的断言：**旧编码 `WEEKLY:n` 仍然能读**（不崩、不丢数据），
    // 但**永远不会再被写出来** —— 这正是"删掉选项"与"别弄坏旧数据"的交点。

    @Test
    fun `旧编码 WEEKLY - 映射成指定星期的周一到第 n 天`() {
        assertThat(HabitFrequencyCodec.decode(HabitFrequencyCodec.TYPE_WEEKLY, "3"))
            .isEqualTo(
                HabitFrequency.SpecificDays(
                    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
                )
            )
        assertThat(HabitFrequencyCodec.decode(HabitFrequencyCodec.TYPE_WEEKLY, "1"))
            .isEqualTo(HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY)))
        assertThat(HabitFrequencyCodec.decode(HabitFrequencyCodec.TYPE_WEEKLY, "7"))
            .isEqualTo(HabitFrequency.SpecificDays(DayOfWeek.values().toSet()))
    }

    @Test
    fun `旧编码 WEEKLY - 次数不变（这是选择这个映射的核心理由）`() {
        // 旧语义"每周 3 次" → 新频率一周 3 个计划日，打卡负担一模一样
        val decoded = HabitFrequencyCodec.decode(HabitFrequencyCodec.TYPE_WEEKLY, "3")
        assertThat((decoded as HabitFrequency.SpecificDays).days).hasSize(3)
        // 映射结果与"用户在界面上手选前 n 个星期"逐字节相同
        assertThat(HabitFrequencyCodec.payloadOf(decoded)).isEqualTo("1,2,3")
    }

    @Test
    fun `旧编码 WEEKLY - 不再被写成新编码（只读不写）`() {
        // v2.14 之后永远不会再产生 WEEKLY:xxx —— 写出去的一定是 DAILY / DAYS /
        // MONTH_DAYS 三者之一。这条防止有人"顺手"把旧格式加回编码侧。
        listOf(
            HabitFrequency.Daily,
            HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY)),
            HabitFrequency.SpecificMonthDays(setOf(1))
        ).forEach { f ->
            assertThat(HabitFrequencyCodec.typeOf(f))
                .isNotEqualTo(HabitFrequencyCodec.TYPE_WEEKLY)
        }
    }

    @Test
    fun `指定星期 - 往返一致`() {
        val f = HabitFrequency.SpecificDays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        assertThat(roundTrip(f)).isEqualTo(f)
        assertThat(HabitFrequencyCodec.typeOf(f)).isEqualTo(HabitFrequencyCodec.TYPE_DAYS)
    }

    @Test
    fun `指定星期 - 含周日的七天也能往返一致`() {
        // 用户要求"指定星期加上周日选项"：数据层从一开始就支持 SUNDAY（ISO 值 7），
        // 这条钉死"七天全都存得下、读得回"
        val all = HabitFrequency.SpecificDays(DayOfWeek.values().toSet())
        assertThat(roundTrip(all)).isEqualTo(all)
        assertThat(HabitFrequencyCodec.payloadOf(all)).isEqualTo("1,2,3,4,5,6,7")
        // 只选周日
        val sunday = HabitFrequency.SpecificDays(setOf(DayOfWeek.SUNDAY))
        assertThat(HabitFrequencyCodec.payloadOf(sunday)).isEqualTo("7")
        assertThat(roundTrip(sunday)).isEqualTo(sunday)
    }

    @Test
    fun `指定星期 - 载荷按星期序号升序且不受输入顺序影响`() {
        val a = HabitFrequency.SpecificDays(
            setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        )
        val b = HabitFrequency.SpecificDays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        assertThat(HabitFrequencyCodec.payloadOf(a)).isEqualTo("1,3,5")
        assertThat(HabitFrequencyCodec.payloadOf(b)).isEqualTo("1,3,5")
    }

    @Test
    fun `旧编码 WEEKLY - 越界或非法数值退化为每天`() {
        // 与 v1 的行为**逐字相同**：越界/非数字在旧版也是退化为「每天」，
        // 所以旧库里的脏数据行为没有任何变化（断言本身一条未改，只改了名字）
        assertThat(HabitFrequencyCodec.decode("WEEKLY", "0")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("WEEKLY", "8")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("WEEKLY", "abc")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("WEEKLY", "")).isEqualTo(HabitFrequency.Daily)
    }

    @Test
    fun `旧编码 WEEKLY - 合法边界被接受`() {
        // v2.14 改写：解码结果从 `WeeklyTimes(n)` 变成「指定星期 周一..第 n 天」
        assertThat(HabitFrequencyCodec.decode("WEEKLY", "1"))
            .isEqualTo(HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY)))
        assertThat(HabitFrequencyCodec.decode("WEEKLY", "7"))
            .isEqualTo(HabitFrequency.SpecificDays(DayOfWeek.values().toSet()))
    }

    @Test
    fun `指定星期 - 非法序号被过滤`() {
        assertThat(HabitFrequencyCodec.decode("DAYS", "1,9,0,abc,3"))
            .isEqualTo(
                HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
            )
    }

    @Test
    fun `指定星期 - 全部非法则退化为每天`() {
        assertThat(HabitFrequencyCodec.decode("DAYS", "9,0,x")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("DAYS", "")).isEqualTo(HabitFrequency.Daily)
    }

    @Test
    fun `未知类型退化为每天`() {
        assertThat(HabitFrequencyCodec.decode("SOMETHING_ELSE", "1"))
            .isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("", "")).isEqualTo(HabitFrequency.Daily)
    }

    @Test
    fun `解码容忍前后空格`() {
        // v2.14 改写：`WEEKLY` 的期望值随类型删除而变成「指定星期」
        assertThat(HabitFrequencyCodec.decode("WEEKLY", " 3 "))
            .isEqualTo(
                HabitFrequency.SpecificDays(
                    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
                )
            )
        assertThat(HabitFrequencyCodec.decode("DAYS", " 1 , 3 "))
            .isEqualTo(
                HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
            )
    }

    // ------------------------------------------------- 指定每月几号（v2.13 新）

    @Test
    fun `指定每月几号 - 往返一致`() {
        val f = HabitFrequency.SpecificMonthDays(setOf(1, 15, 31))
        assertThat(roundTrip(f)).isEqualTo(f)
        assertThat(HabitFrequencyCodec.typeOf(f))
            .isEqualTo(HabitFrequencyCodec.TYPE_MONTH_DAYS)
        assertThat(HabitFrequencyCodec.typeOf(f)).isEqualTo("MONTH_DAYS")
        assertThat(HabitFrequencyCodec.payloadOf(f)).isEqualTo("1,15,31")
    }

    @Test
    fun `指定每月几号 - 载荷升序且不受输入顺序影响`() {
        val a = HabitFrequency.SpecificMonthDays(setOf(31, 1, 15))
        val b = HabitFrequency.SpecificMonthDays(setOf(15, 31, 1))
        assertThat(HabitFrequencyCodec.payloadOf(a)).isEqualTo("1,15,31")
        assertThat(HabitFrequencyCodec.payloadOf(b)).isEqualTo("1,15,31")
    }

    @Test
    fun `指定每月几号 - 只选 31 号能往返一致`() {
        val only31 = HabitFrequency.SpecificMonthDays(setOf(31))
        assertThat(HabitFrequencyCodec.payloadOf(only31)).isEqualTo("31")
        assertThat(roundTrip(only31)).isEqualTo(only31)
    }

    @Test
    fun `指定每月几号 - 越界日期被过滤，全非法则退化为每天`() {
        // 关键：越界值**不能**走到 SpecificMonthDays 的构造函数（那里会抛异常），
        // 脏数据必须安全过滤/退化
        assertThat(HabitFrequencyCodec.decode("MONTH_DAYS", "0,32,99"))
            .isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTH_DAYS", "abc"))
            .isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTH_DAYS", ""))
            .isEqualTo(HabitFrequency.Daily)
        // 部分合法：合法的留下
        assertThat(HabitFrequencyCodec.decode("MONTH_DAYS", "0,1,32,31"))
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 31)))
        assertThat(HabitFrequencyCodec.decode("MONTH_DAYS", "-5,15"))
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(15)))
    }

    @Test
    fun `指定每月几号 - 解码容忍前后空格`() {
        assertThat(HabitFrequencyCodec.decode("MONTH_DAYS", " 1 , 15 "))
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 15)))
    }

    @Test
    fun `指定每月几号 - 类型常量与模型的边界保持同一个来源`() {
        assertThat(HabitFrequencyCodec.MONTH_DAY_MIN)
            .isEqualTo(HabitFrequency.SpecificMonthDays.MIN_DAY)
        assertThat(HabitFrequencyCodec.MONTH_DAY_MAX)
            .isEqualTo(HabitFrequency.SpecificMonthDays.MAX_DAY)
        assertThat(HabitFrequencyCodec.MONTH_DAY_MAX).isEqualTo(31)
    }

    // ------------------------------- 向后兼容：旧的 `MONTHLY:n`（每月 N 次）
    //
    // v2.13 删除了 HabitFrequency.MonthlyTimes，但**存量数据库与旧备份里一定有
    // `MONTHLY:n`**。处理方式：映射成「指定每月 1..n 号」。
    //
    // 为什么这样映射（完整论证在 HabitFrequencyCodec 的类 KDoc 里）：
    //  1. 次数不变 —— 旧语义"每月做够 N 次"，新频率选 n 个日期就是每月 n 次
    //  2. 永远不会跳过 —— n ≤ 28（v2.4 的保证），任何月份都有 1..28 号
    //  3. 确定且可测 —— 同一条旧数据每次解码结果一致，界面文案会明确变成
    //     "每月 1、2、3 号"，用户一眼看得出旧配置被改成了什么

    @Test
    fun `旧编码 MONTHLY - 映射成指定每月 1 到 n 号`() {
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "3"))
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 2, 3)))
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "1"))
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1)))
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "28"))
            .isEqualTo(HabitFrequency.SpecificMonthDays((1..28).toSet()))
    }

    @Test
    fun `旧编码 MONTHLY - 次数不变（这是选择这个映射的核心理由）`() {
        // 旧语义"每月 10 次" → 新频率每月 10 个日期，打卡次数一模一样
        val decoded = HabitFrequencyCodec.decode("MONTHLY", "10")
        assertThat((decoded as HabitFrequency.SpecificMonthDays).days).hasSize(10)
    }

    @Test
    fun `旧编码 MONTHLY - 映射出来的日期在任何月份都排得下，不会整月跳过`() {
        // v2.4 保证 n ≤ 28，而任何自然月都有 1..28 号 —— 所以映射后
        // 绝不会出现"某个月整月不需要打卡"这种意外
        val decoded = HabitFrequencyCodec.decode("MONTHLY", "28")
        val days = (decoded as HabitFrequency.SpecificMonthDays).days
        listOf(2026, 2027, 2028).forEach { year ->
            (1..12).forEach { month ->
                val lastDay = java.time.YearMonth.of(year, month).lengthOfMonth()
                assertThat(days.all { it <= lastDay }).isTrue()
            }
        }
    }

    @Test
    fun `旧编码 MONTHLY - 越界或非法数值退化为每天（不抛异常）`() {
        // v2.4 里这些值同样退化为「每天」，所以旧库里的脏数据行为没有变化
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "0")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "-1")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "32")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "10000")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "abc")).isEqualTo(HabitFrequency.Daily)
        assertThat(HabitFrequencyCodec.decode("MONTHLY", "")).isEqualTo(HabitFrequency.Daily)
    }

    @Test
    fun `旧编码 MONTHLY - 解码容忍前后空格`() {
        assertThat(HabitFrequencyCodec.decode("MONTHLY", " 10 "))
            .isEqualTo(HabitFrequency.SpecificMonthDays((1..10).toSet()))
    }

    @Test
    fun `旧编码 MONTHLY - 不再被写成新编码（只读不写）`() {
        // v2.13 之后永远不会再产生 MONTHLY:xxx —— 写出去的一定是 MONTH_DAYS:xxx。
        // 这条防止有人"顺手"把旧格式加回编码侧，让新旧数据混在一起。
        assertThat(HabitFrequencyCodec.typeOf(HabitFrequency.SpecificMonthDays(setOf(1))))
            .isNotEqualTo(HabitFrequencyCodec.TYPE_MONTHLY)
    }

    @Test
    fun `旧的编码格式一字未改（DAILY 与 DAYS）`() {
        // 向后兼容的最直接证据：旧版本的编码结果在这一版里仍然一模一样。
        // v2.14 改写：原来这里还断言 `WeeklyTimes(3) → "WEEKLY"/"3"`，
        // 但那个类型已被删除，归类为**测试错（旧规格）**；
        // 换成"剩余两种旧格式一字未改 + WEEKLY 只读不写"（见上面那组断言）。
        assertThat(HabitFrequencyCodec.typeOf(HabitFrequency.Daily)).isEqualTo("DAILY")
        assertThat(HabitFrequencyCodec.typeOf(HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY))))
            .isEqualTo("DAYS")
        assertThat(HabitFrequencyCodec.payloadOf(HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY))))
            .isEqualTo("1")
        // WEEKLY / MONTHLY 这两个常量仍然存在（解码用），但不会出现在编码结果里
        assertThat(HabitFrequencyCodec.TYPE_WEEKLY).isEqualTo("WEEKLY")
        assertThat(HabitFrequencyCodec.TYPE_MONTHLY).isEqualTo("MONTHLY")
    }
}
