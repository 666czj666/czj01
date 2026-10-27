package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.local.entity.PlanEntity
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.plan.Plan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [PlanRepository] 的单元测试（纯 JVM，DAO 用 [FakePlanDao]）。
 *
 * 用户要求："添加明日规划界面，该界面**只能添加明天的规划**……规划**不能跨 0 点**。"
 *
 * 时钟固定在 `2026-10-14 16:00 (+08:00)`，所以：
 *  - 今天 = 2026-10-14
 *  - **明天 = 2026-10-15**（唯一允许新增规划的日期）
 *
 * 这个文件是"两条硬约束在**仓库层**"的证据：绕过界面直接调仓库，
 * 一样会被拒绝 —— 而且拒绝原因是可读的一句话，不是一个裸的 IllegalStateException。
 */
class PlanRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private lateinit var dao: FakePlanDao
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        dao = FakePlanDao()
        repo = repoAt()
    }

    /** 默认：固定"今天"为 2026-10-14 16:00（+08:00）。 */
    private fun repoAt(at: String = "2026-10-14T08:00:00Z"): PlanRepository =
        PlanRepository(planDao = dao, clock = Clock.fixed(Instant.parse(at), zone))

    private fun tomorrowEpochDay(): Long = tomorrow.toEpochDay()

    // ------------------------------------------------------------ 只能添加明天

    @Test
    fun `新增明天的规划成功并返回 id`() = runTest {
        val id = repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)

        assertThat(id).isGreaterThan(0L)
        val plans = repo.getForDay(tomorrow)
        assertThat(plans).hasSize(1)
        assertThat(plans.single().name).isEqualTo("读书")
        assertThat(plans.single().date).isEqualTo(tomorrow)
        assertThat(plans.single().startMinute).isEqualTo(540)
        assertThat(plans.single().endMinute).isEqualTo(660)
        // createdAt 取自注入的时钟
        assertThat(plans.single().createdAt)
            .isEqualTo(Instant.parse("2026-10-14T08:00:00Z").toEpochMilli())
    }

    @Test
    fun `只能添加明天 - 今天被拒绝`() = runTest {
        val error = assertFailsWith<PlanRejectedException> {
            repo.addPlan("读书", today.toEpochDay(), 9 * 60, 11 * 60)
        }
        assertThat(error.message).contains("只能添加明天的规划")
        assertThat(error.message).contains("2026-10-14")
        assertThat(error.message).contains("2026-10-15")
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `只能添加明天 - 后天被拒绝`() = runTest {
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("读书", tomorrow.plusDays(1).toEpochDay(), 9 * 60, 11 * 60)
        }
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `只能添加明天 - 过去的日期被拒绝`() = runTest {
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("读书", today.minusDays(10).toEpochDay(), 9 * 60, 11 * 60)
        }
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `只能添加明天 - 无法识别的 epochDay 给出可读原因`() = runTest {
        val error = assertFailsWith<PlanRejectedException> {
            repo.addPlan("读书", Long.MIN_VALUE, 9 * 60, 11 * 60)
        }
        assertThat(error.message).contains("只能添加明天的规划")
    }

    @Test
    fun `LocalDate 重载与 epochDay 版本规则一致`() = runTest {
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("读书", today, 9 * 60, 11 * 60)
        }
        val id = repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        assertThat(id).isGreaterThan(0L)
        assertThat(repo.getForDay(tomorrow)).hasSize(1)
    }

    @Test
    fun `明天由注入的时钟决定`() = runTest {
        // 换一个时钟：本地 2026-10-15 10:00 -> 今天=10-15、明天=10-16
        val later = repoAt("2026-10-15T02:00:00Z")
        assertThat(later.today()).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(later.tomorrow()).isEqualTo(LocalDate.of(2026, 10, 16))

        // 10-15 这时已经"今天"了 -> 拒绝
        assertFailsWith<PlanRejectedException> {
            later.addPlan("读书", today.plusDays(1).toEpochDay(), 9 * 60, 11 * 60)
        }
        // 10-16 才是明天 -> 接受
        assertThat(later.addPlan("读书", LocalDate.of(2026, 10, 16), 9 * 60, 11 * 60))
            .isGreaterThan(0L)
    }

    // -------------------------------------------------------------- 不能跨 0 点

    @Test
    fun `跨0点被拒绝`() = runTest {
        val error = assertFailsWith<PlanRejectedException> {
            repo.addPlan("通宵", tomorrowEpochDay(), 22 * 60, 6 * 60)
        }
        assertThat(error.message).contains("不能跨 0 点")
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `起止相同被拒绝（否则会被当成全天锁机）`() = runTest {
        val error = assertFailsWith<PlanRejectedException> {
            repo.addPlan("会议", tomorrowEpochDay(), 10 * 60, 10 * 60)
        }
        assertThat(error.message).contains("不能相同")
        assertThat(error.message).contains("全天")
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `起止时间越界被拒绝`() = runTest {
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("越界", tomorrowEpochDay(), -1, 60)
        }
        // 1440 = 次日 0 点，对于"不跨 0 点"的规划是非法的
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("越界", tomorrowEpochDay(), 0, 1440)
        }
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("越界", tomorrowEpochDay(), 1439, 1439 + 1)
        }
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `23点到23点59 是允许的最晚规划`() = runTest {
        repo.addPlan("睡前复盘", tomorrowEpochDay(), 23 * 60, 23 * 60 + 59)
        assertThat(repo.getForDay(tomorrow).single().durationMinutes).isEqualTo(59)
    }

    @Test
    fun `名称为空被拒绝`() = runTest {
        assertFailsWith<PlanRejectedException> {
            repo.addPlan("   ", tomorrowEpochDay(), 9 * 60, 11 * 60)
        }
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `名称首尾空白被去掉`() = runTest {
        repo.addPlan("  读书  ", tomorrowEpochDay(), 9 * 60, 11 * 60)
        assertThat(repo.getForDay(tomorrow).single().name).isEqualTo("读书")
    }

    // ------------------------------------------------------------------ 查询

    @Test
    fun `按开始时间升序返回当天的规划`() = runTest {
        repo.addPlan("下午", tomorrowEpochDay(), 14 * 60, 15 * 60)
        repo.addPlan("上午", tomorrowEpochDay(), 8 * 60, 9 * 60)
        repo.addPlan("中午", tomorrowEpochDay(), 12 * 60, 13 * 60)

        assertThat(repo.getForDay(tomorrow).map { it.name })
            .containsExactly("上午", "中午", "下午").inOrder()
    }

    @Test
    fun `不同日期的规划互不干扰`() = runTest {
        repo.addPlan("明天", tomorrowEpochDay(), 9 * 60, 10 * 60)
        // 直接往 DAO 塞一条别的日期（模拟历史数据；仓库层只允许写明天）
        dao.insert(
            PlanEntity(
                name = "昨天",
                epochDay = today.minusDays(1).toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                createdAt = 0L
            )
        )
        assertThat(repo.getForDay(tomorrow).map { it.name }).containsExactly("明天")
        assertThat(repo.getForDay(today.minusDays(1)).map { it.name }).containsExactly("昨天")
    }

    @Test
    fun `observeForDay 会随写入更新`() = runTest {
        assertThat(repo.observeForDay(tomorrow).first()).isEmpty()
        repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        assertThat(repo.observeForDay(tomorrow).first()).hasSize(1)
    }

    @Test
    fun `planDates 返回有规划的日期集合`() = runTest {
        assertThat(repo.planDates()).isEmpty()

        repo.addPlan("明天", tomorrowEpochDay(), 9 * 60, 10 * 60)
        dao.insert(
            PlanEntity(
                name = "更远",
                epochDay = tomorrow.plusDays(3).toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                createdAt = 0L
            )
        )

        assertThat(repo.planDates())
            .containsExactly(tomorrow, tomorrow.plusDays(3))
        assertThat(repo.observePlanDates().first())
            .containsExactly(tomorrow, tomorrow.plusDays(3))
    }

    @Test
    fun `getById 与 getAll 口径正确`() = runTest {
        val id = repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        assertThat(repo.getById(id)!!.name).isEqualTo("读书")
        assertThat(repo.getById(id + 999)).isNull()
        assertThat(repo.getAll().map { it.name }).containsExactly("读书")
        assertThat(repo.observeAll().first().map { it.name }).containsExactly("读书")
    }

    // ------------------------------------------------------------------ 修改

    @Test
    fun `修改规划的名称与时间`() = runTest {
        val id = repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        val saved = repo.getById(id)!!

        repo.update(saved.copy(name = "  看论文 ", startMinute = 10 * 60, endMinute = 12 * 60))

        val updated = repo.getById(id)!!
        assertThat(updated.name).isEqualTo("看论文")
        assertThat(updated.window).isEqualTo(TimeWindow(10 * 60, 12 * 60))
    }

    @Test
    fun `修改时同样校验不能跨 0 点`() = runTest {
        val id = repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        val saved = repo.getById(id)!!

        assertFailsWith<PlanRejectedException> {
            repo.update(saved.copy(startMinute = 22 * 60, endMinute = 6 * 60))
        }
        // 原值没被改坏
        assertThat(repo.getById(id)!!.window).isEqualTo(TimeWindow(9 * 60, 11 * 60))
    }

    @Test
    fun `修改不存在的规划被拒绝`() = runTest {
        val ghost = Plan(
            id = 999L,
            name = "幽灵",
            date = tomorrow,
            startMinute = 9 * 60,
            endMinute = 10 * 60
        )
        val error = assertFailsWith<PlanRejectedException> { repo.update(ghost) }
        assertThat(error.message).contains("不存在")
    }

    @Test
    fun `修改时不能把规划挪到过去的日期`() = runTest {
        val id = repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        val saved = repo.getById(id)!!

        val error = assertFailsWith<PlanRejectedException> {
            repo.update(saved.copy(date = today.minusDays(1)))
        }
        assertThat(error.message).contains("已经过去")
    }

    // ------------------------------------------------------------------ 删除

    @Test
    fun `删除规划`() = runTest {
        val id = repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        repo.delete(id)
        assertThat(repo.getById(id)).isNull()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `删除不存在的 id 是空操作`() = runTest {
        repo.delete(12345L)
        repo.delete(0L)
        assertThat(dao.count()).isEqualTo(0)
    }

    // ------------------------------------------------- 进行中的规划不能改/删
    //
    // 用户要求"解锁条件仅为到点解锁"。如果规划生效期间还能改时间或删掉它，
    // 用户在锁机时点一下就等于解锁 —— 与"锁机期间禁止改动锁机规则"是同一条硬要求。

    @Test
    fun `进行中的规划不能修改`() = runTest {
        // 直接造一条"今天 09:00-11:00"的规划，并把时钟固定在这段时间之内（本地 10:00）
        val id = dao.insert(
            PlanEntity(
                name = "读书",
                epochDay = today.toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 0L
            )
        )
        val during = repoAt("2026-10-14T02:00:00Z")
        assertThat(during.now()).isEqualTo(LocalDateTime.of(2026, 10, 14, 10, 0))

        val saved = during.getById(id)!!
        val error = assertFailsWith<PlanRejectedException> {
            during.update(saved.copy(endMinute = 12 * 60))
        }
        assertThat(error.message).contains("正在进行中")
        assertThat(error.message).contains("到点解锁")
    }

    @Test
    fun `进行中的规划不能删除`() = runTest {
        val id = dao.insert(
            PlanEntity(
                name = "读书",
                epochDay = today.toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 0L
            )
        )
        // 本地 10:00，正在规划时间内
        val during = repoAt("2026-10-14T02:00:00Z")

        val error = assertFailsWith<PlanRejectedException> { during.delete(id) }
        assertThat(error.message).contains("正在进行中")
        assertThat(during.getById(id)).isNotNull()
    }

    @Test
    fun `规划结束后可以修改与删除`() = runTest {
        val id = dao.insert(
            PlanEntity(
                name = "读书",
                epochDay = today.toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 0L
            )
        )
        // 本地 12:00 —— 规划已经结束
        val after = repoAt("2026-10-14T04:00:00Z")
        assertThat(after.now()).isEqualTo(LocalDateTime.of(2026, 10, 14, 12, 0))

        after.update(after.getById(id)!!.copy(name = "读书笔记"))
        assertThat(after.getById(id)!!.name).isEqualTo("读书笔记")

        after.delete(id)
        assertThat(after.getById(id)).isNull()
    }

    // ------------------------------------------------------------ 锁机（运行时）

    @Test
    fun `lockRulesFor 把当天的规划转成规则`() = runTest {
        repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        repo.addPlan("跑步", tomorrowEpochDay(), 18 * 60, 19 * 60)

        val rules = repo.lockRulesFor(tomorrow)
        assertThat(rules.map { it.name }).containsExactly("读书", "跑步").inOrder()
        // 每一条都只能到点解锁，并且指向它自己的规划
        assertThat(rules.all { !it.allowCheckInUnlock }).isTrue()
        assertThat(rules.all { it.isPlanRule }).isTrue()
        assertThat(rules.all { it.habitId == null }).isTrue()
        assertThat(rules[0].effectiveLockWindows).containsExactly(TimeWindow(9 * 60, 11 * 60))
    }

    @Test
    fun `lockRulesFor 在别的日期没有规划时返回空`() = runTest {
        repo.addPlan("读书", tomorrowEpochDay(), 9 * 60, 11 * 60)
        assertThat(repo.lockRulesFor(tomorrow.plusDays(1))).isEmpty()
        assertThat(repo.lockRulesFor(today)).isEmpty()
    }

    @Test
    fun `activeLockRules 只返回此刻进行中的规划`() = runTest {
        dao.insert(
            PlanEntity(
                name = "上午",
                epochDay = today.toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 0L
            )
        )
        dao.insert(
            PlanEntity(
                name = "下午",
                epochDay = today.toEpochDay(),
                startMinute = 14 * 60,
                endMinute = 16 * 60,
                createdAt = 0L
            )
        )
        // 本地 10:00 -> 只有"上午"在进行
        val during = repoAt("2026-10-14T02:00:00Z")

        val active = during.activeLockRules()
        assertThat(active.map { it.name }).containsExactly("上午")
        assertThat(during.activeLockRules(LocalDateTime.of(2026, 10, 14, 15, 0)))
            .hasSize(1)
        // 结束那一刻（11:00 整）不再锁机 —— 半开区间
        assertThat(during.activeLockRules(LocalDateTime.of(2026, 10, 14, 11, 0))).isEmpty()
        // 开始那一刻（09:00 整）就在锁
        assertThat(during.activeLockRules(LocalDateTime.of(2026, 10, 14, 9, 0))).hasSize(1)
    }

    @Test
    fun `脏数据里的非法规划不会进锁机规则`() = runTest {
        dao.insert(
            PlanEntity(
                name = "越界",
                epochDay = tomorrow.toEpochDay(),
                startMinute = 0,
                endMinute = 1440,
                createdAt = 0L
            )
        )
        assertThat(repo.lockRulesFor(tomorrow)).isEmpty()
    }
}
