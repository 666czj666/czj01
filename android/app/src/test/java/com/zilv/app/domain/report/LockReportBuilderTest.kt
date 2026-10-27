package com.zilv.app.domain.report

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.mapper.RuleCheckIn
import com.zilv.app.data.mapper.RuleCheckInStatus
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 锁机报告聚合的单元测试（PRD §3.8）。
 *
 * PRD 反复强调"口径必须写清楚否则数字没意义"，因此这里逐条钉死：
 * 锁机时长含已解锁时段、用手机统计**全部**前台时长而非只统计展示出来的那几条、
 * 被拦次数按合并后口径、打卡早于窗口开始为负数分钟。
 */
class LockReportBuilderTest {

    private val start = LocalDateTime.parse("2026-10-14T08:00")
    private val end = LocalDateTime.parse("2026-10-14T18:00")

    private fun checkIn(
        at: LocalDateTime,
        status: RuleCheckInStatus = RuleCheckInStatus.ON_TIME
    ) = RuleCheckIn(
        ruleId = 1L,
        spotId = 1L,
        checkedAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        epochDay = at.toLocalDate().toEpochDay(),
        lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
        status = status
    )

    private fun build(
        checkIn: RuleCheckIn? = null,
        usage: Map<String, Long> = emptyMap(),
        labels: Map<String, String> = emptyMap(),
        blocked: Map<String, Int> = emptyMap(),
        anomalies: List<String> = emptyList(),
        maxKeyApps: Int = LockReportBuilder.DEFAULT_MAX_KEY_APPS
    ) = LockReportBuilder.build(
        ruleName = "图书馆自习",
        windowStart = start,
        windowEnd = end,
        checkIn = checkIn,
        usageMillis = usage,
        labels = labels,
        blockedTotals = blocked,
        anomalies = anomalies,
        maxKeyApps = maxKeyApps
    )

    @Test
    fun `锁机时长等于窗口长度（含已解锁时段）`() {
        assertThat(build().lockDurationMillis).isEqualTo(10 * 60 * 60 * 1000L)
    }

    @Test
    fun `用手机统计全部前台时长而不只是展示出来的应用`() {
        // 12 个应用，但只展示 8 个；用手机必须把 12 个都算进去
        val usage = (1..12).associate { "pkg.$it" to 60_000L * it }
        val report = build(usage = usage, maxKeyApps = 8)

        assertThat(report.keyApps).hasSize(8)
        val expectedTotal = (1..12).sumOf { 60_000L * it }
        assertThat(report.phoneUsedMillis).isEqualTo(expectedTotal)
    }

    @Test
    fun `关键应用按时长降序且过滤零值`() {
        val report = build(
            usage = mapOf(
                "pkg.a" to 3_000L,
                "pkg.b" to 0L,
                "pkg.c" to 9_000L,
                "pkg.d" to -5L
            )
        )
        assertThat(report.keyApps.map { it.packageName }).containsExactly("pkg.c", "pkg.a").inOrder()
        assertThat(report.keyApps.map { it.durationMillis }).containsExactly(9_000L, 3_000L).inOrder()
    }

    @Test
    fun `显示名解析不到时回退为包名`() {
        val report = build(
            usage = mapOf("com.tencent.mm" to 60_000L, "pkg.unknown" to 30_000L),
            labels = mapOf("com.tencent.mm" to "微信")
        )
        assertThat(report.keyApps.first { it.packageName == "com.tencent.mm" }.label).isEqualTo("微信")
        assertThat(report.keyApps.first { it.packageName == "pkg.unknown" }.label).isEqualTo("pkg.unknown")
    }

    @Test
    fun `被拦应用按次数降序且过滤零值`() {
        val report = build(
            blocked = mapOf("douyin" to 27, "xhs" to 0, "wzry" to 5),
            labels = mapOf("douyin" to "抖音")
        )
        assertThat(report.blockedApps.map { it.packageName }).containsExactly("douyin", "wzry").inOrder()
        assertThat(report.blockedApps.first().count).isEqualTo(27)
        assertThat(report.blockedApps.first().label).isEqualTo("抖音")
    }

    @Test
    fun `无任何使用记录时 hasAnyUsage 为 false`() {
        val report = build()
        assertThat(report.hasAnyUsage).isFalse()
        assertThat(report.keyApps).isEmpty()
        assertThat(report.phoneUsedMillis).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- 打卡

    @Test
    fun `准时打卡的分钟差与状态`() {
        val report = build(checkIn = checkIn(LocalDateTime.parse("2026-10-14T08:00")))
        val c = report.checkIn!!
        assertThat(c.minutesFromStart).isEqualTo(0L)
        assertThat(c.isLate).isFalse()
        assertThat(c.isEarly).isFalse()
    }

    @Test
    fun `迟到打卡为正分钟数`() {
        val report = build(
            checkIn = checkIn(LocalDateTime.parse("2026-10-14T09:12"), RuleCheckInStatus.LATE)
        )
        val c = report.checkIn!!
        assertThat(c.minutesFromStart).isEqualTo(72L)
        assertThat(c.isLate).isTrue()
        assertThat(c.lateMinutes).isEqualTo(72L)
        assertThat(c.status).isEqualTo(RuleCheckInStatus.LATE)
    }

    @Test
    fun `提前打卡为负分钟数`() {
        val report = build(checkIn = checkIn(LocalDateTime.parse("2026-10-14T07:45")))
        val c = report.checkIn!!
        assertThat(c.minutesFromStart).isEqualTo(-15L)
        assertThat(c.isEarly).isTrue()
        assertThat(c.lateMinutes).isEqualTo(0L)
    }

    @Test
    fun `没有打卡记录时 checkIn 为 null`() {
        assertThat(build().checkIn).isNull()
    }

    @Test
    fun `异常解锁状态被带出`() {
        val report = build(
            checkIn = checkIn(LocalDateTime.parse("2026-10-14T09:00"), RuleCheckInStatus.ABNORMAL_UNLOCK)
        )
        assertThat(report.checkIn!!.status).isEqualTo(RuleCheckInStatus.ABNORMAL_UNLOCK)
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `异常事件原样带出`() {
        val report = build(anomalies = listOf("14:23 紧急放行（招商银行）", "16:00 无障碍被关闭"))
        assertThat(report.anomalies).hasSize(2)
    }

    @Test
    fun `maxKeyApps 生效`() {
        val usage = (1..20).associate { "pkg.$it" to 1_000L * it }
        assertThat(build(usage = usage, maxKeyApps = 3).keyApps).hasSize(3)
    }

    @Test
    fun `maxKeyApps 非正数抛异常`() {
        assertThat(runCatching { build(maxKeyApps = 0) }.isFailure).isTrue()
        assertThat(runCatching { build(maxKeyApps = -1) }.isFailure).isTrue()
    }

    @Test
    fun `窗口结束早于开始时锁机时长为0而不是负数`() {
        val report = LockReportBuilder.build(
            ruleName = "x",
            windowStart = end,
            windowEnd = start,
            checkIn = null,
            usageMillis = emptyMap()
        )
        assertThat(report.lockDurationMillis).isEqualTo(0L)
    }

    @Test
    fun `PRD 约定的默认展示条数为 8`() {
        assertThat(LockReportBuilder.DEFAULT_MAX_KEY_APPS).isEqualTo(8)
    }
}
