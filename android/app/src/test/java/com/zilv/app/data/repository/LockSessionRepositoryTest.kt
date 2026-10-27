package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [LockSessionRepository] 的单元测试。
 *
 * 重点是窗口实例的**幂等创建**：无障碍服务、精确闹钟、自检任务都会调用
 * `ensureSession`，重复调用必须只产生一条记录，否则锁机历史会出现大量重复条目。
 */
class LockSessionRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val nowMillis = Instant.parse("2026-10-14T08:00:00Z").toEpochMilli()

    private lateinit var sessionDao: FakeLockSessionDao
    private lateinit var eventDao: FakeEventDao
    private lateinit var repo: LockSessionRepository

    @Before
    fun setUp() {
        sessionDao = FakeLockSessionDao()
        eventDao = FakeEventDao()
        repo = LockSessionRepository(
            sessionDao,
            eventDao,
            Clock.fixed(Instant.ofEpochMilli(nowMillis), zone)
        )
    }

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 10, 14).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `创建窗口实例`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        assertThat(s.ruleId).isEqualTo(1L)
        assertThat(s.ruleName).isEqualTo("早课")
        assertThat(s.state).isEqualTo(LockSessionRepository.STATE_LOCKED)
        assertThat(s.reportSeen).isFalse()
    }

    @Test
    fun `重复创建同一窗口是幂等的`() = runTest {
        val a = repo.ensureSession(1L, "早课", at(7), at(9))
        val b = repo.ensureSession(1L, "早课", at(7), at(9))
        val c = repo.ensureSession(1L, "早课", at(7), at(9))

        assertThat(a.id).isEqualTo(b.id)
        assertThat(b.id).isEqualTo(c.id)
        assertThat(sessionDao.count()).isEqualTo(1)
    }

    @Test
    fun `同规则不同开始时刻产生不同窗口`() = runTest {
        repo.ensureSession(1L, "早课", at(7), at(9))
        repo.ensureSession(1L, "早课", at(14), at(18))
        assertThat(sessionDao.count()).isEqualTo(2)
    }

    @Test
    fun `标记解锁并记录原因`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.markUnlocked(s.id, LockSessionRepository.REASON_CHECKIN)

        val after = repo.getById(s.id)!!
        assertThat(after.state).isEqualTo(LockSessionRepository.STATE_UNLOCKED)
        assertThat(after.unlockReason).isEqualTo(LockSessionRepository.REASON_CHECKIN)
        assertThat(after.unlockedAtMillis).isEqualTo(nowMillis)
    }

    @Test
    fun `标记窗口结束`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.markExpired(s.id)
        assertThat(repo.getById(s.id)!!.state).isEqualTo(LockSessionRepository.STATE_EXPIRED)
    }

    @Test
    fun `已结束的窗口重复标记不改变状态`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.markExpired(s.id)
        repo.markExpired(s.id)
        assertThat(repo.getById(s.id)!!.state).isEqualTo(LockSessionRepository.STATE_EXPIRED)
    }

    @Test
    fun `标记报告已查看`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.markReportSeen(s.id)
        assertThat(repo.getById(s.id)!!.reportSeen).isTrue()
    }

    // ------------------------------------------ 首页「上次回顾」卡片的数据源

    @Test
    fun `最近未查看的已结束窗口`() = runTest {
        // 已结束且未查看
        val finished = repo.ensureSession(1L, "早课", at(7), at(9))
        // 尚未结束（结束时间在 now 之后）
        val ongoing = repo.ensureSession(2L, "下午", nowMillis + 60_000, nowMillis + 3_600_000)

        val latest = repo.latestUnseenFinished()
        assertThat(latest!!.id).isEqualTo(finished.id)
        assertThat(latest.id).isNotEqualTo(ongoing.id)
    }

    @Test
    fun `查看过的窗口不再出现在回顾卡片`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.markReportSeen(s.id)
        assertThat(repo.latestUnseenFinished()).isNull()
    }

    @Test
    fun `多个已结束窗口时取最近结束的那个`() = runTest {
        repo.ensureSession(1L, "早晨", at(6), at(7))
        val later = repo.ensureSession(2L, "中午", at(11), at(12))

        assertThat(repo.latestUnseenFinished()!!.id).isEqualTo(later.id)
    }

    @Test
    fun `没有窗口时回顾为空`() = runTest {
        assertThat(repo.latestUnseenFinished()).isNull()
    }

    @Test
    fun `recent 按开始时刻倒序`() = runTest {
        repo.ensureSession(1L, "早晨", at(6), at(7))
        repo.ensureSession(2L, "中午", at(11), at(12))
        repo.ensureSession(3L, "下午", at(14), at(18))

        assertThat(repo.recent().map { it.ruleName })
            .containsExactly("下午", "中午", "早晨").inOrder()
    }

    // ---------------------------------------------------------------- 事件

    @Test
    fun `记录事件并可查询`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.recordEvent(EventType.EMERGENCY_RELEASE, sessionId = s.id, detail = "招商银行")
        repo.recordEvent(EventType.ACCESSIBILITY_OFF, sessionId = s.id)

        val events = repo.eventsFor(s.id)
        assertThat(events).hasSize(2)
        assertThat(events.first().type).isEqualTo(EventType.EMERGENCY_RELEASE)
        assertThat(events.first().detail).isEqualTo("招商银行")
    }

    @Test
    fun `事件的中文描述符合文案规范`() {
        // PRD §4.10：只陈述事实，不做道德评判
        val descriptions = EventType.entries.map { EventType.describe(it) }
        assertThat(descriptions).containsExactly(
            "无障碍服务被关闭",
            "悬浮窗权限被撤销",
            "应用被强制停止",
            "系统时间被修改",
            "紧急放行",
            "异常解锁（定位降级）",
            "进入系统设置"
        )
        descriptions.forEach { d ->
            assertThat(d).doesNotContain("作弊")
            assertThat(d).doesNotContain("失败")
        }
    }

    @Test
    fun `未知事件类型不崩溃且回退为原始字符串`() {
        assertThat(EventType.decode("SOMETHING_NEW")).isNull()
        val e = ZilvEvent(1L, null, EventType.decode("SOMETHING_NEW"), "SOMETHING_NEW", 0L, null)
        assertThat(e.label).isEqualTo("SOMETHING_NEW")
    }

    @Test
    fun `事件按时间正序返回`() = runTest {
        val s = repo.ensureSession(1L, "早课", at(7), at(9))
        repo.recordEvent(EventType.TIME_CHANGED, sessionId = s.id, atMillis = 3000L)
        repo.recordEvent(EventType.ACCESSIBILITY_OFF, sessionId = s.id, atMillis = 1000L)
        repo.recordEvent(EventType.EMERGENCY_RELEASE, sessionId = s.id, atMillis = 2000L)

        assertThat(repo.eventsFor(s.id).map { it.detectedAtMillis })
            .containsExactly(1000L, 2000L, 3000L).inOrder()
    }
}
