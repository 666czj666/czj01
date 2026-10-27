package com.zilv.app.domain.quota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [QuotaCalculator] 的单元测试 —— **这是本次功能的核心**。
 *
 * 用户要求："自定义一个使用时间额度……自定义时间额度刷新间隔（每天 0 点，
 * 每周一，每月 1 号）到点刷新（**旧额度不可保留**）。时间额度为零时禁用指定软件。"
 *
 * 这一组测试要钉死四件事：
 *  1. [QuotaCalculator.periodStart] 三个周期的定义，含**跨月与跨年**
 *  2. 边界：周期起点**前一秒 / 后一秒**、刚好用完、超额、时长为 0
 *  3. "旧额度不可保留"：只统计周期内的用量，不做任何结转
 *  4. 跨周期边界的会话：只算落在本周期内的那一段
 *
 * ## 为什么这些断言能成立（时区与毫秒的换算）
 *
 * `periodStart` 返回 [LocalDateTime]，而用量是毫秒。测试里用
 * [QuotaCalculator.epochMillis] 做**同一个**换算（实现内部也是它），
 * 所以断言不受运行机器时区影响：无论 CI 在哪个时区，
 * "周期起点前一毫秒"永远是相对同一个本地时间轴算出来的。
 */
class QuotaCalculatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 把本地时间变成毫秒（与实现同一条换算）。 */
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        QuotaCalculator.epochMillis(LocalDateTime.of(y, mo, d, h, mi, s))

    private fun ldt(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s)

    private fun segment(pkg: String, start: Long, end: Long) = UsageSegment(pkg, start, end)

    private fun quota(
        id: Long = 1L,
        name: String = "短视频",
        limit: Int = 30,
        period: ResetPeriod = ResetPeriod.DAILY,
        enabled: Boolean = true,
        packages: Set<String> = setOf("com.douyin")
    ) = UsageQuota(
        id = id,
        name = name,
        limitMinutes = limit,
        resetPeriod = period,
        enabled = enabled,
        packageNames = packages
    )

    // ============================================================ 周期起点

    @Test
    fun `DAILY 的周期起点是当天 0 点`() {
        // 用 atStartOfDay 而不是 LocalDate.atTime(0,0)：语义相同，
        // 但前者在 DST 缺 0 点的情况下会给出该天最早的有效时刻
        assertThat(QuotaCalculator.periodStart(ResetPeriod.DAILY, ldt(2026, 10, 14, 16, 30)))
            .isEqualTo(ldt(2026, 10, 14))
        // 0 点整自己：起点就是自己（边界闭区间）
        assertThat(QuotaCalculator.periodStart(ResetPeriod.DAILY, ldt(2026, 10, 14, 0, 0)))
            .isEqualTo(ldt(2026, 10, 14))
        // 23:59:59 仍是同一天
        assertThat(QuotaCalculator.periodStart(ResetPeriod.DAILY, ldt(2026, 10, 14, 23, 59, 59)))
            .isEqualTo(ldt(2026, 10, 14))
    }

    @Test
    fun `WEEKLY 的周期起点是本周一 0 点 —— 一周七天逐天验证`() {
        // 2026-10-12 是周一。逐天检查：从周一到周日，起点都必须回到 10-12
        val monday = ldt(2026, 10, 12)
        for (day in 12..18) {
            assertThat(
                QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 10, day, 13, 45))
            ).isEqualTo(monday)
        }
        // 下一个周一（10-19）：新周期从它自己开始
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 10, 19, 0, 0)))
            .isEqualTo(ldt(2026, 10, 19))
        // 10-18 是周日，仍属 10-12 那一周
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 10, 18, 23, 59, 59)))
            .isEqualTo(monday)
    }

    @Test
    fun `WEEKLY 跨月：月初那一周的周一是上个月的某天`() {
        // 2026-03-01 是周日 -> 本周一是 2026-02-23
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 3, 1, 10, 0)))
            .isEqualTo(ldt(2026, 2, 23))
        // 2026-03-02 是周一 -> 起点是它自己
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 3, 2, 0, 0)))
            .isEqualTo(ldt(2026, 3, 2))
    }

    @Test
    fun `WEEKLY 跨年：元旦那一周的周一落在上一年`() {
        // 2026-01-01 是周四 -> 本周一是 2025-12-29
        val start = QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 1, 1, 8, 0))
        assertThat(start).isEqualTo(ldt(2025, 12, 29))
        assertThat(start.year).isEqualTo(2025)
        assertThat(start.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)

        // 2026-01-04 是周日，仍在 2025-12-29 那一周内
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 1, 4, 23, 0)))
            .isEqualTo(ldt(2025, 12, 29))
        // 2026-01-05 是周一 -> 新周期
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2026, 1, 5, 0, 0)))
            .isEqualTo(ldt(2026, 1, 5))
    }

    @Test
    fun `WEEKLY 在闰年二月同样按周一对齐`() {
        // 2028-02-29 是周二 -> 本周一是 2028-02-28
        assertThat(QuotaCalculator.periodStart(ResetPeriod.WEEKLY, ldt(2028, 2, 29, 12, 0)))
            .isEqualTo(ldt(2028, 2, 28))
    }

    @Test
    fun `MONTHLY 的周期起点是当月 1 号 0 点`() {
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2026, 10, 14, 16, 30)))
            .isEqualTo(ldt(2026, 10, 1))
        // 月末最后一天仍是本月 1 号
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2026, 10, 31, 23, 59, 59)))
            .isEqualTo(ldt(2026, 10, 1))
    }

    @Test
    fun `MONTHLY 跨年：1 月 1 号的周期起点是它自己，不是去年 12 月`() {
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2026, 1, 1, 0, 0)))
            .isEqualTo(ldt(2026, 1, 1))
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2026, 1, 1, 0, 0)).year)
            .isEqualTo(2026)
        // 1 月的任意一天
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2026, 1, 31, 20, 0)))
            .isEqualTo(ldt(2026, 1, 1))
    }

    @Test
    fun `MONTHLY 在 12 月 31 号仍属当月 1 号`() {
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2025, 12, 31, 23, 0)))
            .isEqualTo(ldt(2025, 12, 1))
    }

    @Test
    fun `两个闰年 2 月的 1 号都被正确取到`() {
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2028, 2, 29, 6, 0)))
            .isEqualTo(ldt(2028, 2, 1))
        assertThat(QuotaCalculator.periodStart(ResetPeriod.MONTHLY, ldt(2024, 2, 29, 6, 0)))
            .isEqualTo(ldt(2024, 2, 1))
    }

    @Test
    fun `周期起点永远不晚于当前时刻`() {
        val samples = listOf(
            ldt(2026, 1, 1, 0, 0, 0),
            ldt(2026, 3, 1, 12, 30),
            ldt(2026, 10, 14, 16, 30),
            ldt(2025, 12, 31, 23, 59, 59),
            ldt(2028, 2, 29, 6, 0)
        )
        for (period in ResetPeriod.entries) {
            for (now in samples) {
                assertThat(QuotaCalculator.periodStart(period, now)).isAtMost(now)
            }
        }
    }

    @Test
    fun `ResetPeriod 的 startOf 与 QuotaCalculator 完全一致`() {
        // 枚举上的便捷方法只是转发，不能有第二套算法
        val now = ldt(2026, 10, 14, 16, 30)
        for (period in ResetPeriod.entries) {
            assertThat(period.startOf(now)).isEqualTo(QuotaCalculator.periodStart(period, now))
        }
    }

    // ============================================================ 剩余与用尽

    @Test
    fun `剩余分钟数不会为负`() {
        assertThat(QuotaCalculator.remainingMinutes(30, 0)).isEqualTo(30)
        assertThat(QuotaCalculator.remainingMinutes(30, 29)).isEqualTo(1)
        assertThat(QuotaCalculator.remainingMinutes(30, 30)).isEqualTo(0)  // 刚好用完
        assertThat(QuotaCalculator.remainingMinutes(30, 31)).isEqualTo(0)  // 超额
        assertThat(QuotaCalculator.remainingMinutes(30, 10_000)).isEqualTo(0)
        assertThat(QuotaCalculator.remainingMinutes(0, 0)).isEqualTo(0)
    }

    @Test
    fun `刚好用完算用尽，差一分钟不算`() {
        assertThat(QuotaCalculator.isExhausted(usedMinutes = 30, limitMinutes = 30)).isTrue()
        assertThat(QuotaCalculator.isExhausted(usedMinutes = 29, limitMinutes = 30)).isFalse()
        assertThat(QuotaCalculator.isExhausted(usedMinutes = 31, limitMinutes = 30)).isTrue()
    }

    @Test
    fun `时长为 0 的额度恒为用尽 —— 用户要求额度为零时禁用`() {
        assertThat(QuotaCalculator.isExhausted(usedMinutes = 0, limitMinutes = 0)).isTrue()
        assertThat(QuotaCalculator.isExhausted(usedMillis = 0L, limitMillis = 0L)).isTrue()
        assertThat(QuotaCalculator.remainingMinutes(0, 0)).isEqualTo(0)
    }

    @Test
    fun `毫秒口径的用尽判定与分钟口径一致`() {
        val limit = 30 * 60_000L
        assertThat(QuotaCalculator.isExhausted(limit - 1, limit)).isFalse()
        assertThat(QuotaCalculator.isExhausted(limit, limit)).isTrue()
        assertThat(QuotaCalculator.isExhausted(limit + 1, limit)).isTrue()
    }

    @Test
    fun `已用分钟数向上取整`() {
        assertThat(QuotaCalculator.usedMinutes(0L)).isEqualTo(0)
        assertThat(QuotaCalculator.usedMinutes(1L)).isEqualTo(1)          // 1 毫秒也算 1 分钟
        assertThat(QuotaCalculator.usedMinutes(59_999L)).isEqualTo(1)
        assertThat(QuotaCalculator.usedMinutes(60_000L)).isEqualTo(1)     // 整 1 分钟
        assertThat(QuotaCalculator.usedMinutes(60_001L)).isEqualTo(2)
        assertThat(QuotaCalculator.usedMinutes(30 * 60_000L)).isEqualTo(30)
    }

    @Test
    fun `向上取整防止 59 秒无限重开绕过时长限制`() {
        // 每天 1 分钟：用了 30 秒就应该显现"已用 1 分钟"，
        // 否则用户可以每 59 秒重开一次而永远用不完
        val used = QuotaCalculator.usedMinutes(30_000L)
        assertThat(used).isEqualTo(1)
        assertThat(QuotaCalculator.isExhausted(used, 1)).isTrue()
    }

    // ============================================================ 周期内用量

    @Test
    fun `只统计周期内的用量，周期外的完全不计`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val usage = listOf(
            // 昨天 10:00-11:00（周期外）
            segment("com.douyin", at(2026, 10, 13, 10), at(2026, 10, 13, 11)),
            // 今天 09:00-09:30（周期内，30 分钟）
            segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 30))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.DAILY,
            now
        )
        assertThat(used).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `旧额度不可保留 —— 上个周期用超了不会扣本周期`() {
        val now = ldt(2026, 10, 14, 10, 0)
        val usage = listOf(
            // 昨天用了 5 小时（远超 30 分钟额度）
            segment("com.douyin", at(2026, 10, 13, 8), at(2026, 10, 13, 13)),
            // 今天只用了 5 分钟
            segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 5))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.DAILY,
            now
        )
        // 只有今天那 5 分钟 —— 没有任何"欠账"
        assertThat(used).isEqualTo(5 * 60_000L)
        assertThat(QuotaCalculator.remainingMinutes(30, QuotaCalculator.usedMinutes(used)))
            .isEqualTo(25)
    }

    @Test
    fun `旧额度不可保留 —— 上个周期省下来的不会攒到本周期`() {
        val now = ldt(2026, 10, 14, 10, 0)
        val usage = listOf(
            // 昨天只用了 1 分钟（额度 30 分钟，省下 29 分钟）
            segment("com.douyin", at(2026, 10, 13, 8), at(2026, 10, 13, 8, 1)),
            // 今天用了 30 分钟（正好用完）
            segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 30))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.DAILY,
            now
        )
        // 本周期只有 30 分钟 -> 正好用尽。若把昨天省下的 29 分钟结转过来，
        // 这里就会是"还剩 29 分钟"，用户又能继续用 —— 那是错的
        assertThat(used).isEqualTo(30 * 60_000L)
        assertThat(QuotaCalculator.isExhausted(used, 30 * 60_000L)).isTrue()
    }

    @Test
    fun `跨周期边界的会话只算落在本周期内的那一段`() {
        val now = ldt(2026, 10, 14, 2, 0)
        val usage = listOf(
            // 23:30（昨天）用到 00:30（今天）：本周期内只有 00:00-00:30 这 30 分钟
            segment("com.douyin", at(2026, 10, 13, 23, 30), at(2026, 10, 14, 0, 30))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.DAILY,
            now
        )
        assertThat(used).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `跨周边界的会话只算周一 0 点之后的部分`() {
        // 2026-10-12 是周一。会话从 10-11（周日）23:00 到 10-12 01:00
        val now = ldt(2026, 10, 12, 3, 0)
        val usage = listOf(
            segment("com.douyin", at(2026, 10, 11, 23), at(2026, 10, 12, 1))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.WEEKLY,
            now
        )
        assertThat(used).isEqualTo(60 * 60_000L)  // 只有周一 00:00-01:00
    }

    @Test
    fun `跨月边界的会话只算 1 号 0 点之后的部分`() {
        // 会话从 9-30 23:00 到 10-1 01:00
        val now = ldt(2026, 10, 1, 5, 0)
        val usage = listOf(
            segment("com.douyin", at(2026, 9, 30, 23), at(2026, 10, 1, 1))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.MONTHLY,
            now
        )
        assertThat(used).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `跨年的周边界只算元旦那一周周一之后的部分`() {
        // 2026-01-01 是周四，本周一 = 2025-12-29。
        // 会话从 2025-12-28（周日）23:00 到 2025-12-29 01:00 -> 只算 1 小时
        val now = ldt(2026, 1, 1, 10, 0)
        val usage = listOf(
            segment("com.douyin", at(2025, 12, 28, 23), at(2025, 12, 29, 1))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.WEEKLY,
            now
        )
        assertThat(used).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `周期起点前一秒不算，后一秒算`() {
        val now = ldt(2026, 10, 14, 12, 0)
        val start = at(2026, 10, 14, 0, 0)

        val justBefore = listOf(segment("com.douyin", start - 10_000L, start - 1_000L))
        assertThat(
            QuotaCalculator.usedMillisInPeriod(justBefore, setOf("com.douyin"), ResetPeriod.DAILY, now)
        ).isEqualTo(0L)

        val justAfter = listOf(segment("com.douyin", start + 1_000L, start + 2_000L))
        assertThat(
            QuotaCalculator.usedMillisInPeriod(justAfter, setOf("com.douyin"), ResetPeriod.DAILY, now)
        ).isEqualTo(1_000L)

        // **正好**从起点开始的那一段算进本周期（区间是闭的）
        val exactlyAtStart = listOf(segment("com.douyin", start, start + 5_000L))
        assertThat(
            QuotaCalculator.usedMillisInPeriod(
                exactlyAtStart,
                setOf("com.douyin"),
                ResetPeriod.DAILY,
                now
            )
        ).isEqualTo(5_000L)
    }

    @Test
    fun `周期结束时刻之后的用量不计入`() {
        // now 之后才结束的会话：只算到 now 为止
        val now = ldt(2026, 10, 14, 12, 0)
        val usage = listOf(
            segment("com.douyin", at(2026, 10, 14, 11, 30), at(2026, 10, 14, 12, 30))
        )
        assertThat(
            QuotaCalculator.usedMillisInPeriod(usage, setOf("com.douyin"), ResetPeriod.DAILY, now)
        ).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `只统计指定包名，别的应用再久也不算`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val usage = listOf(
            segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 10)),
            segment("com.tencent.mm", at(2026, 10, 14, 10), at(2026, 10, 14, 18))
        )
        val used = QuotaCalculator.usedMillisInPeriod(
            usage,
            setOf("com.douyin"),
            ResetPeriod.DAILY,
            now
        )
        assertThat(used).isEqualTo(10 * 60_000L)
    }

    @Test
    fun `空包名集合直接返回 0（不会把全部应用算进来）`() {
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 10)))
        assertThat(
            QuotaCalculator.usedMillisInPeriod(usage, emptySet(), ResetPeriod.DAILY, ldt(2026, 10, 14, 20))
        ).isEqualTo(0L)
    }

    @Test
    fun `空用量列表返回 0`() {
        assertThat(
            QuotaCalculator.usedMillisInPeriod(
                emptyList(),
                setOf("com.douyin"),
                ResetPeriod.DAILY,
                ldt(2026, 10, 14, 20)
            )
        ).isEqualTo(0L)
    }

    @Test
    fun `窗口结束不晚于开始就返回 0，不会算出负时长`() {
        val usage = listOf(segment("com.douyin", 1000L, 2000L))
        assertThat(QuotaCalculator.totalInWindow(usage, setOf("com.douyin"), 5000L, 5000L))
            .isEqualTo(0L)
        assertThat(QuotaCalculator.totalInWindow(usage, setOf("com.douyin"), 5000L, 4000L))
            .isEqualTo(0L)
    }

    // ============================================================ 共用额度

    @Test
    fun `一个额度下的多个应用共用同一份时长`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, packages = setOf("com.douyin", "com.kuaishou"))
        val usage = listOf(
            segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 20)),   // 20 分钟
            segment("com.kuaishou", at(2026, 10, 14, 10), at(2026, 10, 14, 10, 10)) // 10 分钟
        )
        val exhausted = QuotaCalculator.exhaustedApps(listOf(quota), usage, now)

        // 加起来 30 分钟 = 额度 -> **两个都**被禁用
        assertThat(exhausted.keys).containsExactly("com.douyin", "com.kuaishou")
        assertThat(exhausted.getValue("com.douyin").usedMinutes).isEqualTo(30)
    }

    @Test
    fun `共用额度下单个应用用满额度，同额度内另一个也被禁`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, packages = setOf("com.douyin", "com.kuaishou"))
        val usage = listOf(
            segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 30))
        )
        val exhausted = QuotaCalculator.exhaustedApps(listOf(quota), usage, now)
        assertThat(exhausted.keys).containsExactly("com.douyin", "com.kuaishou")
        // 另一个应用自己一分钟都没用，但额度是共用的 —— 这正是需求的原话
        assertThat(exhausted.getValue("com.kuaishou").quotaName).isEqualTo("短视频")
    }

    @Test
    fun `额度没用完时一个都不禁`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, packages = setOf("com.douyin"))
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 29)))
        assertThat(QuotaCalculator.exhaustedApps(listOf(quota), usage, now)).isEmpty()
    }

    @Test
    fun `额度刚好用完就禁用（边界）`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, packages = setOf("com.douyin"))
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 30)))
        assertThat(QuotaCalculator.exhaustedApps(listOf(quota), usage, now).keys)
            .containsExactly("com.douyin")
    }

    @Test
    fun `超额之后依然禁用，且已用分钟数是真实值`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, packages = setOf("com.douyin"))
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 10, 15)))
        val exhaustion = QuotaCalculator.exhaustedApps(listOf(quota), usage, now)
            .getValue("com.douyin")
        assertThat(exhaustion.usedMinutes).isEqualTo(75)
        assertThat(exhaustion.limitMinutes).isEqualTo(30)
    }

    @Test
    fun `停用的额度一个都不禁`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, enabled = false)
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 18)))
        assertThat(QuotaCalculator.exhaustedApps(listOf(quota), usage, now)).isEmpty()
    }

    @Test
    fun `0 分钟额度在没有任何用量时也被禁用`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 0, packages = setOf("com.douyin"))
        val exhausted = QuotaCalculator.exhaustedApps(listOf(quota), emptyList(), now)
        assertThat(exhausted.keys).containsExactly("com.douyin")
        assertThat(exhausted.getValue("com.douyin").usedMinutes).isEqualTo(0)
    }

    @Test
    fun `不合法的额度整条跳过，不会误拦`() {
        val now = ldt(2026, 10, 14, 20, 0)
        // 没有绑任何应用
        val noApps = quota(id = 1, limit = 0, packages = emptySet())
        // 名称为空
        val noName = quota(id = 2, name = "  ", limit = 0, packages = setOf("com.douyin"))
        assertThat(QuotaCalculator.exhaustedApps(listOf(noApps, noName), emptyList(), now)).isEmpty()
    }

    @Test
    fun `同一个应用被两个额度绑定时，任意一个用尽即禁用，原因取更严的那个`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val loose = quota(id = 1, name = "宽松", limit = 120, packages = setOf("com.douyin"))
        val strict = quota(id = 2, name = "严格", limit = 10, packages = setOf("com.douyin"))
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 15)))

        val exhaustion = QuotaCalculator.exhaustedApps(listOf(loose, strict), usage, now)
            .getValue("com.douyin")
        // 15 分钟：严格的那个（10 分钟）已用尽，宽松的（120 分钟）没有。
        // 结论是禁用，且原因报"更严"的那条，用户改起来最省事
        assertThat(exhaustion.quotaName).isEqualTo("严格")
        assertThat(exhaustion.limitMinutes).isEqualTo(10)

        // 反过来：只有一个宽松额度时不该禁用
        assertThat(QuotaCalculator.exhaustedApps(listOf(loose), usage, now)).isEmpty()
    }

    @Test
    fun `不同刷新周期的额度互不干扰`() {
        // now = 2026-10-14（周三）。周额度起点是 10-12（周一），月额度起点是 10-01。
        val now = ldt(2026, 10, 14, 20, 0)
        val daily = quota(id = 1, name = "每日", limit = 10, period = ResetPeriod.DAILY)
        val monthly = quota(id = 2, name = "每月", limit = 10, period = ResetPeriod.MONTHLY)
        val usage = listOf(
            // 10-13 用了 1 小时：日额度看不到（周期外），月额度看得到
            segment("com.douyin", at(2026, 10, 13, 9), at(2026, 10, 13, 10))
        )
        val exhausted = QuotaCalculator.exhaustedApps(listOf(daily, monthly), usage, now)
        // 日额度没超（本周期 0 分钟），月额度超了（60 > 10）
        assertThat(exhausted.keys).containsExactly("com.douyin")
        assertThat(exhausted.getValue("com.douyin").quotaName).isEqualTo("每月")
    }

    @Test
    fun `额度说明文案带上名称、时长与周期`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(name = "短视频", limit = 90, packages = setOf("com.douyin"))
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 11)))
        val message = QuotaCalculator.exhaustedApps(listOf(quota), usage, now)
            .getValue("com.douyin").message
        assertThat(message).contains("短视频")
        assertThat(message).contains("1 小时 30 分")
        assertThat(message).contains("每天")
    }

    // ============================================================ 窄接口重载

    @Test
    fun `QuotaEnforcer 重载与 UsageQuota 重载结论一致`() {
        val now = ldt(2026, 10, 14, 20, 0)
        val quota = quota(limit = 30, packages = setOf("com.douyin"))
        val usage = listOf(segment("com.douyin", at(2026, 10, 14, 9), at(2026, 10, 14, 9, 30)))
        val viaQuota = QuotaCalculator.exhaustedApps(listOf(quota), usage, now)
        val viaEnforcer = QuotaCalculator.exhaustedPackages(
            listOf(QuotaEnforcer.from(quota)),
            usage,
            now
        )
        assertThat(viaEnforcer).isEqualTo(viaQuota)
    }

    @Test
    fun `QuotaEnforcer 的 NONE 什么都不禁，也不会因为乘法溢出而反过来全禁`() {
        val now = ldt(2026, 10, 14, 20, 0)
        // 关键：limitMinutes 若用 Int.MAX_VALUE，`* 60_000L` 会溢出成负数，
        // 于是"没有额度"反而变成"全都超额"。这条断言专门钉住它。
        assertThat(QuotaEnforcer.NONE.limitMinutes * 60_000L).isGreaterThan(0L)
        assertThat(
            QuotaCalculator.exhaustedPackages(listOf(QuotaEnforcer.NONE), emptyList(), now)
        ).isEmpty()
    }

    @Test
    fun `epochMillis 与 LocalDateTime 的换算可逆且与时区一致`() {
        val at = ldt(2026, 10, 14, 16, 30, 45)
        val millis = QuotaCalculator.epochMillis(at)
        val back = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
        assertThat(back).isEqualTo(at)
    }

    // ============================================================ 枚举与文案

    @Test
    fun `三个刷新周期与用户要求的三个刷新点一一对应`() {
        assertThat(ResetPeriod.entries.map { it.label }).containsExactly(
            "每天 0 点刷新",
            "每周一 0 点刷新",
            "每月 1 号 0 点刷新"
        ).inOrder()
        assertThat(ResetPeriod.entries.map { it.shortLabel })
            .containsExactly("每天", "每周", "每月").inOrder()
    }

    @Test
    fun `额度时长的格式化覆盖几档常见值`() {
        assertThat(UsageQuota.formatMinutes(0)).contains("0 分")
        assertThat(UsageQuota.formatMinutes(30)).isEqualTo("30 分")
        assertThat(UsageQuota.formatMinutes(60)).isEqualTo("1 小时")
        assertThat(UsageQuota.formatMinutes(90)).isEqualTo("1 小时 30 分")
        assertThat(UsageQuota.formatMinutes(1440)).isEqualTo("24 小时")
        // ⚠️ 规格变更（用户要求"限额时长上限增加到 744 小时"）：
        // 上限本身就是最长的那一档，文案必须能读出来
        assertThat(UsageQuota.formatMinutes(UsageQuota.MAX_LIMIT_MINUTES)).isEqualTo("744 小时")
    }

    @Test
    fun `上限于 744 小时（44640 分钟），越界额度不合法`() {
        // ⚠️ 规格变更：上限从 1440 分钟（24 小时）提到 44640 分钟（744 小时 = 31 天）。
        // 旧断言写的是 "1440 合法 / 1441 不合法"，那是**旧规格**——
        // 现在 1441 是合法的（在 0..44640 之间），必须改成新的边界。
        assertThat(UsageQuota.MAX_LIMIT_MINUTES).isEqualTo(744 * 60)
        assertThat(UsageQuota.MAX_LIMIT_MINUTES).isEqualTo(44640)

        assertThat(quota(limit = 44640).isValid).isTrue()
        assertThat(quota(limit = 44641).isValid).isFalse()
        assertThat(quota(limit = -1).isValid).isFalse()
        assertThat(quota(limit = 0).isValid).isTrue()
        // 旧的 24 小时上限现在是"合法但很小"的一档，不再是上界
        assertThat(quota(limit = 1440).isValid).isTrue()
        assertThat(quota(limit = 1441).isValid).isTrue()
    }
}
