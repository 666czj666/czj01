package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [BlockedAttemptRepository] 的单元测试（纯 JVM，内存 Fake DAO）。
 *
 * 重点验证 PRD §3.8 的数据口径：「同一 App 连续尝试 5 秒内合并为 1 次」。
 */
class BlockedAttemptRepositoryTest {

    private val base = Instant.parse("2026-10-14T08:00:00Z").toEpochMilli()

    private lateinit var dao: FakeBlockedAttemptDao
    private lateinit var repo: BlockedAttemptRepository

    private var now: Long = base

    @Before
    fun setUp() {
        dao = FakeBlockedAttemptDao()
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        repo = BlockedAttemptRepository(dao = dao, clock = clock)
    }

    @Test
    fun `首次尝试新建一条记录`() = runTest {
        val created = repo.recordAttempt("com.ss.android.ugc.aweme")
        assertThat(created).isTrue()
        assertThat(repo.totalCount()).isEqualTo(1)
        assertThat(dao.rowCount()).isEqualTo(1)
    }

    @Test
    fun `5秒内连点10次只产生1条记录且计数为10`() = runTest {
        repeat(10) { i ->
            now = base + i * 400L
            repo.recordAttempt("com.ss.android.ugc.aweme")
        }
        assertThat(dao.rowCount()).isEqualTo(1)
        assertThat(repo.totalCount()).isEqualTo(10)

        val row = repo.observeRecent().first().single()
        assertThat(row.count).isEqualTo(10)
        assertThat(row.firstAtMillis).isEqualTo(base)
        assertThat(row.lastAtMillis).isEqualTo(base + 9 * 400L)
    }

    @Test
    fun `超过窗口的尝试产生新记录`() = runTest {
        repo.recordAttempt("a.b.c")          // t=0   -> 新
        now = base + 3_000L
        repo.recordAttempt("a.b.c")          // t=3s   -> 并入
        now = base + 6_000L
        repo.recordAttempt("a.b.c")          // t=6s   -> 距上次 3s，并入
        now = base + 11_000L
        repo.recordAttempt("a.b.c")          // t=11s  -> 距上次 5s，新记录

        assertThat(dao.rowCount()).isEqualTo(2)
        assertThat(repo.totalCount()).isEqualTo(4)
    }

    @Test
    fun `不同应用互不影响`() = runTest {
        repo.recordAttempt("pkg.a")
        repo.recordAttempt("pkg.b")
        repo.recordAttempt("pkg.a")

        assertThat(dao.rowCount()).isEqualTo(2)
        assertThat(repo.totalCount()).isEqualTo(3)

        val totals = repo.totalsByPackage()
        assertThat(totals.first { it.packageName == "pkg.a" }.total).isEqualTo(2)
        assertThat(totals.first { it.packageName == "pkg.b" }.total).isEqualTo(1)
    }

    @Test
    fun `按包名汇总降序排列`() = runTest {
        repeat(5) { repo.recordAttempt("pkg.many") }
        now = base + 10_000L
        repo.recordAttempt("pkg.few")

        val totals = repo.totalsByPackage()
        assertThat(totals.map { it.packageName }).containsExactly("pkg.many", "pkg.few").inOrder()
        assertThat(totals.first().total).isEqualTo(5)
    }

    @Test
    fun `记录携带规则与会话信息`() = runTest {
        repo.recordAttempt("pkg.a", ruleId = 7L, sessionId = 42L)
        val row = repo.observeRecent().first().single()
        assertThat(row.ruleId).isEqualTo(7L)
        assertThat(row.sessionId).isEqualTo(42L)
    }

    @Test
    fun `时钟回拨时不产生负窗口，按新记录处理`() = runTest {
        now = base
        repo.recordAttempt("pkg.a")
        now = base - 60_000L // 用户把时间改早了
        repo.recordAttempt("pkg.a")

        assertThat(dao.rowCount()).isEqualTo(2)
        assertThat(repo.totalCount()).isEqualTo(2)
    }

    @Test
    fun `空库时总数为0`() = runTest {
        assertThat(repo.totalCount()).isEqualTo(0)
        assertThat(repo.observeRecent().first()).isEmpty()
        assertThat(repo.totalsByPackage()).isEmpty()
    }

    @Test
    fun `清空记录`() = runTest {
        repeat(3) { repo.recordAttempt("pkg.a") }
        repo.clear()
        assertThat(repo.totalCount()).isEqualTo(0)
        assertThat(dao.rowCount()).isEqualTo(0)
    }

    @Test
    fun `observeRecent 按最近时间倒序并受 limit 限制`() = runTest {
        repo.recordAttempt("pkg.1")
        now = base + 10_000L
        repo.recordAttempt("pkg.2")
        now = base + 20_000L
        repo.recordAttempt("pkg.3")

        val recent = repo.observeRecent(limit = 2).first()
        assertThat(recent.map { it.packageName }).containsExactly("pkg.3", "pkg.2").inOrder()
    }
}
