package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [UsageRepository] 的单元测试（纯 JVM，内存 Fake DAO）。
 *
 * 最关键的一组是**窗口内区间裁剪**：一段使用可能横跨窗口边界，
 * 只应计入落在窗口内的那部分，否则报告里的"用手机 51 分钟"会失真。
 */
class UsageRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-14 16:00 (+08:00) */
    private val fixedInstant = Instant.parse("2026-10-14T08:00:00Z")

    private lateinit var dao: FakeUsageDao
    private lateinit var repo: UsageRepository

    @Before
    fun setUp() {
        dao = FakeUsageDao()
        repo = UsageRepository(dao, Clock.fixed(fixedInstant, zone))
    }

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 10, 14).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- 写入

    @Test
    fun `记录一段使用同时写会话与当日聚合`() = runTest {
        val id = repo.recordSegment("com.tencent.mm", at(9), at(9, 30))
        assertThat(id).isNotNull()

        val totals = repo.dailyTotals(LocalDate.of(2026, 10, 14))
        assertThat(totals["com.tencent.mm"]).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `同一应用多段使用累加到当日聚合`() = runTest {
        repo.recordSegment("com.tencent.mm", at(9), at(9, 10))
        repo.recordSegment("com.tencent.mm", at(10), at(10, 20))

        assertThat(repo.dailyTotals(LocalDate.of(2026, 10, 14))["com.tencent.mm"])
            .isEqualTo(30 * 60_000L)
    }

    @Test
    fun `零时长或空包名不写库`() = runTest {
        assertThat(repo.recordSegment("com.tencent.mm", at(9), at(9))).isNull()
        assertThat(repo.recordSegment("com.tencent.mm", at(10), at(9))).isNull()
        assertThat(repo.recordSegment("", at(9), at(10))).isNull()

        assertThat(repo.dailyTotals(LocalDate.of(2026, 10, 14))).isEmpty()
    }

    // -------------------------------------------------- 窗口内区间裁剪（核心）

    @Test
    fun `会话完全落在窗口内则全额计入`() = runTest {
        repo.recordSegment("pkg.a", at(9), at(9, 30))
        val totals = repo.windowTotals(at(8), at(18))
        assertThat(totals["pkg.a"]).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `会话横跨窗口起点时只计入窗口内的部分`() = runTest {
        // 会话 07:30 - 08:30，窗口 08:00 - 18:00 -> 只应计 30 分钟
        repo.recordSegment("pkg.a", at(7, 30), at(8, 30))
        val totals = repo.windowTotals(at(8), at(18))
        assertThat(totals["pkg.a"]).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `会话横跨窗口终点时只计入窗口内的部分`() = runTest {
        // 会话 17:30 - 18:30，窗口 08:00 - 18:00 -> 只应计 30 分钟
        repo.recordSegment("pkg.a", at(17, 30), at(18, 30))
        val totals = repo.windowTotals(at(8), at(18))
        assertThat(totals["pkg.a"]).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `会话完全包住窗口时按整个窗口计入`() = runTest {
        // 会话 07:00 - 19:00，窗口 08:00 - 18:00 -> 计 10 小时
        repo.recordSegment("pkg.a", at(7), at(19))
        val totals = repo.windowTotals(at(8), at(18))
        assertThat(totals["pkg.a"]).isEqualTo(10 * 60 * 60_000L)
    }

    @Test
    fun `窗口外的会话完全不计入`() = runTest {
        repo.recordSegment("pkg.a", at(6), at(7))
        repo.recordSegment("pkg.b", at(19), at(20))
        assertThat(repo.windowTotals(at(8), at(18))).isEmpty()
    }

    @Test
    fun `多应用多段在窗口内正确合并`() = runTest {
        repo.recordSegment("pkg.a", at(7, 30), at(8, 30))   // 计 30 分钟
        repo.recordSegment("pkg.a", at(9), at(10))          // 计 60 分钟
        repo.recordSegment("pkg.b", at(17), at(19))         // 计 60 分钟

        val totals = repo.windowTotals(at(8), at(18))
        assertThat(totals["pkg.a"]).isEqualTo(90 * 60_000L)
        assertThat(totals["pkg.b"]).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `非法窗口返回空`() = runTest {
        repo.recordSegment("pkg.a", at(9), at(10))
        assertThat(repo.windowTotals(at(10), at(9))).isEmpty()
        assertThat(repo.windowTotals(at(9), at(9))).isEmpty()
    }

    @Test
    fun `窗口内最长单次使用`() = runTest {
        repo.recordSegment("pkg.a", at(9), at(9, 10))
        repo.recordSegment("pkg.b", at(10), at(11))
        repo.recordSegment("pkg.a", at(11), at(11, 5))

        assertThat(repo.longestSessionInWindow(at(8), at(18))).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `窗口内没有会话时最长使用为0`() = runTest {
        assertThat(repo.longestSessionInWindow(at(8), at(18))).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `按天查询互不串天`() = runTest {
        repo.recordSegment("pkg.a", at(9), at(10))
        assertThat(repo.dailyTotals(LocalDate.of(2026, 10, 14))).isNotEmpty()
        assertThat(repo.dailyTotals(LocalDate.of(2026, 10, 13))).isEmpty()
    }

    @Test
    fun `observeDailyTotals 随写入更新`() = runTest {
        assertThat(repo.observeDailyTotals(LocalDate.of(2026, 10, 14)).first()).isEmpty()
        repo.recordSegment("pkg.a", at(9), at(10))
        assertThat(repo.observeDailyTotals(LocalDate.of(2026, 10, 14)).first())
            .containsKey("pkg.a")
    }

    @Test
    fun `prune 参数非法时抛异常`() = runTest {
        assertThat(runCatching { repo.pruneSessionsOlderThan(0) }.isFailure).isTrue()
    }
}
