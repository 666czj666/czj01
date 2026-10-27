package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.local.entity.PlanEntity
import com.zilv.app.data.local.entity.RuleCheckInEntity
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「规划并进锁机规则」的运行时接线测试（v2.5 的 A1）。
 *
 * 上一批把规划 → 锁机规则（`Plan.toLockRule()`，`allowCheckInUnlock = false`）
 * 的换算做完了，但**运行时没接上**：`LockRuleRepository.getEnabledRules()` /
 * `currentStatus()` 那时只读 `lock_rules` 表，于是"规划时间内锁机"只在领域层成立。
 *
 * 这个文件专门钉住接线本身，与 `PlanRepositoryTest`（换算正确性）、
 * `PlanLockUnlockTest`（"打卡对规划无效"的调度语义）三足鼎立：
 *
 *  1. [LockRuleRepository.getEnabledRules] 必须把**今明两天**的规划并进来
 *     （明天那一份是必要的：窗口开始前的预警与"开始那一刻"的闹钟要靠它）
 *  2. [LockRuleRepository.currentStatus] 必须把**正在进行中**的规划并进状态，
 *     且在规划窗口内一律是 [LockPhase.LOCKED]（打卡救不了它）
 *  3. 规划生效期间 [LockRuleRepository] 的写入口必须抛 [RuleLockedException]
 *     —— "锁机期间禁止改动"这条保护必须自动覆盖规划锁机
 *  4. 没有规划来源时（默认值）行为与接线之前**完全一致**
 *
 * 时钟固定在 `2026-10-14 16:00 (+08:00)`：今天 = 10-14（周三）、明天 = 10-15（周四）。
 */
class LockRulePlanWiringTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private lateinit var lockRuleDao: FakeLockRuleDao
    private lateinit var allowAppDao: FakeAllowAppDao
    private lateinit var ruleAllowAppDao: FakeRuleAllowAppDao
    private lateinit var ruleCheckInDao: FakeRuleCheckInDao
    private lateinit var habitCheckInDao: FakeCheckInDao
    private lateinit var planDao: FakePlanDao

    /** 16:00 —— 既在下面那条"习惯锁机 07:00-09:00"之外，又方便构造规划场景。 */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)

    /** 接上真实规划来源的仓库（应用里 Koin 就是这么装的）。 */
    private lateinit var repo: LockRuleRepository

    /** **没有**规划来源的仓库（默认值）—— 用来证明既有行为一字未变。 */
    private lateinit var plainRepo: LockRuleRepository

    /** 应用里注入的就是 `PlanRepository`，它自己实现了 `PlanRuleSource`。 */
    private lateinit var planRepo: PlanRepository

    @Before
    fun setUp() {
        lockRuleDao = FakeLockRuleDao()
        allowAppDao = FakeAllowAppDao()
        ruleAllowAppDao = FakeRuleAllowAppDao()
        ruleCheckInDao = FakeRuleCheckInDao()
        habitCheckInDao = FakeCheckInDao()
        planDao = FakePlanDao()
        planRepo = PlanRepository(planDao, clock)
        repo = repoWith(clock)
        plainRepo = LockRuleRepository(
            lockRuleDao, allowAppDao, ruleAllowAppDao, ruleCheckInDao, habitCheckInDao, clock
        )
    }

    /** 与 Koin 的装配方式一致：把 `PlanRepository` 当作 `PlanRuleSource` 传进去。 */
    private fun repoWith(clock: Clock) = LockRuleRepository(
        lockRuleDao = lockRuleDao,
        allowAppDao = allowAppDao,
        ruleAllowAppDao = ruleAllowAppDao,
        ruleCheckInDao = ruleCheckInDao,
        checkInDao = habitCheckInDao,
        clock = clock,
        planRuleSource = planRepo
    )

    /** 一条与规划无关的习惯规则，用来验证"并集"而不是"替换"。 */
    private val habitRule = LockRule(
        name = "习惯锁机",
        windows = listOf(TimeWindow(7 * 60, 9 * 60))
    )

    /** 直接往 `plans` 表塞一条规划（绕过"只能添加明天"的仓库校验，模拟历史数据）。 */
    private suspend fun insertPlan(
        name: String,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int
    ): Long = planDao.insert(
        PlanEntity(
            name = name,
            epochDay = date.toEpochDay(),
            startMinute = startMinute,
            endMinute = endMinute,
            createdAt = 0L
        )
    )

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    // ------------------------------------------------ 1）规则并集：今明两天

    @Test
    fun `今天生效的规划出现在启用规则里`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)

        val rules = repo.getEnabledRules()

        val planRule = rules.single { it.isPlanRule }
        assertThat(planRule.name).isEqualTo("读书")
        assertThat(planRule.habitId).isNull()
        // 规划规则不落库，id 恒为 0，来源靠 planId 标记
        assertThat(planRule.id).isEqualTo(0L)
        assertThat(planRule.planId).isNotNull()
        // 解锁条件仅为到点解锁
        assertThat(planRule.allowCheckInUnlock).isFalse()
        assertThat(planRule.effectiveLockWindows).containsExactly(TimeWindow(15 * 60, 17 * 60))
    }

    @Test
    fun `明天的规划也提前出现在启用规则里（否则预警与闹钟没有理由注册）`() = runTest {
        insertPlan("明天读书", tomorrow, 9 * 60, 11 * 60)

        val rules = repo.getEnabledRules()

        assertThat(rules.map { it.name }).contains("明天读书")
        assertThat(rules.single { it.isPlanRule }.startDate).isEqualTo(tomorrow)
    }

    @Test
    fun `更远的规划（后天以后）不会提前进规则`() = runTest {
        insertPlan("后天", today.plusDays(2), 9 * 60, 11 * 60)

        assertThat(repo.getEnabledRules()).isEmpty()
    }

    @Test
    fun `计划表里的非法规划（起止相同或跨 0 点）不会进规则`() = runTest {
        // 起止相同 = 全天锁机，跨 0 点 = 规划明令禁止，两者都必须被挡在外面
        insertPlan("会议", today, 10 * 60, 10 * 60)
        insertPlan("跨0点", today, 22 * 60, 6 * 60)

        assertThat(repo.getEnabledRules()).isEmpty()
        assertThat(repo.currentStatus(dt("2026-10-14T16:00")).phase).isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `表里的规则与规划规则同时存在时都返回`() = runTest {
        repo.save(habitRule)
        insertPlan("读书", today, 15 * 60, 17 * 60)

        val rules = repo.getEnabledRules()

        assertThat(rules.map { it.name }).containsExactly("习惯锁机", "读书")
        assertThat(rules.count { it.isPlanRule }).isEqualTo(1)
        assertThat(rules.single { !it.isPlanRule }.name).isEqualTo("习惯锁机")
    }

    // ------------------------------------------ 2）状态判定：规划生效＝锁机中

    @Test
    fun `规划时间内状态是锁机中`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)

        val status = repo.currentStatus(dt("2026-10-14T16:00"))

        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.rule?.name).isEqualTo("读书")
        assertThat(status.rule?.isPlanRule).isTrue()
        // 还有 60 分钟到 17:00
        assertThat(status.minutesUntilChange).isEqualTo(60L)
    }

    @Test
    fun `规划时间内习惯打卡也不能解锁`() = runTest {
        // 用户要求"解锁条件仅为到点解锁"：这是 v2.2"任何打卡都能解锁"的唯一例外。
        // 这里从**习惯打卡**这条路径写入（LockRuleRepository 会读 check_ins），
        // 规划规则也必须无视它。
        habitCheckInDao.insert(
            com.zilv.app.data.local.entity.CheckInEntity(
                habitId = 1L,
                ruleId = null,
                sessionId = null,
                checkedAtMillis = dt("2026-10-14T15:30").atZone(zone).toInstant().toEpochMilli(),
                epochDay = today.toEpochDay(),
                status = "MANUAL",
                lat = null,
                lng = null,
                accuracyM = null,
                distanceM = null,
                spotId = null,
                photoPath = null,
                note = null
            )
        )
        insertPlan("读书", today, 15 * 60, 17 * 60)

        assertThat(repo.currentStatus(dt("2026-10-14T16:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `规划时间内规则打卡也不能解锁`() = runTest {
        // 另一条打卡路径：rule_check_ins（从锁机规则 / 打卡点流程打的那张卡）
        val planId = insertPlan("读书", today, 15 * 60, 17 * 60)
        assertThat(planId).isGreaterThan(0L)
        ruleCheckInDao.insert(
            RuleCheckInEntity(
                ruleId = 0L,
                spotId = 0L,
                checkedAtMillis = dt("2026-10-14T15:30").atZone(zone).toInstant().toEpochMilli(),
                epochDay = today.toEpochDay(),
                lat = 30.0,
                lng = 120.0,
                accuracyM = 10f,
                distanceM = 5.0,
                status = "ON_TIME"
            )
        )

        assertThat(repo.currentStatus(dt("2026-10-14T16:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `规划结束那一刻自动解除`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)

        assertThat(repo.currentStatus(dt("2026-10-14T16:59")).phase).isEqualTo(LockPhase.LOCKED)
        // 半开区间 [start, end)：17:00 整已经结束
        assertThat(repo.currentStatus(dt("2026-10-14T17:00")).phase).isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `规划开始前 15 分钟内是预警`() = runTest {
        insertPlan("读书", today, 17 * 60, 18 * 60)

        assertThat(repo.currentStatus(dt("2026-10-14T16:50")).phase).isEqualTo(LockPhase.WARN)
        assertThat(repo.currentStatus(dt("2026-10-14T16:00")).phase).isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `明天的规划不会让今天变成锁机`() = runTest {
        insertPlan("明天读书", tomorrow, 15 * 60, 17 * 60)

        assertThat(repo.currentStatus(dt("2026-10-14T16:00")).phase).isEqualTo(LockPhase.IDLE)
        // 到了明天那个时刻才锁
        assertThat(repo.currentStatus(dt("2026-10-15T16:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------ 3）"锁机期间禁止改动"自动覆盖规划

    @Test
    fun `规划进行中不能停用规则`() = runTest {
        val id = repo.save(habitRule)
        insertPlan("读书", today, 15 * 60, 17 * 60)

        val error = runCatching { repo.setEnabled(id, false) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRule(id)!!.enabled).isTrue()
    }

    @Test
    fun `规划进行中不能改白名单`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)

        val error = runCatching { repo.addUserApp("com.example.game", "游戏") }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getAllowedApps().map { it.packageName }).doesNotContain("com.example.game")
    }

    @Test
    fun `规划进行中不能新增规则`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)

        val error = runCatching { repo.save(habitRule) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getAllRules()).isEmpty()
    }

    @Test
    fun `规划结束后又能改动规则`() = runTest {
        val id = repo.save(habitRule)
        insertPlan("读书", today, 15 * 60, 17 * 60)

        // 固定到 18:00（规划已结束）的仓库，同一个规划来源
        val after = repoWith(Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), zone))
        assertThat(after.now()).isEqualTo(LocalDateTime.of(2026, 10, 14, 18, 0))

        after.setEnabled(id, false)

        assertThat(after.getRule(id)!!.enabled).isFalse()
    }

    // ------------------------------- 4）不接规划来源时行为与接线之前一致

    @Test
    fun `没有规划来源时规划不影响规则与状态`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)

        assertThat(plainRepo.getEnabledRules()).isEmpty()
        assertThat(plainRepo.currentStatus(dt("2026-10-14T16:00")).phase).isEqualTo(LockPhase.IDLE)
        // 默认值就是"什么都不提供"
        assertThat(PlanRuleSource.NONE.rulesFor(today)).isEmpty()
        assertThat(PlanRuleSource.NONE.activeRules(dt("2026-10-14T16:00"))).isEmpty()
    }

    @Test
    fun `fromPlanDao 适配器与 PlanRepository 的口径一致`() = runTest {
        insertPlan("读书", today, 15 * 60, 17 * 60)
        val adapter = PlanRuleSource.fromPlanDao(planDao)

        assertThat(adapter.rulesFor(today).map { it.name })
            .isEqualTo(planRepo.rulesFor(today).map { it.name })
        assertThat(adapter.activeRules(dt("2026-10-14T16:00")).map { it.name })
            .isEqualTo(planRepo.activeRules(dt("2026-10-14T16:00")).map { it.name })
        // 非法规划同样被适配器挡在外面
        insertPlan("会议", today, 10 * 60, 10 * 60)
        assertThat(adapter.rulesFor(today).map { it.name }).containsExactly("读书")
    }
}
