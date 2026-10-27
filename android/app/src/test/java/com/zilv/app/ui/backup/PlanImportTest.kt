package com.zilv.app.ui.backup

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakePlanDao
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.domain.backup.DataBackup
import com.zilv.app.domain.plan.Plan
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「明日规划」进备份与恢复的测试（v2.5 的 A4）。
 *
 * 上一批已经把 `DataBackup`（`FORMAT_VERSION = 2` 的 `plan` 记录）做完了，
 * 这一批做的是**接线**：导出时把 `plans` 放进快照、导入时把它落回库里。
 *
 * 这里覆盖三件事：
 *  1. 规划能完整往返（含名字里的换行 —— 与留言复用同一套转义）
 *  2. 恢复走的是 `PlanRepository.addPlan`，所以"只能明天 / 不能跨 0 点 /
 *     起止不能相同"三条**一条都没被绕过**
 *  3. 备份里的历史规划（过去的日期）会被拒绝，但**不影响**明天那条落库
 *
 * 时间固定在 2026-10-14 16:00 (+08:00)：今天 = 10-14，明天 = 10-15。
 */
class PlanImportTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private lateinit var dao: FakePlanDao
    private lateinit var repo: PlanRepository
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)

    @Before
    fun setUp() {
        dao = FakePlanDao()
        repo = PlanRepository(dao, fixedClock)
    }

    /**
     * 从**同一份数据**上开一个新仓库实例。
     *
     * 模拟"导出之后重启 App，再导入"：时间与数据都不变，只有仓库对象是新的。
     */
    private fun reopen(): PlanRepository = PlanRepository(dao, fixedClock)

    private fun plan(
        name: String,
        date: LocalDate = tomorrow,
        startMinute: Int = 9 * 60,
        endMinute: Int = 11 * 60,
        id: Long = 0L
    ) = Plan(id = id, name = name, date = date, startMinute = startMinute, endMinute = endMinute)

    // ------------------------------------------------------------ 导出

    @Test
    fun `导出快照里的规划能原样编码解码`() {
        val snapshot = DataBackup.Snapshot(
            plans = listOf(plan("读书"), plan("看论文", startMinute = 14 * 60, endMinute = 16 * 60))
        )

        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!

        assertThat(decoded.plans.map { it.name }).containsExactly("读书", "看论文")
        assertThat(decoded.plans.map { it.date }).containsExactly(tomorrow, tomorrow)
        assertThat(decoded.plans.first { it.name == "看论文" }.startMinute).isEqualTo(14 * 60)
    }

    @Test
    fun `规划名里的换行能往返（与留言同一套转义）`() {
        val snapshot = DataBackup.Snapshot(plans = listOf(plan("读书\n第二行")))

        val decoded = DataBackup.decode(DataBackup.encode(snapshot))!!

        assertThat(decoded.plans.single().name).isEqualTo("读书\n第二行")
    }

    @Test
    fun `只有规划时快照也不算空`() {
        assertThat(DataBackup.Snapshot(plans = listOf(plan("读书"))).isEmpty).isFalse()
    }

    // ------------------------------------------------------------ 恢复

    @Test
    fun `明天的规划能被恢复`() = runTest {
        val imported = PlanImport.restore(listOf(plan("读书")), repo)

        assertThat(imported).isEqualTo(1)
        val saved = repo.getForDay(tomorrow).single()
        assertThat(saved.name).isEqualTo("读书")
        assertThat(saved.startMinute).isEqualTo(9 * 60)
        assertThat(saved.endMinute).isEqualTo(11 * 60)
    }

    @Test
    fun `过去的规划被拒绝，但明天的照旧落库`() = runTest {
        val imported = PlanImport.restore(
            listOf(
                plan("昨天的", date = today.minusDays(1)),
                plan("明天的", date = tomorrow),
                plan("更远的", date = tomorrow.plusDays(5))
            ),
            repo
        )

        // 只有"明天"那一条能落库 —— 这是产品规则，不是 bug
        assertThat(imported).isEqualTo(1)
        assertThat(repo.getAll().map { it.name }).containsExactly("明天的")
    }

    @Test
    fun `起止相同的规划被拒绝（否则会变成全天锁机）`() = runTest {
        val imported = PlanImport.restore(
            listOf(plan("会议", startMinute = 10 * 60, endMinute = 10 * 60)),
            repo
        )

        assertThat(imported).isEqualTo(0)
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `跨 0 点的规划被拒绝`() = runTest {
        val imported = PlanImport.restore(
            listOf(plan("通宵", startMinute = 22 * 60, endMinute = 6 * 60)),
            repo
        )

        assertThat(imported).isEqualTo(0)
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `名字为空的规划被拒绝`() = runTest {
        val imported = PlanImport.restore(listOf(plan("   ")), repo)

        assertThat(imported).isEqualTo(0)
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `空快照恢复 0 条且不报错`() = runTest {
        assertThat(PlanImport.restore(emptyList(), repo)).isEqualTo(0)
        assertThat(dao.count()).isEqualTo(0)
    }

    /**
     * 端到端：导出 → 清空 → 导入。
     *
     * 这是"备份接线"最直接的证据 —— 上一批做好的 `DataBackup` 编码
     * 加上这一批接上的 `PlanImport`，合起来能把规划完整搬回来。
     */
    @Test
    fun `导出再导入能把明天的规划搬回来`() = runTest {
        repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        val text = DataBackup.encode(DataBackup.Snapshot(plans = repo.getAll()))

        // 模拟"库被清空"（换一个空的 DAO，但时钟相同）
        val emptyDao = FakePlanDao()
        val fresh = PlanRepository(emptyDao, fixedClock)
        val decoded = DataBackup.decode(text)!!

        val imported = PlanImport.restore(decoded.plans, fresh)

        assertThat(imported).isEqualTo(1)
        assertThat(fresh.getForDay(tomorrow).single().name).isEqualTo("读书")
    }

    @Test
    fun `反复恢复同一条规划会得到多条（规划没有天然主键去重）`() = runTest {
        // 与留言不同：留言按 epoch_day 主键 upsert，规划是**一天可以有多条**的，
        // 所以重复导入会留下重复项。这里把已知行为钉住，避免以后有人
        // 以为它是幂等的而依赖这一点。
        PlanImport.restore(listOf(plan("读书")), repo)
        PlanImport.restore(listOf(plan("读书")), repo)

        assertThat(repo.getForDay(tomorrow)).hasSize(2)
    }

    @Test
    fun `名字首尾空白在恢复时被去掉`() = runTest {
        PlanImport.restore(listOf(plan("  读书  ")), repo)
        assertThat(repo.getForDay(tomorrow).single().name).isEqualTo("读书")
    }
}
