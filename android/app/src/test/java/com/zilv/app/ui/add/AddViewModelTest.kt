package com.zilv.app.ui.add

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.FakePlanDao
import com.zilv.app.data.repository.FakeQuotaDao
import com.zilv.app.data.repository.FakeUsageDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.ResetPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [AddViewModel] 的单元测试（v2.5 的 C，v2.7 追加额度入口）。
 *
 * 用户要求：
 *  - "界面显示已有的习惯和明日规划，点击可进行编辑"
 *  - "点击明日规划就再弹出来一个窗口，新窗口有开始时间和结束时间的设定"
 *  - v2.7："在添加界面加入软件使用时间限时功能入口，要独立入口"
 *
 * 覆盖三件事：
 *  1. 列表口径：习惯（含归档）与**明天**的规划都在，更远/更早的规划不在
 *  2. 时间校验：[validateTimes] 必须显式拒绝"起止相同"与"跨 0 点"——
 *     `TimeWindow(x, x)` 是**全天 1440 分钟**而不是 0 分钟，放过去就等于整天锁机
 *  3. v2.7：额度入口的计数（`quotaCount` / `enabledQuotaCount`）跟着库走，
 *     且额度只有"计数"进这一页 —— 这一页不新增/不修改额度
 *
 * 时间固定在 2026-10-14 16:00 (+08:00)：今天 = 10-14，明天 = 10-15。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private lateinit var habitDao: FakeHabitDao
    private lateinit var planDao: FakePlanDao
    private lateinit var quotaDao: FakeQuotaDao
    private lateinit var habitRepo: HabitRepository
    private lateinit var planRepo: PlanRepository
    private lateinit var quotaRepo: UsageQuotaRepository
    private lateinit var usageRepo: UsageRepository
    private lateinit var viewModel: AddViewModel

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        habitDao = FakeHabitDao()
        planDao = FakePlanDao()
        quotaDao = FakeQuotaDao()
        habitRepo = HabitRepository(habitDao, FakeCheckInDao(), clock)
        planRepo = PlanRepository(planDao, clock)
        usageRepo = UsageRepository(FakeUsageDao(), clock)
        quotaRepo = UsageQuotaRepository(quotaDao, usageRepo, clock)
        viewModel = AddViewModel(habitRepo, planRepo, quotaRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun habit(name: String) = Habit(name = name, type = HabitType.SIMPLE)

    // ------------------------------------------------------------ 列表口径

    @Test
    fun `初始时列表为空`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isEmpty).isTrue()
        assertThat(viewModel.uiState.value.loading).isFalse()
        assertThat(viewModel.uiState.value.tomorrow).isEqualTo(tomorrow)
    }

    @Test
    fun `新增的习惯自动出现在列表里`() = runTest {
        advanceUntilIdle()

        habitRepo.save(habit("早起"))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.habits.map { it.name }).containsExactly("早起")
    }

    @Test
    fun `归档的习惯进归档组，不进主动列表`() = runTest {
        val id = habitRepo.save(habit("早起"))
        habitRepo.save(habit("读书"))
        habitRepo.archive(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.habits.map { it.name }).containsExactly("读书")
        assertThat(viewModel.uiState.value.archivedHabits.map { it.name }).containsExactly("早起")
    }

    @Test
    fun `只显示明天的规划，更远或更早的都不显示`() = runTest {
        planRepo.addPlan("明天的", tomorrow, 9 * 60, 10 * 60)
        // 直接往 DAO 塞别的日期（模拟历史数据 / 别处写入）
        planDao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = "更远的",
                epochDay = tomorrow.plusDays(3).toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                createdAt = 0L
            )
        )
        planDao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = "过去的",
                epochDay = today.minusDays(1).toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                createdAt = 0L
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.plans.map { it.name }).containsExactly("明天的")
        assertThat(viewModel.uiState.value.planCount).isEqualTo(1)
    }

    @Test
    fun `规划按开始时间升序（与 DAO 的排序一致）`() = runTest {
        planRepo.addPlan("下午", tomorrow, 14 * 60, 15 * 60)
        planRepo.addPlan("上午", tomorrow, 8 * 60, 9 * 60)
        planRepo.addPlan("中午", tomorrow, 12 * 60, 13 * 60)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.plans.map { it.name })
            .containsExactly("上午", "中午", "下午").inOrder()
    }

    @Test
    fun `新建规划后不用手动刷新，列表自己会更新`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.plans).isEmpty()

        planRepo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.plans.map { it.name }).containsExactly("读书")
    }

    // ------------------------------------------------------------ 时间校验

    @Test
    fun `合法的起止被接受`() {
        assertThat(viewModel.validateTimes(9 * 60, 11 * 60)).isNull()
        // 23:00-23:59 是最晚的合法规划
        assertThat(viewModel.validateTimes(23 * 60, 23 * 60 + 59)).isNull()
        // 1 分钟也是合法的规划
        assertThat(viewModel.validateTimes(0, 1)).isNull()
    }

    @Test
    fun `起止相同被拒绝（TimeWindow(x,x) 是全天而不是 0 分钟）`() {
        val error = viewModel.validateTimes(10 * 60, 10 * 60)!!
        assertThat(error).contains("不能相同")
        assertThat(error).contains("全天")
    }

    @Test
    fun `跨 0 点被拒绝`() {
        val error = viewModel.validateTimes(22 * 60, 6 * 60)!!
        assertThat(error).contains("不能跨 0 点")
    }

    @Test
    fun `越界的时间被拒绝`() {
        assertThat(viewModel.validateTimes(-1, 60)!!).contains("00:00..23:59")
        assertThat(viewModel.validateTimes(0, 1440)!!).contains("00:00..23:59")
        assertThat(viewModel.validateTimes(1439, 1440)!!).contains("00:00..23:59")
    }

    // ------------------------------------------------------------ 默认值

    @Test
    fun `默认时段是 09 点到 10 点，本身必须是合法的`() {
        // 这个默认值会作为"预填"带进新建规划页，所以它**绝不能**是
        // TimeWindow(x, x)（那会被当成全天锁机）
        assertThat(AddViewModel.DEFAULT_START_MINUTE).isEqualTo(9 * 60)
        assertThat(AddViewModel.DEFAULT_END_MINUTE).isEqualTo(10 * 60)
        assertThat(
            viewModel.validateTimes(
                AddViewModel.DEFAULT_START_MINUTE,
                AddViewModel.DEFAULT_END_MINUTE
            )
        ).isNull()
    }

    // ------------------------------------------------------------ 额度入口（v2.7）
    //
    // 用户要求"在添加界面加入软件使用时间限时功能入口，要独立入口"。
    // 这一页只负责把"有几个额度、几个启用"显示出来并跳转 ——
    // 额度的增删改在 QuotaViewModel 里（有它自己的一组测试）。

    @Test
    fun `初始时没有额度`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.enabledQuotaCount).isEqualTo(0)
        // v2.11：没有额度时摘要为空串（界面上那一行不会出现半句"其中 "）
        assertThat(viewModel.uiState.value.quotaStatusSummary).isEmpty()
    }

    // ================== v2.11（用户第 3 条规则 1）：这一页也只给文字状态
    //
    // 用户要求"不展示还剩多少分钟、也不展示已用多少分钟（已用 + 上限等于剩余，
    // 等于变相暴露），只展示文字状态"。这一页的额度区块同样是"额度界面"，
    // 所以摘要里**一个分钟数都不能有**。

    @Test
    fun `额度摘要只由文字状态组成，没有任何分钟数`() = runTest {
        quotaRepo.add("A", 30, ResetPeriod.DAILY, setOf("com.douyin"), enabled = true)
        advanceUntilIdle()

        val summary = viewModel.uiState.value.quotaStatusSummary
        assertThat(summary).isEqualTo("其中 1 个【限额生效中】")
        // 防御性断言：整句里不许出现"分钟""还剩""已用"这几个词
        assertThat(summary).doesNotContain("分钟")
        assertThat(summary).doesNotContain("还剩")
        assertThat(summary).doesNotContain("已用")
    }

    @Test
    fun `三种状态各数一遍，为 0 的省掉`() = runTest {
        // ⚠️ 本轮起状态是**四种**（多了【配置无效】）。这条断言的意图没降低：
        // 摘要只数**实际出现过的**状态，为 0 的一律省掉 ——
        // 四种状态各数一遍正是它现在的实现（见 AddUiState.quotaStatusSummary）。
        // 生效中：30 分钟额度，没有任何用量
        quotaRepo.add("生效", 30, ResetPeriod.DAILY, setOf("com.a"), enabled = true)
        // 已耗尽：1 分钟额度 + 0 用量也算用尽（`isExhausted(0, 1)` 为 false，
        // 所以这里要真的有用量 —— 下面用 30 分钟）
        val exhaustedId = quotaRepo.add(
            name = "用尽",
            limitMinutes = 1,
            resetPeriod = ResetPeriod.DAILY,
            packageNames = setOf("com.b"),
            enabled = true
        )
        // 已关闭
        quotaRepo.add("关闭", 30, ResetPeriod.DAILY, setOf("com.c"), enabled = false)
        // 给"用尽"那条造 30 分钟用量（> 1 分钟）
        val start = LocalDate.of(2026, 10, 14).atTime(9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        usageRepo.recordSegment("com.b", start, start + 30 * 60_000L)
        // 触发一次重新收集（额度定义变了，Flow 会重发）
        quotaRepo.setEnabled(exhaustedId, true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.quotaCount).isEqualTo(3)
        assertThat(state.quotaStatuses).hasSize(3)
        assertThat(state.quotaStatuses.count { it == QuotaStatus.ACTIVE }).isEqualTo(1)
        assertThat(state.quotaStatuses.count { it == QuotaStatus.DISABLED }).isEqualTo(1)

        // 摘要里只出现文字状态
        val summary = state.quotaStatusSummary
        assertThat(summary).contains("【限额生效中】")
        assertThat(summary).contains("【已关闭】")
        assertThat(summary).doesNotContain("分钟")
        assertThat(summary).doesNotContain("还剩")
    }

    @Test
    fun `额度用量变化后可以手动重算状态 —— 时间流逝 Flow 观察不到`() = runTest {
        // "生效中 / 已耗尽"是剩余分钟的函数 = 时间的函数。
        // 用户在页面停留期间额度正好用完时，Flow 不会重发，
        // 所以界面回到前台时会调 refreshQuotaStatuses（与 QuotaScreen 同一处理）。
        val id = quotaRepo.add("用尽", 1, ResetPeriod.DAILY, setOf("com.b"), enabled = true)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.quotaStatuses.single())
            .isEqualTo(QuotaStatus.ACTIVE)

        val start = LocalDate.of(2026, 10, 14).atTime(9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        usageRepo.recordSegment("com.b", start, start + 30 * 60_000L)
        viewModel.refreshQuotaStatuses()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.quotaStatuses.single())
            .isEqualTo(QuotaStatus.EXHAUSTED)
        assertThat(id).isGreaterThan(0L)
    }

    @Test
    fun `新增额度后入口计数自动更新`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(0)

        quotaRepo.add(
            name = "短视频",
            limitMinutes = 30,
            resetPeriod = ResetPeriod.DAILY,
            packageNames = setOf("com.ss.android.ugc.aweme")
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.enabledQuotaCount).isEqualTo(1)
    }

    @Test
    fun `停用的额度计入总数但不计入启用数`() = runTest {
        quotaRepo.add("A", 30, ResetPeriod.DAILY, setOf("com.douyin"), enabled = true)
        quotaRepo.add("B", 30, ResetPeriod.DAILY, setOf("com.kuaishou"), enabled = false)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(2)
        assertThat(viewModel.uiState.value.enabledQuotaCount).isEqualTo(1)
    }

    @Test
    fun `删除额度后入口计数跟着减少`() = runTest {
        val id = quotaRepo.add("A", 30, ResetPeriod.DAILY, setOf("com.douyin"))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(1)

        quotaRepo.delete(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(0)
    }

    @Test
    fun `额度为空不影响页面是否为空的判定`() = runTest {
        quotaRepo.add("A", 30, ResetPeriod.DAILY, setOf("com.douyin"))
        advanceUntilIdle()

        // 只加了一个额度，页面仍然显示"还没有习惯或规划"的引导
        assertThat(viewModel.uiState.value.isEmpty).isTrue()
        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(1)
    }

    @Test
    fun `额度不会混进习惯或规划列表`() = runTest {
        quotaRepo.add("短视频", 30, ResetPeriod.DAILY, setOf("com.douyin"))
        habitRepo.save(habit("早起"))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.habits.map { it.name }).containsExactly("早起")
        assertThat(viewModel.uiState.value.plans).isEmpty()
        assertThat(viewModel.uiState.value.quotaCount).isEqualTo(1)
    }

    // ------------------------------------------------------------ 加号弹窗（本轮改造）
    //
    // 用户要求"把加号弹窗里的名称输入删掉"。于是弹窗不再收集名称，
    // 「明日规划」这条路径上带过去的就是 [AddViewModel.DEFAULT_PLAN_NAME] ——
    // 它因此成了**唯一的**名字来源，必须能被仓库层接受
    // （名称为空的规划会被 PlanRepository 拒绝）。
    //
    // 弹窗的按钮顺序（明日规划 / 习惯 / 时间限额）与主体区块顺序是
    // Compose 界面的事，纯 JVM 单测碰不到；这里钉住的是它依赖的那份数据契约。

    @Test
    fun `弹窗不再收名称，兜底的规划名必须能被仓库接受`() = runTest {
        advanceUntilIdle()
        assertThat(AddViewModel.DEFAULT_PLAN_NAME).isNotEmpty()
        assertThat(AddViewModel.DEFAULT_PLAN_NAME.trim()).isEqualTo(AddViewModel.DEFAULT_PLAN_NAME)

        val id = planRepo.addPlan(
            AddViewModel.DEFAULT_PLAN_NAME,
            tomorrow,
            9 * 60,
            10 * 60
        )
        advanceUntilIdle()

        assertThat(planRepo.getById(id)!!.name).isEqualTo(AddViewModel.DEFAULT_PLAN_NAME)
        assertThat(viewModel.uiState.value.plans.map { it.name })
            .containsExactly(AddViewModel.DEFAULT_PLAN_NAME)
    }

    @Test
    fun `弹窗里选的时段配上兜底名称就是一个合法的规划`() = runTest {
        advanceUntilIdle()
        // 与 AddScreen 弹窗 2 的「下一步」完全同一条路径：
        // 名称用兜底值、起止用两个滚轮的当前值
        assertThat(
            viewModel.validateTimes(
                AddViewModel.DEFAULT_START_MINUTE,
                AddViewModel.DEFAULT_END_MINUTE
            )
        ).isNull()

        val id = planRepo.addPlan(
            AddViewModel.DEFAULT_PLAN_NAME,
            tomorrow,
            AddViewModel.DEFAULT_START_MINUTE,
            AddViewModel.DEFAULT_END_MINUTE
        )
        advanceUntilIdle()

        val saved = planRepo.getById(id)!!
        assertThat(saved.startMinute).isEqualTo(AddViewModel.DEFAULT_START_MINUTE)
        assertThat(saved.endMinute).isEqualTo(AddViewModel.DEFAULT_END_MINUTE)
    }
}
